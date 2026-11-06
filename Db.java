import java.sql.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * PostgreSQL database access.
 * Uses one shared connection protected by a lock.
 * Every unit of work runs inside a transaction.
 */
final class Db {
    private Db() {}

    private static Connection conn;
    private static final Object LOCK = new Object();

    interface Tx<T> {
        T run(Connection c) throws Exception;
    }

    /**
     * Connects using Render's DATABASE_URL environment variable.
     *
     * Expected URL format:
     * postgresql://username:password@hostname:5432/database
     */
    static void open() throws Exception {
        Class.forName("org.postgresql.Driver");

        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException(
                "DATABASE_URL environment variable is not configured"
            );
        }

        String jdbcUrl;
        String username;
        String password;

        if (databaseUrl.startsWith("jdbc:postgresql://")) {
            // Also supports an explicitly configured JDBC URL.
            jdbcUrl = databaseUrl;
            username = System.getenv("DB_USER");
            password = System.getenv("DB_PASSWORD");
        } else {
            URI uri = URI.create(databaseUrl);

            String scheme = uri.getScheme();
            if (!"postgres".equals(scheme) &&
                !"postgresql".equals(scheme)) {
                throw new IllegalArgumentException(
                    "DATABASE_URL must use postgres:// or postgresql://"
                );
            }

            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String database = uri.getPath();

            if (host == null || database == null || database.length() < 2) {
                throw new IllegalArgumentException(
                    "DATABASE_URL is missing its host or database name"
                );
            }

            database = database.substring(1);

            String userInfo = uri.getRawUserInfo();
            if (userInfo == null || !userInfo.contains(":")) {
                throw new IllegalArgumentException(
                    "DATABASE_URL is missing its username or password"
                );
            }

            String[] credentials = userInfo.split(":", 2);
            username = decode(credentials[0]);
            password = decode(credentials[1]);

            String sslMode = System.getenv("PGSSLMODE");
            if (sslMode == null || sslMode.isBlank()) {
                sslMode = "require";
            }

            jdbcUrl = "jdbc:postgresql://" + host + ":" + port +
                      "/" + database + "?sslmode=" + sslMode;
        }

        conn = DriverManager.getConnection(jdbcUrl, username, password);

        createTables();

        System.out.println("Connected to PostgreSQL successfully.");
    }

    private static String decode(String value) {
        // Preserve literal '+' characters in URL-encoded credentials.
        return URLDecoder.decode(
            value.replace("+", "%2B"),
            StandardCharsets.UTF_8
        );
    }

    /**
     * Creates the application tables if they do not already exist.
     */
    private static void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    k TEXT PRIMARY KEY,
                    v TEXT NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGSERIAL PRIMARY KEY,
                    account_number TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    phone TEXT NOT NULL UNIQUE,
                    upi_id TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    pin_hash TEXT NOT NULL,
                    balance_paise BIGINT NOT NULL DEFAULT 0
                        CHECK (balance_paise >= 0),
                    failed_logins INTEGER NOT NULL DEFAULT 0,
                    login_locked_until BIGINT NOT NULL DEFAULT 0,
                    failed_pins INTEGER NOT NULL DEFAULT 0,
                    pin_locked_until BIGINT NOT NULL DEFAULT 0,
                    token_version INTEGER NOT NULL DEFAULT 0,
                    monthly_budget_paise BIGINT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    id BIGSERIAL PRIMARY KEY,
                    owner_id BIGINT NOT NULL REFERENCES users(id),
                    name TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE(owner_id, phone)
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS txns (
                    id BIGSERIAL PRIMARY KEY,
                    ref TEXT NOT NULL UNIQUE,
                    type TEXT NOT NULL,
                    from_user BIGINT REFERENCES users(id),
                    to_user BIGINT REFERENCES users(id),
                    amount_paise BIGINT NOT NULL CHECK(amount_paise > 0),
                    note TEXT NOT NULL DEFAULT '',
                    category TEXT NOT NULL DEFAULT 'others',
                    status TEXT NOT NULL DEFAULT 'SUCCESS',
                    flagged INTEGER NOT NULL DEFAULT 0,
                    flag_reason TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT 'manual',
                    idem_key TEXT,
                    from_bal BIGINT,
                    to_bal BIGINT,
                    orig_id BIGINT,
                    created_at BIGINT NOT NULL,
                    reversed_at BIGINT
                )
            """);

            st.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_txn_idem
                ON txns(from_user, idem_key)
                WHERE idem_key IS NOT NULL
            """);

            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_txn_from
                ON txns(from_user, created_at)
            """);

            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_txn_to
                ON txns(to_user, created_at)
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS qr_codes (
                    token TEXT PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    amount_paise BIGINT NOT NULL DEFAULT 0,
                    note TEXT NOT NULL DEFAULT '',
                    expires_at BIGINT NOT NULL,
                    used INTEGER NOT NULL DEFAULT 0
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS splits (
                    id BIGSERIAL PRIMARY KEY,
                    creator_id BIGINT NOT NULL REFERENCES users(id),
                    total_paise BIGINT NOT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    created_at BIGINT NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS split_shares (
                    id BIGSERIAL PRIMARY KEY,
                    split_id BIGINT NOT NULL REFERENCES splits(id),
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    amount_paise BIGINT NOT NULL CHECK(amount_paise > 0),
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    txn_id BIGINT
                )
            """);
        }
    }

    /**
     * Executes work in a database transaction.
     * Commits on success and rolls back on failure.
     */
    static <T> T tx(Tx<T> work) throws Exception {
        synchronized (LOCK) {
            conn.setAutoCommit(false);

            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Throwable e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // Preserve the original exception.
                }

                if (e instanceof Exception) {
                    throw (Exception) e;
                }
                if (e instanceof Error) {
                    throw (Error) e;
                }
                throw new RuntimeException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Loads the JWT signing secret.
     * Uses APP_SECRET when configured; otherwise stores a generated secret.
     */
    static byte[] loadSecret() throws Exception {
        String env = System.getenv("APP_SECRET");

        if (env != null && env.length() >= 16) {
            return env.getBytes(StandardCharsets.UTF_8);
        }

        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT v FROM settings WHERE k='app_secret'")) {

                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    return Base64.getDecoder().decode(rs.getString(1));
                }
            }

            byte[] secret = new byte[48];
            Security.RNG.nextBytes(secret);

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO settings(k,v) VALUES('app_secret',?)")) {

                ps.setString(
                    1,
                    Base64.getEncoder().encodeToString(secret)
                );

                ps.executeUpdate();
            }

            return secret;
        });
    }
}
