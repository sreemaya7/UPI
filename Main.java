import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** JavaBank Pay: a simulated UPI wallet. No real money is ever involved. */
public class Main {

    // ================================================================== plumbing

    interface Handler { Object handle(Ctx c) throws Exception; }
    record Route(boolean auth, Handler h) {}
    record Raw(byte[] bytes, String type, String filename) {}

    static final Map<String, Route> ROUTES = new HashMap<>();
    static final Map<String, byte[]> STATIC = new HashMap<>();
    static final int MAX_BODY = 16 * 1024;
    static final long TOKEN_TTL_SECONDS = 3600;

    static void route(String method, String path, boolean auth, Handler h) { ROUTES.put(method + " " + path, new Route(auth, h)); }

    static final class Ctx {
        final HttpExchange ex;
        final String ip;
        Wallet.User user;
        private Map<String, Object> body;
        private Map<String, String> query;

        Ctx(HttpExchange ex, String ip) { this.ex = ex; this.ip = ip; }

        Map<String, Object> body() throws IOException {
            if (body != null) return body;
            byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new ApiException(413, "Request too large");
            if (b.length == 0) return body = new HashMap<>();
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new ApiException(415, "Content-Type must be application/json");
            try { return body = Json.parseObject(new String(b, StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw new ApiException(400, "Invalid JSON"); }
        }

        Map<String, String> query() {
            if (query != null) return query;
            query = new HashMap<>();
            String q = ex.getRequestURI().getRawQuery();
            if (q != null && !q.isEmpty())
                for (String pair : q.split("&")) {
                    String[] kv = pair.split("=", 2);
                    query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
                }
            return query;
        }

        /** Trimmed text field ("" if absent). */
        String str(String key, int max) throws IOException {
            String s = raw(key, max);
            return s == null ? "" : s.trim();
        }

        /** Text field exactly as sent (passwords and PINs must not be trimmed). */
        String raw(String key, int max) throws IOException {
            Object o = body().get(key);
            if (o == null) return null;
            if (!(o instanceof String)) throw new ApiException(400, key + " must be text");
            if (((String) o).length() > max) throw new ApiException(400, key + " is too long");
            return (String) o;
        }

        void limit(String name, int max, long windowMs) {
            long wait = Security.rateLimit(name + ":" + (user != null ? "u" + user.id() : ip), max, windowMs);
            if (wait > 0) throw new ApiException(429, "Too many requests. Try again in " + wait + "s.", wait);
        }
    }

    static String clientIp(HttpExchange ex) {
        // behind Render's proxy the last X-Forwarded-For entry is the one the proxy itself appended
        String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(self), microphone=(), geolocation=()");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                "font-src https://fonts.gstatic.com; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if ("https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto")))
            h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, String... extraHeaders) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) ex.getResponseHeaders().set(extraHeaders[i], extraHeaders[i + 1]);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    static void sendJson(HttpExchange ex, int code, Map<String, Object> m, String... extra) throws IOException {
        String[] h = Arrays.copyOf(extra, extra.length + 2);
        h[extra.length] = "Cache-Control";
        h[extra.length + 1] = "no-store";
        send(ex, code, "application/json; charset=utf-8", Json.write(m).getBytes(StandardCharsets.UTF_8), h);
    }

