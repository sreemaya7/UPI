import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Password/PIN hashing, signed session tokens (JWT, HS256) and rate limiting. Uses only the JDK. */
final class Security {
    private Security() {}

    static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 210_000; // OWASP guidance for PBKDF2-HMAC-SHA256
    private static byte[] secret;
    private static String dummyHash;

    static void init(byte[] appSecret) {
        secret = appSecret;
        dummyHash = hash("not-a-real-password");
    }

    static byte[] hmac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] pbkdf2(String pre, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pre.toCharArray(), salt, iter, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Salted PBKDF2, with the server secret mixed in first (a "pepper"), so a leaked DB alone is not enough to brute-force PINs. */
    static String hash(String text) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        String pre = Base64.getEncoder().encodeToString(hmac("pw|" + text));
        byte[] h = pbkdf2(pre, salt, ITERATIONS);
        return "v1$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    static boolean verify(String text, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("v1")) return false;
            int iter = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            String pre = Base64.getEncoder().encodeToString(hmac("pw|" + text));
            return MessageDigest.isEqual(want, pbkdf2(pre, salt, iter));
        } catch (Exception e) { return false; }
    }

    /** Burn the same CPU time as a real check, so unknown accounts cannot be told apart by response time. */
    static void dummyVerify(String text) { verify(text, dummyHash); }

    // ------------------------------------------------------------ tokens (JWT HS256)

    private static String b64u(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    static String issueToken(long userId, int tokenVersion, long ttlSeconds) {
        long now = System.currentTimeMillis() / 1000;
        String header = b64u("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64u(Json.write(Json.obj("sub", userId, "tv", tokenVersion, "iat", now, "exp", now + ttlSeconds))
                .getBytes(StandardCharsets.UTF_8));
        String sig = b64u(hmac(header + "." + payload));
        return header + "." + payload + "." + sig;
    }

    /** Returns {userId, tokenVersion} or null if the token is malformed, forged or expired. The algorithm is never read from the token. */
    static long[] verifyToken(String token) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) return null;
            byte[] want = hmac(p[0] + "." + p[1]);
            byte[] got = Base64.getUrlDecoder().decode(p[2]);
            if (!MessageDigest.isEqual(want, got)) return null;
            Map<String, Object> c = Json.parseObject(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8));
            long exp = ((java.math.BigDecimal) c.get("exp")).longValue();
            if (exp < System.currentTimeMillis() / 1000) return null;
            return new long[]{((java.math.BigDecimal) c.get("sub")).longValue(), ((java.math.BigDecimal) c.get("tv")).longValue()};
        } catch (Exception e) { return null; }
    }

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return b64u(b);
    }

    // ------------------------------------------------------------ rate limiting

    private static final Map<String, ArrayDeque<Long>> HITS = new ConcurrentHashMap<>();

    /** Sliding-window limiter. Returns seconds to wait if the limit is exceeded, else 0. */
    static long rateLimit(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        if (HITS.size() > 20_000) HITS.clear(); // crude memory guard
        ArrayDeque<Long> q = HITS.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= max) return Math.max(1, (windowMs - (now - q.peekFirst()) + 999) / 1000);
            q.addLast(now);
            return 0;
        }
    }
}
