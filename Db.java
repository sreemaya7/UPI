import java.sql.*;
import java.util.Base64;

/** SQLite access. One connection guarded by a lock; every unit of work is a real transaction. */
final class Db {
    private Db() {}

    private static Connection conn;
    private static final Object LOCK = new Object();

    interface Tx<T> { T run(Connection c) throws Exception; }

    static void open(String path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS users(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "account_number TEXT NOT NULL UNIQUE," +
                    "name TEXT NOT NULL," +
                    "phone TEXT NOT NULL UNIQUE," +
                    "upi_id TEXT NOT NULL UNIQUE," +
                    "password_hash TEXT NOT NULL," +
                    "pin_hash TEXT NOT NULL," +
                    // money is stored as integer paise: no floating-point rounding, and the CHECK makes overdrafts impossible even if app code has a bug
                    "balance_paise INTEGER NOT NULL DEFAULT 0 CHECK(balance_paise >= 0)," +
                    "failed_logins INTEGER NOT NULL DEFAULT 0," +
                    "login_locked_until INTEGER NOT NULL DEFAULT 0," +
                    "failed_pins INTEGER NOT NULL DEFAULT 0," +
                    "pin_locked_until INTEGER NOT NULL DEFAULT 0," +
                    "token_version INTEGER NOT NULL DEFAULT 0," +
                    "monthly_budget_paise INTEGER NOT NULL DEFAULT 0," +
                    "created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS contacts(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "owner_id INTEGER NOT NULL REFERENCES users(id)," +
                    "name TEXT NOT NULL," +
                    "phone TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "UNIQUE(owner_id, phone))");
            st.execute("CREATE TABLE IF NOT EXISTS txns(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ref TEXT NOT NULL UNIQUE," +
                    "type TEXT NOT NULL," +            // TRANSFER, ADD_MONEY, WITHDRAW, REVERSAL
                    "from_user INTEGER REFERENCES users(id)," +
                    "to_user INTEGER REFERENCES users(id)," +
                    "amount_paise INTEGER NOT NULL CHECK(amount_paise > 0)," +
                    "note TEXT NOT NULL DEFAULT ''," +
                    "category TEXT NOT NULL DEFAULT 'others'," +
                    "status TEXT NOT NULL DEFAULT 'SUCCESS'," + // SUCCESS | REVERSED
                    "flagged INTEGER NOT NULL DEFAULT 0," +
                    "flag_reason TEXT NOT NULL DEFAULT ''," +
                    "source TEXT NOT NULL DEFAULT 'manual'," + // manual | ai | qr | split
                    "idem_key TEXT," +
                    "from_bal INTEGER," +
                    "to_bal INTEGER," +
                    "orig_id INTEGER," +
                    "created_at INTEGER NOT NULL," +
                    "reversed_at INTEGER)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_txn_idem ON txns(from_user, idem_key) WHERE idem_key IS NOT NULL");
            st.execute("CREATE INDEX IF NOT EXISTS idx_txn_from ON txns(from_user, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_txn_to ON txns(to_user, created_at)");
            st.execute("CREATE TABLE IF NOT EXISTS qr_codes(" +
                    "token TEXT PRIMARY KEY," +
                    "user_id INTEGER NOT NULL REFERENCES users(id)," +
                    "amount_paise INTEGER NOT NULL DEFAULT 0," +
                    "note TEXT NOT NULL DEFAULT ''," +
                    "expires_at INTEGER NOT NULL," +
                    "used INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS splits(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "creator_id INTEGER NOT NULL REFERENCES users(id)," +
                    "total_paise INTEGER NOT NULL," +
                    "note TEXT NOT NULL DEFAULT ''," +
                    "created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS split_shares(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "split_id INTEGER NOT NULL REFERENCES splits(id)," +
                    "user_id INTEGER NOT NULL REFERENCES users(id)," +
                    "amount_paise INTEGER NOT NULL CHECK(amount_paise > 0)," +
                    "status TEXT NOT NULL DEFAULT 'PENDING'," +
                    "txn_id INTEGER)");
        }
    }

    /** Run work inside one transaction; rolls back on any exception. */
    static <T> T tx(Tx<T> work) throws Exception {
        synchronized (LOCK) {
            conn.setAutoCommit(false);
            try {
                T r = work.run(conn);
                conn.commit();
                return r;
            } catch (Throwable e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** The signing secret: APP_SECRET env var if set, otherwise generated once and stored in the DB. */
    static byte[] loadSecret() throws Exception {
        String env = System.getenv("APP_SECRET");
        if (env != null && env.length() >= 16) return env.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM settings WHERE k='app_secret'")) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return Base64.getDecoder().decode(rs.getString(1));
            }
            byte[] b = new byte[48];
            Security.RNG.nextBytes(b);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings(k,v) VALUES('app_secret',?)")) {
                ps.setString(1, Base64.getEncoder().encodeToString(b));
                ps.executeUpdate();
            }
            return b;
        });
    }
}