    static void handleApi(HttpExchange ex) throws IOException {
        Ctx c = new Ctx(ex, clientIp(ex));
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            Route r = ROUTES.get(method + " " + path);
            if (r == null) {
                boolean pathExists = ROUTES.keySet().stream().anyMatch(k -> k.endsWith(" " + path));
                throw new ApiException(pathExists ? 405 : 404, pathExists ? "Method not allowed" : "Not found");
            }
            c.limit("ip", 300, 60_000);
            if (r.auth()) c.user = authenticate(ex);
            Object out = r.h().handle(c);
            if (out instanceof Raw) {
                Raw raw = (Raw) out;
                send(ex, 200, raw.type(), raw.bytes(), "Content-Disposition", "attachment; filename=\"" + raw.filename() + "\"", "Cache-Control", "no-store");
                return;
            }
            @SuppressWarnings("unchecked") Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) out);
            m.putIfAbsent("success", true);
            sendJson(ex, 200, m);
        } catch (ApiException e) {
            String[] extra = e.retryAfterSeconds > 0 ? new String[]{"Retry-After", String.valueOf(e.retryAfterSeconds)} : new String[0];
            sendJson(ex, e.status, Json.obj("success", false, "message", e.getMessage()), extra);
        } catch (Exception e) {
            System.err.println("ERROR " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + ": " + e);
            sendJson(ex, 500, Json.obj("success", false, "message", "Something went wrong. Please try again."));
        }
    }

    static Wallet.User authenticate(HttpExchange ex) throws Exception {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new ApiException(401, "Please sign in");
        long[] t = Security.verifyToken(h.substring(7).trim());
        if (t == null) throw new ApiException(401, "Session expired. Please sign in again");
        Wallet.User u = Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("SELECT token_version FROM users WHERE id=?")) {
                ps.setLong(1, t[0]);
                ResultSet rs = ps.executeQuery();
                if (!rs.next() || rs.getLong(1) != t[1]) return null;
            }
            return Wallet.user(cn, t[0]);
        });
        if (u == null) throw new ApiException(401, "Session expired. Please sign in again");
        return u;
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        byte[] b = STATIC.get(p.substring(1));
        if (!ex.getRequestMethod().equalsIgnoreCase("GET") || b == null) { send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
        String type = p.endsWith(".html") ? "text/html; charset=utf-8" : p.endsWith(".js") ? "text/javascript; charset=utf-8"
                : p.endsWith(".css") ? "text/css; charset=utf-8" : p.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        send(ex, 200, type, b, "Cache-Control", p.equals("/index.html") ? "no-cache" : "public, max-age=300");
    }

    // ================================================================== auth

    record LoginRow(long id, String hash, int failed, long lockedUntil) {}

    static boolean weakPin(String pin) {
        if (pin.chars().distinct().count() == 1) return true;
        boolean asc = true, desc = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) != pin.charAt(i - 1) + 1) asc = false;
            if (pin.charAt(i) != pin.charAt(i - 1) - 1) desc = false;
        }
        return asc || desc;
    }

    static void validatePin(String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) throw new ApiException(400, "UPI PIN must be 4 to 6 digits");
        if (weakPin(pin)) throw new ApiException(400, "That PIN is too easy to guess. Avoid repeated or sequential digits.");
    }

    static void validatePassword(String pw, String phone) {
        if (pw == null || pw.length() < 8) throw new ApiException(400, "Password must be at least 8 characters");
        if (pw.length() > 100) throw new ApiException(400, "Password is too long");
        if (!pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*")) throw new ApiException(400, "Password must contain a letter and a number");
        if (pw.equals(phone)) throw new ApiException(400, "Password cannot be your phone number");
    }

    static Map<String, Object> session(Wallet.User u, int tokenVersion) {
        return Json.obj("token", Security.issueToken(u.id(), tokenVersion, TOKEN_TTL_SECONDS), "expiresIn", TOKEN_TTL_SECONDS, "user", Wallet.userView(u));
    }

    static Object register(Ctx c) throws Exception {
        c.limit("register", 10, 3_600_000);
        String name = c.str("name", 60), phone = c.str("phone", 15), pw = c.raw("password", 200), pin = c.raw("pin", 10);
        if (!Wallet.NAME_OK.matcher(name).matches()) throw new ApiException(400, "Enter your full name (letters only, 2-50 characters)");
        if (!Wallet.PHONE.matcher(phone).matches()) throw new ApiException(400, "Enter a valid 10-digit Indian mobile number");
        validatePassword(pw, phone);
        validatePin(pin);
        String pwHash = Security.hash(pw), pinHash = Security.hash(pin); // slow on purpose, done outside the DB lock
        long now = System.currentTimeMillis();
        Wallet.User u = Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM users WHERE phone=?")) {
                ps.setString(1, phone);
                if (ps.executeQuery().next()) throw new ApiException(409, "This mobile number is already registered");
            }
            String acc;
            while (true) {
                acc = "JB" + String.format("%08d", Security.RNG.nextInt(100_000_000));
                try (PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM users WHERE account_number=?")) {
                    ps.setString(1, acc);
                    if (!ps.executeQuery().next()) break;
                }
            }
            long id;
            try (PreparedStatement ps = cn.prepareStatement(
                    "INSERT INTO users(account_number,name,phone,upi_id,password_hash,pin_hash,created_at) VALUES(?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, acc);
                ps.setString(2, name);
                ps.setString(3, phone);
                ps.setString(4, phone + "@javapay");
                ps.setString(5, pwHash);
                ps.setString(6, pinHash);
                ps.setLong(7, now);
                ps.executeUpdate();
                ResultSet k = ps.getGeneratedKeys();
                k.next();
                id = k.getLong(1);
            }
            Wallet.welcome(cn, id, now);
            return Wallet.user(cn, id);
        });
        return session(u, 0);
    }

    static Object login(Ctx c) throws Exception {
        c.limit("login", 10, 60_000);
        String ident = c.str("identifier", 30), pw = c.raw("password", 200);
        if (ident.isEmpty() || pw == null || pw.isEmpty()) throw new ApiException(400, "Enter your mobile/account number and password");
        LoginRow row = Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("SELECT id,password_hash,failed_logins,login_locked_until FROM users WHERE phone=? OR account_number=?")) {
                ps.setString(1, ident);
                ps.setString(2, ident.toUpperCase(Locale.ROOT));
                ResultSet rs = ps.executeQuery();
                return rs.next() ? new LoginRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)) : null;
            }
        });
        if (row == null) { Security.dummyVerify(pw); throw new ApiException(401, "Invalid credentials"); }
        long now = System.currentTimeMillis();
        if (row.lockedUntil() > now) {
            long mins = (row.lockedUntil() - now + 59_999) / 60_000;
            throw new ApiException(429, "Too many failed attempts. Try again in " + mins + " minute(s).", mins * 60);
        }
        if (!Security.verify(pw, row.hash())) {
            Db.tx(cn -> {
                int fails = row.failed() + 1;
                try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET failed_logins=?, login_locked_until=? WHERE id=?")) {
                    ps.setInt(1, fails >= 5 ? 0 : fails);
                    ps.setLong(2, fails >= 5 ? now + 15 * 60_000 : 0);
                    ps.setLong(3, row.id());
                    ps.executeUpdate();
                }
                return null;
            });
            throw new ApiException(401, "Invalid credentials");
        }
        return Db.tx(cn -> {
            if (row.failed() > 0) try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET failed_logins=0 WHERE id=?")) {
                ps.setLong(1, row.id());
                ps.executeUpdate();
            }
            int tv;
            try (PreparedStatement ps = cn.prepareStatement("SELECT token_version FROM users WHERE id=?")) {
                ps.setLong(1, row.id());
                ResultSet rs = ps.executeQuery();
                rs.next();
                tv = rs.getInt(1);
            }
            return session(Wallet.user(cn, row.id()), tv);
        });
    }

    /** Verifies the UPI PIN with a lockout after 5 wrong attempts. Counters are committed even when the PIN is wrong. */
    static void requirePin(Wallet.User u, String pin) throws Exception {
        if (pin == null || !pin.matches("\\d{4,6}")) throw new ApiException(400, "Enter your 4-6 digit UPI PIN");
        String[] row = Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("SELECT pin_hash,failed_pins,pin_locked_until FROM users WHERE id=?")) {
                ps.setLong(1, u.id());
                ResultSet rs = ps.executeQuery();
                rs.next();
                return new String[]{rs.getString(1), String.valueOf(rs.getInt(2)), String.valueOf(rs.getLong(3))};
            }
        });
        long now = System.currentTimeMillis(), lockedUntil = Long.parseLong(row[2]);
        if (lockedUntil > now) {
            long mins = (lockedUntil - now + 59_999) / 60_000;
            throw new ApiException(429, "UPI PIN is locked. Try again in " + mins + " minute(s).", mins * 60);
        }
        int failed = Integer.parseInt(row[1]);
        if (Security.verify(pin, row[0])) {
            if (failed > 0) Db.tx(cn -> {
                try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET failed_pins=0 WHERE id=?")) { ps.setLong(1, u.id()); ps.executeUpdate(); }
                return null;
            });
            return;
        }
        int left = Db.tx(cn -> {
            int fails = failed + 1;
            try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET failed_pins=?, pin_locked_until=? WHERE id=?")) {
                ps.setInt(1, fails >= 5 ? 0 : fails);
                ps.setLong(2, fails >= 5 ? now + 15 * 60_000 : 0);
                ps.setLong(3, u.id());
                ps.executeUpdate();
            }
            return Math.max(0, 5 - fails);
        });
        throw new ApiException(403, left == 0 ? "Too many wrong PINs. UPI PIN locked for 15 minutes." : "Incorrect PIN. " + left + " attempt(s) left.");
    }

    static Object changePassword(Ctx c) throws Exception {
        c.limit("chpw", 5, 60_000);
        String oldPw = c.raw("oldPassword", 200), newPw = c.raw("newPassword", 200);
        String stored = Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("SELECT password_hash FROM users WHERE id=?")) {
                ps.setLong(1, c.user.id());
                ResultSet rs = ps.executeQuery();
                rs.next();
                return rs.getString(1);
            }
        });
        if (oldPw == null || !Security.verify(oldPw, stored)) throw new ApiException(403, "Current password is incorrect");
        validatePassword(newPw, c.user.phone());
        String hash = Security.hash(newPw);
        return Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET password_hash=?, token_version=token_version+1 WHERE id=?")) {
                ps.setString(1, hash);
                ps.setLong(2, c.user.id());
                ps.executeUpdate();
            }
            int tv;
            try (PreparedStatement ps = cn.prepareStatement("SELECT token_version FROM users WHERE id=?")) {
                ps.setLong(1, c.user.id());
                ResultSet rs = ps.executeQuery();
                rs.next();
                tv = rs.getInt(1);
            }
            Map<String, Object> s = session(Wallet.user(cn, c.user.id()), tv);
            s.put("message", "Password updated. Other devices have been signed out.");
            return s;
        });
    }

    static Object changePin(Ctx c) throws Exception {
        c.limit("chpin", 10, 60_000);
        requirePin(c.user, c.raw("oldPin", 10));
        String np = c.raw("newPin", 10);
        validatePin(np);
        String hash = Security.hash(np);
        Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET pin_hash=? WHERE id=?")) {
                ps.setString(1, hash);
                ps.setLong(2, c.user.id());
                ps.executeUpdate();
            }
            return null;
        });
        return Json.obj("message", "UPI PIN updated");
    }

    static Object logout(Ctx c) throws Exception {
        Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET token_version=token_version+1 WHERE id=?")) { ps.setLong(1, c.user.id()); ps.executeUpdate(); }
            return null;
        });
        return Json.obj("message", "Signed out");
    }

    // ================================================================== contacts


static Object listContacts(Ctx c) throws Exception {
    return Db.tx(cn -> {
        List<Map<String, Object>> out = new ArrayList<>();

        try (PreparedStatement ps = cn.prepareStatement(
                "SELECT c.id,c.name,c.phone,u.upi_id " +
                "FROM contacts c " +
                "LEFT JOIN users u ON u.phone=c.phone " +
                "WHERE c.owner_id=? " +
                "ORDER BY LOWER(c.name), c.name"
        )) {
            ps.setLong(1, c.user.id());

            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                out.add(Json.obj(
                    "id", rs.getLong(1),
                    "name", rs.getString(2),
                    "phone", rs.getString(3),
                    "upiId", rs.getString(4),
                    "registered", rs.getString(4) != null
                ));
            }
        }

        return Json.obj("contacts", out);
    });
}


    static Object addContact(Ctx c) throws Exception {
        String name = c.str("name", 60), phone = c.str("phone", 15);
        if (!Wallet.NAME_OK.matcher(name).matches()) throw new ApiException(400, "Enter a valid name (letters only)");
        if (!Wallet.PHONE.matcher(phone).matches()) throw new ApiException(400, "Enter a valid 10-digit mobile number");
        if (phone.equals(c.user.phone())) throw new ApiException(400, "That is your own number");
        return Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM contacts WHERE owner_id=?")) {
                ps.setLong(1, c.user.id());
                ResultSet rs = ps.executeQuery();
                rs.next();
                if (rs.getInt(1) >= 200) throw new ApiException(400, "Contact limit reached (200)");
            }
            try (PreparedStatement ps = cn.prepareStatement("SELECT name FROM contacts WHERE owner_id=? AND phone=?")) {
                ps.setLong(1, c.user.id());
                ps.setString(2, phone);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) throw new ApiException(409, "Already saved as " + rs.getString(1));
            }
            try (PreparedStatement ps = cn.prepareStatement("INSERT INTO contacts(owner_id,name,phone,created_at) VALUES(?,?,?,?)")) {
                ps.setLong(1, c.user.id());
                ps.setString(2, name);
                ps.setString(3, phone);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            Wallet.User reg = Wallet.userByPhone(cn, phone);
            return Json.obj("message", reg != null ? "Contact saved" : "Contact saved. They have not joined JavaPay yet, so you cannot pay them until they do.", "registered", reg != null);
        });
    }

    static Object deleteContact(Ctx c) throws Exception {
        String id = c.query().getOrDefault("id", "");
        if (!id.matches("\\d{1,12}")) throw new ApiException(400, "Invalid contact");
        return Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("DELETE FROM contacts WHERE id=? AND owner_id=?")) {
                ps.setLong(1, Long.parseLong(id));
                ps.setLong(2, c.user.id());
                if (ps.executeUpdate() != 1) throw new ApiException(404, "Contact not found");
            }
            return Json.obj("message", "Contact removed");
        });
    }

    // ================================================================== AI + payments

    static Object aiParse(Ctx c) throws Exception {
        c.limit("ai", 20, 60_000);
        String text = c.str("text", 200);
        if (text.length() < 3) throw new ApiException(400, "Type something like \"Pay 300 to Priya for lunch\"");
        Map<String, Object> p = Ai.parse(text);
        String intent = (String) p.get("intent");
        if (intent.equals("UNKNOWN"))
            return Json.obj("intent", "UNKNOWN", "message", "I could not understand that. Try \"Pay 300 to Priya for lunch\" or \"Split 900 among me, Arun, Meena\".", "source", p.get("source"));
        long paise = Wallet.toPaise(p.get("amount"), Wallet.MAX_PAYMENT);
        String note = (String) p.get("note");
        return Db.tx(cn -> {
            if (intent.equals("PAY")) {
                String who = (String) p.get("recipient");
                List<Map<String, Object>> matches = Wallet.matchContacts(cn, c.user.id(), who);
                return Json.obj("intent", "PAY", "amount", Wallet.money(paise), "note", note, "category", Wallet.categorize(note), "recipient", who,
                        "matches", matches, "needsChoice", Wallet.needsChoice(matches), "source", p.get("source"),
                        "message", matches.isEmpty() ? "No saved contact matches \"" + who + "\". Add them in Contacts first." : "");
            }
            @SuppressWarnings("unchecked") List<String> names = (List<String>) p.get("participants");
            int n = names.size() + 1;
            long per = paise / n;
            if (per < 1) throw new ApiException(400, "Amount is too small to split " + n + " ways");
            List<Map<String, Object>> parts = new ArrayList<>();
            for (String nm : names) {
                List<Map<String, Object>> matches = Wallet.matchContacts(cn, c.user.id(), nm);
                parts.add(Json.obj("query", nm, "matches", matches, "needsChoice", Wallet.needsChoice(matches)));
            }
            return Json.obj("intent", "SPLIT", "total", Wallet.money(paise), "note", note, "perHead", Wallet.money(per),
                    "yourShare", Wallet.money(paise - per * (n - 1)), "people", n, "participants", parts, "source", p.get("source"));
        });
    }

    static Object payPreview(Ctx c) throws Exception {
        c.limit("preview", 60, 60_000);
        long paise = Wallet.toPaise(c.body().get("amount"), Wallet.MAX_PAYMENT);
        String note = Wallet.cleanNote(c.body().get("note"));
        return Db.tx(cn -> {
            Wallet.User me = Wallet.user(cn, c.user.id());
            Wallet.User to = Wallet.resolvePayee(cn, me, c.body());
            return Json.obj("payee", Json.obj("name", to.name(), "upiId", to.upiId()), "amount", Wallet.money(paise), "note", note,
                    "category", Wallet.categorize(note), "flags", Wallet.fraudCheck(cn, me.id(), to.id(), paise, System.currentTimeMillis()),
                    "sufficient", me.balance() >= paise, "balance", Wallet.money(me.balance()));
        });
    }

    static Object pay(Ctx c) throws Exception {
        c.limit("pay", 30, 60_000);
        Map<String, Object> b = c.body();
        requirePin(c.user, c.raw("pin", 10));
        String qrToken = c.str("qrToken", 64);
        String category = c.str("category", 20);
        String src = c.str("source", 10);
        String idem = c.str("idempotencyKey", 64);
        if (!idem.isEmpty() && !idem.matches("[A-Za-z0-9_-]{8,64}")) throw new ApiException(400, "Invalid request key");
        String noteIn = Wallet.cleanNote(b.get("note"));
        return Db.tx(cn -> {
            Wallet.User me = Wallet.user(cn, c.user.id());
            Wallet.User to;
            long paise;
            String note = noteIn, source = src.equals("ai") ? "ai" : "manual";
            if (!qrToken.isEmpty()) {
                long ownerId, qrAmount, exp;
                int used;
                String qrNote;
                try (PreparedStatement ps = cn.prepareStatement("SELECT user_id,amount_paise,note,expires_at,used FROM qr_codes WHERE token=?")) {
                    ps.setString(1, qrToken);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new ApiException(404, "QR code not recognised");
                    ownerId = rs.getLong(1); qrAmount = rs.getLong(2); qrNote = rs.getString(3); exp = rs.getLong(4); used = rs.getInt(5);
                }
                if (exp < System.currentTimeMillis()) throw new ApiException(410, "This QR code has expired. Ask for a new one.");
                if (used == 1) throw new ApiException(409, "This QR code has already been used");
                to = Wallet.user(cn, ownerId);
                if (qrAmount > 0) {
                    paise = qrAmount; // the amount always comes from the server, never from the scanned text
                    if (b.get("amount") != null && Wallet.toPaise(b.get("amount"), Wallet.MAX_PAYMENT) != qrAmount) throw new ApiException(400, "Amount does not match the QR code");
                    try (PreparedStatement ps = cn.prepareStatement("UPDATE qr_codes SET used=1 WHERE token=? AND used=0")) {
                        ps.setString(1, qrToken);
                        if (ps.executeUpdate() != 1) throw new ApiException(409, "This QR code has already been used");
                    }
                } else paise = Wallet.toPaise(b.get("amount"), Wallet.MAX_PAYMENT);
                if (note.isEmpty()) note = qrNote;
                source = "qr";
            } else {
                to = Wallet.resolvePayee(cn, me, b);
                paise = Wallet.toPaise(b.get("amount"), Wallet.MAX_PAYMENT);
            }
            return Wallet.transfer(cn, me, to, paise, note, category.isEmpty() ? null : category, source, idem.isEmpty() ? null : idem);
        });
    }

    static Object undo(Ctx c) throws Exception {
        c.limit("undo", 10, 60_000);
        Object id = c.body().get("txnId");
        if (!(id instanceof BigDecimal)) throw new ApiException(400, "Choose a payment to undo");
        return Db.tx(cn -> Wallet.undo(cn, Wallet.user(cn, c.user.id()), ((BigDecimal) id).longValue()));
    }

    static Object addMoney(Ctx c) throws Exception {
        c.limit("addmoney", 20, 60_000);
        long paise = Wallet.toPaise(c.body().get("amount"), Wallet.MAX_ADD_MONEY);
        return Db.tx(cn -> Wallet.addMoney(cn, Wallet.user(cn, c.user.id()), paise));
    }

    static Object withdraw(Ctx c) throws Exception {
        c.limit("withdraw", 20, 60_000);
        long paise = Wallet.toPaise(c.body().get("amount"), Wallet.MAX_PAYMENT);
        requirePin(c.user, c.raw("pin", 10));
        return Db.tx(cn -> Wallet.withdraw(cn, Wallet.user(cn, c.user.id()), paise));
    }

    static Object me(Ctx c) throws Exception {
        return Json.obj("user", Wallet.userView(c.user), "serverTime", System.currentTimeMillis());
    }

    static Object transactions(Ctx c) throws Exception {
        String f = c.query().getOrDefault("filter", "all");
        if (!List.of("all", "credit", "debit", "flagged").contains(f)) f = "all";
        int limit = 50;
        try { limit = Integer.parseInt(c.query().getOrDefault("limit", "50")); } catch (NumberFormatException ignored) { }
        String filter = f;
        int lim = limit;
        return Db.tx(cn -> Json.obj("transactions", Wallet.history(cn, c.user.id(), filter, lim, null, null), "serverTime", System.currentTimeMillis()));
    }

    // ================================================================== QR

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    static Object qrCreate(Ctx c) throws Exception {
        c.limit("qr", 20, 60_000);
        Object amt = c.body().get("amount");
        int ttl = c.body().get("ttlMinutes") instanceof BigDecimal ? ((BigDecimal) c.body().get("ttlMinutes")).intValue() : 5;
        if (!List.of(0, 5, 15, 60).contains(ttl)) throw new ApiException(400, "Choose 5, 15 or 60 minutes, or 0 for a permanent QR");
        String note = Wallet.cleanNote(c.body().get("note"));
        boolean hasAmt = amt != null && !amt.toString().isBlank() && new BigDecimal(amt.toString().trim().isEmpty() ? "0" : amt.toString().trim()).signum() != 0;
        long paise = ttl > 0 && hasAmt ? Wallet.toPaise(amt, Wallet.MAX_PAYMENT) : 0; // a permanent QR never carries an amount
        long expires = ttl > 0 ? System.currentTimeMillis() + ttl * 60_000L : 0;
        String token = ttl > 0 ? Security.randomToken(16) : null;
        String pn = c.user.name().length() > 24 ? c.user.name().substring(0, 24) : c.user.name();
        String shortNote = note.length() > 30 ? note.substring(0, 30) : note;
        String payload = null;
        for (int attempt = 0; attempt < 2 && payload == null; attempt++) {
            String cand = "upi://pay?pa=" + enc(c.user.upiId()) + "&pn=" + enc(pn) + (paise > 0 ? "&am=" + Wallet.money(paise).toPlainString() : "")
                    + (attempt == 0 && !shortNote.isEmpty() ? "&tn=" + enc(shortNote) : "") + "&cu=INR" + (token != null ? "&tr=" + token : "");
            if (cand.getBytes(StandardCharsets.UTF_8).length <= 213) payload = cand;
        }
        if (payload == null) throw new ApiException(400, "Name or note is too long for a QR code");
        String png = Qr.pngDataUri(payload, 8);
        if (token != null) {
            String tk = token;
            Db.tx(cn -> {
                try (PreparedStatement ps = cn.prepareStatement("DELETE FROM qr_codes WHERE expires_at < ?")) { ps.setLong(1, System.currentTimeMillis() - 86_400_000L); ps.executeUpdate(); }
                try (PreparedStatement ps = cn.prepareStatement("INSERT INTO qr_codes(token,user_id,amount_paise,note,expires_at) VALUES(?,?,?,?,?)")) {
                    ps.setString(1, tk);
                    ps.setLong(2, c.user.id());
                    ps.setLong(3, paise);
                    ps.setString(4, note);
                    ps.setLong(5, expires);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        return Json.obj("payload", payload, "qr", png, "expiresAt", ttl > 0 ? expires : null, "amount", paise > 0 ? Wallet.money(paise) : null);
    }

    /** Turns scanned QR text into a safe payment preview. Amount and payee for dynamic QRs come from the DB, never from the text. */
    static Object qrResolve(Ctx c) throws Exception {
        c.limit("qrres", 30, 60_000);
        String payload = c.str("payload", 400);
        if (!payload.toLowerCase(Locale.ROOT).startsWith("upi://pay?")) throw new ApiException(400, "That is not a UPI payment QR code");
        Map<String, String> q = new HashMap<>();
        for (String pair : payload.substring(payload.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            try { q.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : ""); }
            catch (IllegalArgumentException e) { throw new ApiException(400, "Malformed QR code"); }
        }
        String pa = q.getOrDefault("pa", ""), tr = q.getOrDefault("tr", "");
        if (pa.isEmpty()) throw new ApiException(400, "QR code has no payee");
        return Db.tx(cn -> {
            Wallet.User me = Wallet.user(cn, c.user.id());
            Wallet.User payee;
            long amount = 0, exp = 0;
            String note = "";
            if (!tr.isEmpty()) {
                try (PreparedStatement ps = cn.prepareStatement("SELECT user_id,amount_paise,note,expires_at,used FROM qr_codes WHERE token=?")) {
                    ps.setString(1, tr);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new ApiException(404, "QR code not recognised");
                    payee = Wallet.user(cn, rs.getLong(1));
                    amount = rs.getLong(2); note = rs.getString(3); exp = rs.getLong(4);
                    if (!payee.upiId().equalsIgnoreCase(pa)) throw new ApiException(400, "QR code looks tampered with");
                    if (exp < System.currentTimeMillis()) throw new ApiException(410, "This QR code has expired. Ask for a new one.");
                    if (rs.getInt(5) == 1) throw new ApiException(409, "This QR code has already been used");
                }
            } else {
                payee = Wallet.userByUpi(cn, pa);
                if (payee == null) throw new ApiException(404, "Payee not found. This simulator only supports JavaPay users.");
            }
            if (payee.id() == me.id()) throw new ApiException(400, "That is your own QR code");
            List<String> flags = amount > 0 ? Wallet.fraudCheck(cn, me.id(), payee.id(), amount, System.currentTimeMillis()) : List.of();
            return Json.obj("payee", Json.obj("name", payee.name(), "upiId", payee.upiId()), "amount", amount > 0 ? Wallet.money(amount) : null,
                    "fixedAmount", amount > 0, "note", note, "qrToken", tr, "expiresAt", exp > 0 ? exp : null, "flags", flags,
                    "balance", Wallet.money(me.balance()));
        });
    }

    // ================================================================== split bills

    static Object splitCreate(Ctx c) throws Exception {
        c.limit("split", 20, 60_000);
        long total = Wallet.toPaise(c.body().get("total"), Wallet.MAX_PAYMENT);
        String note = Wallet.cleanNote(c.body().get("note"));
        Object ps0 = c.body().get("participants");
        if (!(ps0 instanceof List) || ((List<?>) ps0).isEmpty()) throw new ApiException(400, "Choose at least one person to split with");
        List<?> parts = (List<?>) ps0;
        if (parts.size() > 15) throw new ApiException(400, "You can split with at most 15 people");
        return Db.tx(cn -> {
            Wallet.User me = Wallet.user(cn, c.user.id());
            Set<Long> seen = new LinkedHashSet<>();
            List<Wallet.User> people = new ArrayList<>();
            for (Object o : parts) {
                Map<String, Object> m = new HashMap<>();
                if (o instanceof Map && ((Map<?, ?>) o).get("contactId") instanceof BigDecimal) m.put("contactId", ((Map<?, ?>) o).get("contactId"));
                else if (o instanceof BigDecimal) m.put("contactId", o);
                else throw new ApiException(400, "Invalid participant");
                Wallet.User u = Wallet.resolvePayee(cn, me, m);
                if (seen.add(u.id())) people.add(u);
            }
            int n = people.size() + 1;
            long per = total / n;
            if (per < 1) throw new ApiException(400, "Amount is too small to split " + n + " ways");
            long splitId;
            try (PreparedStatement ps = cn.prepareStatement("INSERT INTO splits(creator_id,total_paise,note,created_at) VALUES(?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, me.id());
                ps.setLong(2, total);
                ps.setString(3, note);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                ResultSet k = ps.getGeneratedKeys();
                k.next();
                splitId = k.getLong(1);
            }
            for (Wallet.User u : people)
                try (PreparedStatement ps = cn.prepareStatement("INSERT INTO split_shares(split_id,user_id,amount_paise) VALUES(?,?,?)")) {
                    ps.setLong(1, splitId);
                    ps.setLong(2, u.id());
                    ps.setLong(3, per);
                    ps.executeUpdate();
                }
            return Json.obj("splitId", splitId, "perHead", Wallet.money(per), "yourShare", Wallet.money(total - per * (n - 1)), "people", n,
                    "message", "Requests sent to " + people.size() + " " + (people.size() == 1 ? "person" : "people"));
        });
    }

    static Object splitList(Ctx c) throws Exception {
        return Db.tx(cn -> {
            List<Map<String, Object>> created = new ArrayList<>(), owed = new ArrayList<>();
            try (PreparedStatement ps = cn.prepareStatement("SELECT id,total_paise,note,created_at FROM splits WHERE creator_id=? ORDER BY id DESC LIMIT 20")) {
                ps.setLong(1, c.user.id());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    long sid = rs.getLong(1);
                    List<Map<String, Object>> shares = new ArrayList<>();
                    try (PreparedStatement p2 = cn.prepareStatement("SELECT u.name,sh.amount_paise,sh.status FROM split_shares sh JOIN users u ON u.id=sh.user_id WHERE sh.split_id=?")) {
                        p2.setLong(1, sid);
                        ResultSet r2 = p2.executeQuery();
                        while (r2.next()) shares.add(Json.obj("name", r2.getString(1), "amount", Wallet.money(r2.getLong(2)), "status", r2.getString(3)));
                    }
                    created.add(Json.obj("id", sid, "total", Wallet.money(rs.getLong(2)), "note", rs.getString(3), "time", rs.getLong(4), "shares", shares));
                }
            }
            try (PreparedStatement ps = cn.prepareStatement("SELECT sh.id,sh.amount_paise,sh.status,s.note,s.created_at,u.name FROM split_shares sh JOIN splits s ON s.id=sh.split_id JOIN users u ON u.id=s.creator_id WHERE sh.user_id=? ORDER BY sh.id DESC LIMIT 30")) {
                ps.setLong(1, c.user.id());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) owed.add(Json.obj("shareId", rs.getLong(1), "amount", Wallet.money(rs.getLong(2)), "status", rs.getString(3),
                        "note", rs.getString(4), "time", rs.getLong(5), "from", rs.getString(6)));
            }
            return Json.obj("created", created, "owed", owed);
        });
    }

    static Object splitPay(Ctx c) throws Exception {
        c.limit("splitpay", 20, 60_000);
        Object sid = c.body().get("shareId");
        if (!(sid instanceof BigDecimal)) throw new ApiException(400, "Choose a request to pay");
        requirePin(c.user, c.raw("pin", 10));
        return Db.tx(cn -> {
            Wallet.User me = Wallet.user(cn, c.user.id());
            long shareId = ((BigDecimal) sid).longValue(), amount, creatorId;
            String note;
            try (PreparedStatement ps = cn.prepareStatement("SELECT sh.amount_paise,s.creator_id,s.note FROM split_shares sh JOIN splits s ON s.id=sh.split_id WHERE sh.id=? AND sh.user_id=? AND sh.status='PENDING'")) {
                ps.setLong(1, shareId);
                ps.setLong(2, me.id());
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new ApiException(404, "Request not found or already paid");
                amount = rs.getLong(1); creatorId = rs.getLong(2); note = rs.getString(3);
            }
            try (PreparedStatement ps = cn.prepareStatement("UPDATE split_shares SET status='PAID' WHERE id=? AND status='PENDING'")) {
                ps.setLong(1, shareId);
                if (ps.executeUpdate() != 1) throw new ApiException(409, "Already paid");
            }
            Map<String, Object> r = Wallet.transfer(cn, me, Wallet.user(cn, creatorId), amount, note.isEmpty() ? "Split bill" : "Split: " + note, Wallet.categorize(note), "split", null);
            try (PreparedStatement ps = cn.prepareStatement("UPDATE split_shares SET txn_id=? WHERE id=?")) {
                ps.setLong(1, ((Number) r.get("txnId")).longValue());
                ps.setLong(2, shareId);
                ps.executeUpdate();
            }
            return r;
        });
    }

    // ================================================================== analytics, budget, statement

    static Object analytics(Ctx c) throws Exception { return Db.tx(cn -> Wallet.analytics(cn, Wallet.user(cn, c.user.id()))); }

    static Object setBudget(Ctx c) throws Exception {
        Object a = c.body().get("amount");
        boolean zero = a != null && a.toString().trim().matches("0+(\\.0+)?");
        long paise = zero ? 0 : Wallet.toPaise(a, 10_000_000_00L);
        return Db.tx(cn -> {
            try (PreparedStatement ps = cn.prepareStatement("UPDATE users SET monthly_budget_paise=? WHERE id=?")) {
                ps.setLong(1, paise);
                ps.setLong(2, c.user.id());
                ps.executeUpdate();
            }
            return Json.obj("message", paise == 0 ? "Budget turned off" : "Monthly budget saved");
        });
    }

    static Object statement(Ctx c) throws Exception {
        c.limit("stmt", 10, 60_000);
        YearMonth now = YearMonth.now(Wallet.IST);
        LocalDate from, to;
        try {
            from = c.query().containsKey("from") ? LocalDate.parse(c.query().get("from")) : now.atDay(1);
            to = c.query().containsKey("to") ? LocalDate.parse(c.query().get("to")) : now.atEndOfMonth();
        } catch (Exception e) { throw new ApiException(400, "Dates must look like 2026-11-01"); }
        if (to.isBefore(from) || from.plusDays(366).isBefore(to)) throw new ApiException(400, "Choose a date range of up to one year");
        long fromMs = from.atStartOfDay(Wallet.IST).toInstant().toEpochMilli(), toMs = to.plusDays(1).atStartOfDay(Wallet.IST).toInstant().toEpochMilli();
        List<Map<String, Object>> txns = Db.tx(cn -> Wallet.history(cn, c.user.id(), "all", 500, fromMs, toMs));
        Collections.reverse(txns);
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm").withZone(Wallet.IST);
        List<String[]> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (Map<String, Object> t : txns) {
            boolean isDebit = t.get("direction").equals("DEBIT");
            BigDecimal amt = (BigDecimal) t.get("amount");
            String status = t.get("status").equals("REVERSED") ? " [reversed]" : "";
            String note = (String) t.get("note");
            String details = (isDebit ? "To " : "From ") + t.get("counterparty") + (note.isEmpty() ? "" : " - " + note) + status;
            if (t.get("type").equals("ADD_MONEY") || t.get("type").equals("WITHDRAW")) details = (String) t.get("counterparty");
            // a reversed payment and its reversal entry cancel out, so neither counts towards the totals
            if (!t.get("status").equals("REVERSED") && !t.get("type").equals("REVERSAL")) { if (isDebit) debit = debit.add(amt); else credit = credit.add(amt); }
            BigDecimal bal = (BigDecimal) t.get("balanceAfter");
            rows.add(new String[]{df.format(Instant.ofEpochMilli((Long) t.get("time"))), (String) t.get("ref"), details,
                    isDebit ? String.format("%,.2f", amt) : "", isDebit ? "" : String.format("%,.2f", amt), bal == null ? "" : String.format("%,.2f", bal)});
        }
        List<String> header = List.of("Account holder: " + c.user.name(), "Account: " + c.user.accountNumber() + "    UPI ID: " + c.user.upiId(),
                "Period: " + from + " to " + to, "Generated: " + DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").format(LocalDateTime.now(Wallet.IST)) + " IST");
        List<String> footer = List.of("Total debits (successful): Rs " + String.format("%,.2f", debit), "Total credits (successful): Rs " + String.format("%,.2f", credit),
                "Transactions listed: " + rows.size() + (rows.size() >= 500 ? " (limit reached)" : ""), "",
                "JavaBank Pay is a simulation. All money shown is fake and has no real-world value.");
        return new Raw(Pdf.statement("JavaBank Pay Statement", header, rows, footer), "application/pdf", "javabank-statement-" + from + "-to-" + to + ".pdf");
    }

    // ================================================================== startup

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Db.open();
        Security.init(Db.loadSecret());

        Path web = Path.of(System.getenv().getOrDefault("WEB_DIR", "web"));
        if (Files.isDirectory(web)) {
            try (Stream<Path> files = Files.walk(web)) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
                    STATIC.put(web.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }

        route("GET", "/api/health", false, c -> Json.obj("ok", true));
        route("POST", "/api/register", false, Main::register);
        route("POST", "/api/login", false, Main::login);
        route("POST", "/api/logout", true, Main::logout);
        route("GET", "/api/me", true, Main::me);
        route("POST", "/api/change-password", true, Main::changePassword);
        route("POST", "/api/change-pin", true, Main::changePin);
        route("GET", "/api/contacts", true, Main::listContacts);
        route("POST", "/api/contacts", true, Main::addContact);
        route("DELETE", "/api/contacts", true, Main::deleteContact);
        route("POST", "/api/ai/parse", true, Main::aiParse);
        route("POST", "/api/pay/preview", true, Main::payPreview);
        route("POST", "/api/pay", true, Main::pay);
        route("POST", "/api/undo", true, Main::undo);
        route("POST", "/api/wallet/add", true, Main::addMoney);
        route("POST", "/api/wallet/withdraw", true, Main::withdraw);
        route("GET", "/api/transactions", true, Main::transactions);
        route("POST", "/api/qr/create", true, Main::qrCreate);
        route("POST", "/api/qr/resolve", true, Main::qrResolve);
        route("POST", "/api/split/create", true, Main::splitCreate);
        route("GET", "/api/split", true, Main::splitList);
        route("POST", "/api/split/pay", true, Main::splitPay);
        route("GET", "/api/analytics", true, Main::analytics);
        route("POST", "/api/budget", true, Main::setBudget);
        route("GET", "/api/statement", true, Main::statement);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("JavaBank Pay running on http://localhost:" + port + "  (AI: " +
                (System.getenv("ANTHROPIC_API_KEY") != null ? "Claude API" : "rule-based fallback") + ")");
    }
}
