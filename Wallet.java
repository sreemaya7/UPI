import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** All money logic. Every method here runs inside a Db.tx(...) so debit + credit + ledger row are atomic. */
final class Wallet {
    private Wallet() {}

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final long MAX_PAYMENT = 100_000_00L;      // Rs 1,00,000 per payment (real UPI limit)
    static final long DAILY_LIMIT = 100_000_00L;      // Rs 1,00,000 sent per day
    static final long MAX_ADD_MONEY = 10_000_00L;     // simulated top-up per request
    static final long BALANCE_CAP = 100_000_00L;      // top-ups stop once wallet holds Rs 1,00,000
    static final long WELCOME_BONUS = 1_000_00L;      // fake money for new accounts
    static final long UNDO_WINDOW_MS = 30_000;

    static final List<String> CATEGORIES = List.of("food", "travel", "rent", "bills", "shopping", "education", "health", "others");

    record User(long id, String accountNumber, String name, String phone, String upiId, long balance, long monthlyBudget, long createdAt) {}

    // ------------------------------------------------------------------ helpers

    static BigDecimal money(long paise) { return BigDecimal.valueOf(paise, 2); }

    /** Parses a rupee amount with at most 2 decimals. Throws ApiException on anything else. */
    static long toPaise(Object v, long max) {
        if (v == null) throw new ApiException(400, "Amount is required");
        BigDecimal bd;
        try { bd = new BigDecimal(v.toString().trim()); } catch (NumberFormatException e) { throw new ApiException(400, "Amount is not a valid number"); }
        if (bd.signum() <= 0) throw new ApiException(400, "Amount must be greater than zero");
        if (bd.stripTrailingZeros().scale() > 2) throw new ApiException(400, "Amount can have at most 2 decimal places");
        BigDecimal paise = bd.movePointRight(2);
        if (paise.compareTo(BigDecimal.valueOf(max)) > 0) throw new ApiException(400, "Amount exceeds the limit of ₹" + money(max).stripTrailingZeros().toPlainString());
        return paise.longValueExact();
    }

    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("food", List.of("lunch", "dinner", "breakfast", "food", "snack", "tea", "coffee", "biryani", "pizza", "burger", "restaurant", "meal", "swiggy", "zomato", "juice", "canteen"));
        KEYWORDS.put("travel", List.of("bus", "train", "auto", "cab", "uber", "ola", "fuel", "petrol", "diesel", "ticket", "metro", "flight", "taxi", "travel", "toll"));
        KEYWORDS.put("rent", List.of("rent", "hostel", "pg", "deposit"));
        KEYWORDS.put("bills", List.of("bill", "recharge", "electricity", "wifi", "internet", "water", "gas", "emi", "subscription"));
        KEYWORDS.put("shopping", List.of("shopping", "clothes", "shirt", "shoes", "amazon", "flipkart", "gift", "grocery", "groceries"));
        KEYWORDS.put("education", List.of("fees", "fee", "book", "books", "course", "exam", "tuition", "stationery", "notes", "xerox"));
        KEYWORDS.put("health", List.of("medicine", "doctor", "hospital", "pharmacy", "clinic", "medical"));
    }

    static String categorize(String note) {
        String n = " " + note.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}0-9]+", " ") + " ";
        for (Map.Entry<String, List<String>> e : KEYWORDS.entrySet())
            for (String k : e.getValue()) if (n.contains(" " + k + " ")) return e.getKey();
        return "others";
    }

    static String cleanNote(Object o) {
        if (o == null) return "";
        String s = o.toString().replaceAll("[\\p{Cntrl}]", " ").trim();
        if (s.length() > 60) throw new ApiException(400, "Note can be at most 60 characters");
        return s;
    }

    static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");
    static final Pattern NAME_OK = Pattern.compile("^[\\p{L}][\\p{L} .'-]{1,49}$");

    static String newRef() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder b = new StringBuilder("JP");
        for (int i = 0; i < 12; i++) b.append(alphabet.charAt(Security.RNG.nextInt(alphabet.length())));
        return b.toString();
    }

    static long monthStart(YearMonth ym) { return ym.atDay(1).atStartOfDay(IST).toInstant().toEpochMilli(); }

    static long dayStart(long now) { return Instant.ofEpochMilli(now).atZone(IST).toLocalDate().atStartOfDay(IST).toInstant().toEpochMilli(); }

    // ------------------------------------------------------------------ users

    private static final String USER_COLS = "id,account_number,name,phone,upi_id,balance_paise,monthly_budget_paise,created_at";

    private static User map(ResultSet rs) throws SQLException {
        return new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8));
    }

    static User user(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM users WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        }
    }

    static User userByPhone(Connection c, String phone) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM users WHERE phone=?")) {
            ps.setString(1, phone);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        }
    }

    static User userByUpi(Connection c, String upi) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM users WHERE upi_id=?")) {
            ps.setString(1, upi.toLowerCase(Locale.ROOT));
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        }
    }

    static Map<String, Object> userView(User u) {
        return Json.obj("name", u.name(), "accountNumber", u.accountNumber(), "phone", u.phone(), "upiId", u.upiId(),
                "balance", money(u.balance()), "monthlyBudget", money(u.monthlyBudget()), "createdAt", u.createdAt());
    }

    // ------------------------------------------------------------------ contacts & payee resolution

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 0..1 similarity between what the user typed and a saved contact name. Handles case, "Priya" vs "Priya K", and typos. */
    static double similarity(String query, String name) {
        String q = query.toLowerCase(Locale.ROOT).trim(), n = name.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return 0;
        if (q.equals(n)) return 1.0;
        double best = 0;
        if (n.startsWith(q + " ") || n.endsWith(" " + q) || n.contains(" " + q + " ")) best = 0.95;
        else if (n.startsWith(q)) best = 0.85;
        for (String token : n.split("\\s+")) {
            int maxLen = Math.max(token.length(), q.length());
            double s = 1.0 - (double) levenshtein(q, token) / maxLen;
            if (q.length() >= 3 && s > best) best = s * 0.92;
        }
        int maxLen = Math.max(q.length(), n.length());
        best = Math.max(best, (1.0 - (double) levenshtein(q, n) / maxLen) * 0.92);
        return best;
    }

    /** Contacts of this owner that plausibly match the typed name, best first. */
    static List<Map<String, Object>> matchContacts(Connection c, long ownerId, String query) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT c.id,c.name,c.phone,u.upi_id FROM contacts c LEFT JOIN users u ON u.phone=c.phone WHERE c.owner_id=?")) {
            ps.setLong(1, ownerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                double s = similarity(query, rs.getString(2));
                if (s >= 0.7) out.add(Json.obj("contactId", rs.getLong(1), "name", rs.getString(2), "phone", rs.getString(3),
                        "upiId", rs.getString(4), "registered", rs.getString(4) != null, "score", Math.round(s * 100) / 100.0));
            }
        }
        out.sort((x, y) -> Double.compare((Double) y.get("score"), (Double) x.get("score")));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    /** Ask the user to choose when there are several equally good matches (e.g. two Priyas). */
    static boolean needsChoice(List<Map<String, Object>> matches) {
        if (matches.size() < 2) return false;
        double a = (Double) matches.get(0).get("score"), b = (Double) matches.get(1).get("score");
        if (a >= 1.0) return b >= 1.0;   // an exact name wins unless two contacts share that exact name
        return a - b < 0.1;
    }

    /** Resolves {contactId} or {upiId} from a request to a registered user other than the payer. */
    static User resolvePayee(Connection c, User payer, Map<String, Object> body) throws SQLException {
        User p = null;
        Object cid = body.get("contactId");
        Object upi = body.get("upiId");
        if (cid instanceof BigDecimal) {
            try (PreparedStatement ps = c.prepareStatement("SELECT phone FROM contacts WHERE id=? AND owner_id=?")) {
                ps.setLong(1, ((BigDecimal) cid).longValue());
                ps.setLong(2, payer.id());
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new ApiException(404, "Contact not found");
                p = userByPhone(c, rs.getString(1));
                if (p == null) throw new ApiException(404, "That contact has not joined JavaPay yet");
            }
        } else if (upi instanceof String) {
            p = userByUpi(c, ((String) upi).trim());
            if (p == null) throw new ApiException(404, "No JavaPay account found for that UPI ID");
        } else {
            throw new ApiException(400, "Choose who to pay");
        }
        if (p.id() == payer.id()) throw new ApiException(400, "You cannot pay yourself");
        return p;
    }

    // ------------------------------------------------------------------ fraud rules

    /** Simple, explainable rules. A flag never silently blocks a payment: the user sees the reasons first. */
    static List<String> fraudCheck(Connection c, long fromId, long toId, long paise, long now) throws SQLException {
        List<String> r = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT AVG(a), COUNT(*) FROM (SELECT amount_paise a FROM txns WHERE from_user=? AND type='TRANSFER' AND status='SUCCESS' ORDER BY id DESC LIMIT 20)")) {
            ps.setLong(1, fromId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(2) >= 3 && paise > 5 * rs.getDouble(1)) r.add("This is more than 5× your usual payment size.");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM txns WHERE from_user=? AND to_user=? AND type='TRANSFER' AND status='SUCCESS' AND created_at>=?")) {
            ps.setLong(1, fromId);
            ps.setLong(2, toId);
            ps.setLong(3, now - 5 * 60_000);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) >= 2) r.add("Third payment to this person within 5 minutes.");
        }
        int hour = Instant.ofEpochMilli(now).atZone(IST).getHour();
        if (hour < 5) r.add("Payment at an unusual hour (12–5 AM).");
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM txns WHERE from_user=? AND to_user=? AND type='TRANSFER'")) {
            ps.setLong(1, fromId);
            ps.setLong(2, toId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) == 0 && paise >= 5_000_00) r.add("First payment to this person and above ₹5,000.");
        }
        return r;
    }

    // ------------------------------------------------------------------ ledger

    private static void adjust(Connection c, long userId, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET balance_paise = balance_paise + ? WHERE id=?")) {
            ps.setLong(1, delta);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    static long balanceOf(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance_paise FROM users WHERE id=?")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static long insertTxn(Connection c, String type, Long from, Long to, long paise, String note, String category,
                                  boolean flagged, String flagReason, String source, String idem, Long fromBal, Long toBal,
                                  Long origId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO txns(ref,type,from_user,to_user,amount_paise,note,category,flagged,flag_reason,source,idem_key,from_bal,to_bal,orig_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, newRef());
            ps.setString(2, type);
            if (from == null) ps.setNull(3, Types.INTEGER); else ps.setLong(3, from);
            if (to == null) ps.setNull(4, Types.INTEGER); else ps.setLong(4, to);
            ps.setLong(5, paise);
            ps.setString(6, note);
            ps.setString(7, category);
            ps.setInt(8, flagged ? 1 : 0);
            ps.setString(9, flagReason);
            ps.setString(10, source);
            if (idem == null) ps.setNull(11, Types.VARCHAR); else ps.setString(11, idem);
            if (fromBal == null) ps.setNull(12, Types.INTEGER); else ps.setLong(12, fromBal);
            if (toBal == null) ps.setNull(13, Types.INTEGER); else ps.setLong(13, toBal);
            if (origId == null) ps.setNull(14, Types.INTEGER); else ps.setLong(14, origId);
            ps.setLong(15, now);
            ps.executeUpdate();
            ResultSet k = ps.getGeneratedKeys();
            k.next();
            return k.getLong(1);
        }
    }

    static long welcome(Connection c, long userId, long now) throws SQLException {
        adjust(c, userId, WELCOME_BONUS);
        return insertTxn(c, "ADD_MONEY", null, userId, WELCOME_BONUS, "Welcome bonus (simulated money)", "others", false, "", "system", null, null, WELCOME_BONUS, null, now);
    }

    static Map<String, Object> addMoney(Connection c, User u, long paise) throws SQLException {
        long bal = balanceOf(c, u.id());
        if (bal + paise > BALANCE_CAP) throw new ApiException(400, "Simulated wallet balance is capped at ₹" + money(BALANCE_CAP).stripTrailingZeros().toPlainString());
        long now = System.currentTimeMillis();
        adjust(c, u.id(), paise);
        long id = insertTxn(c, "ADD_MONEY", null, u.id(), paise, "Added simulated money", "others", false, "", "manual", null, null, bal + paise, null, now);
        return Json.obj("txnId", id, "balance", money(bal + paise));
    }

    static Map<String, Object> withdraw(Connection c, User u, long paise) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET balance_paise = balance_paise - ? WHERE id=? AND balance_paise >= ?")) {
            ps.setLong(1, paise);
            ps.setLong(2, u.id());
            ps.setLong(3, paise);
            if (ps.executeUpdate() != 1) throw new ApiException(400, "Insufficient balance");
        }
        long bal = balanceOf(c, u.id());
        long id = insertTxn(c, "WITHDRAW", u.id(), null, paise, "Withdrawal to bank (simulated)", "others", false, "", "manual", null, bal, null, null, now);
        return Json.obj("txnId", id, "balance", money(bal));
    }

    /** Atomic payment: debit (guarded by balance >= amount), credit and ledger row succeed or fail together. */
    static Map<String, Object> transfer(Connection c, User from, User to, long paise, String note, String category, String source, String idemKey) throws SQLException {
        if (from.id() == to.id()) throw new ApiException(400, "You cannot pay yourself");
        if (paise <= 0 || paise > MAX_PAYMENT) throw new ApiException(400, "Invalid amount");
        long now = System.currentTimeMillis();

        if (idemKey != null) { // a double-click or retry returns the original result instead of paying twice
            try (PreparedStatement ps = c.prepareStatement("SELECT id,ref,amount_paise,from_bal FROM txns WHERE from_user=? AND idem_key=?")) {
                ps.setLong(1, from.id());
                ps.setString(2, idemKey);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return Json.obj("txnId", rs.getLong(1), "ref", rs.getString(2), "amount", money(rs.getLong(3)),
                        "balance", money(rs.getLong(4)), "payee", to.name(), "duplicate", true, "flags", List.of(), "undoMsLeft", 0);
            }
        }

        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount_paise),0) FROM txns WHERE from_user=? AND type='TRANSFER' AND status='SUCCESS' AND created_at>=?")) {
            ps.setLong(1, from.id());
            ps.setLong(2, dayStart(now));
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getLong(1) + paise > DAILY_LIMIT) throw new ApiException(403, "Daily sending limit of ₹1,00,000 reached");
        }

        List<String> flags = fraudCheck(c, from.id(), to.id(), paise, now);

        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET balance_paise = balance_paise - ? WHERE id=? AND balance_paise >= ?")) {
            ps.setLong(1, paise);
            ps.setLong(2, from.id());
            ps.setLong(3, paise);
            if (ps.executeUpdate() != 1) throw new ApiException(400, "Insufficient balance");
        }
        adjust(c, to.id(), paise);
        long fromBal = balanceOf(c, from.id()), toBal = balanceOf(c, to.id());
        String cat = category != null && CATEGORIES.contains(category) ? category : categorize(note);
        long id = insertTxn(c, "TRANSFER", from.id(), to.id(), paise, note, cat, !flags.isEmpty(), String.join(" ", flags), source, idemKey, fromBal, toBal, null, now);

        Map<String, Object> r = Json.obj("txnId", id, "amount", money(paise), "payee", to.name(), "balance", money(fromBal),
                "category", cat, "flags", flags, "undoMsLeft", UNDO_WINDOW_MS);
        try (PreparedStatement ps = c.prepareStatement("SELECT ref FROM txns WHERE id=?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            rs.next();
            r.put("ref", rs.getString(1));
        }
        BudgetStatus b = budget(c, from, now);
        if (b.limit > 0 && b.spent * 100 >= b.limit * 80) r.put("budgetWarning", b.spent >= b.limit ? "You have crossed your monthly budget." : "You have used over 80% of your monthly budget.");
        return r;
    }

    /** Reverse a payment within 30 seconds, if the receiver still has the money. */
    static Map<String, Object> undo(Connection c, User me, long txnId) throws SQLException {
        long now = System.currentTimeMillis();
        long to, paise, created;
        String status, category, type;
        try (PreparedStatement ps = c.prepareStatement("SELECT to_user,amount_paise,created_at,status,category,type FROM txns WHERE id=? AND from_user=?")) {
            ps.setLong(1, txnId);
            ps.setLong(2, me.id());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new ApiException(404, "Payment not found");
            to = rs.getLong(1); paise = rs.getLong(2); created = rs.getLong(3); status = rs.getString(4); category = rs.getString(5); type = rs.getString(6);
        }
        if (!type.equals("TRANSFER")) throw new ApiException(400, "Only payments can be undone");
        if (!status.equals("SUCCESS")) throw new ApiException(409, "This payment was already reversed");
        if (now - created > UNDO_WINDOW_MS) throw new ApiException(409, "The 30-second undo window has expired");
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET balance_paise = balance_paise - ? WHERE id=? AND balance_paise >= ?")) {
            ps.setLong(1, paise);
            ps.setLong(2, to);
            ps.setLong(3, paise);
            if (ps.executeUpdate() != 1) throw new ApiException(409, "Cannot undo: the receiver has already spent this money");
        }
        adjust(c, me.id(), paise);
        try (PreparedStatement ps = c.prepareStatement("UPDATE txns SET status='REVERSED', reversed_at=? WHERE id=? AND status='SUCCESS'")) {
            ps.setLong(1, now);
            ps.setLong(2, txnId);
            if (ps.executeUpdate() != 1) throw new ApiException(409, "This payment was already reversed");
        }
        long myBal = balanceOf(c, me.id()), theirBal = balanceOf(c, to);
        insertTxn(c, "REVERSAL", to, me.id(), paise, "Reversal of payment #" + txnId, category, false, "", "undo", null, theirBal, myBal, txnId, now);
        return Json.obj("balance", money(myBal));
    }

    // ------------------------------------------------------------------ history

    private static final String TXN_SELECT =
            "SELECT t.id,t.ref,t.type,t.from_user,t.to_user,t.amount_paise,t.note,t.category,t.status,t.flagged,t.flag_reason,t.source,t.created_at,t.from_bal,t.to_bal," +
            "fu.name,fu.upi_id,tu.name,tu.upi_id FROM txns t LEFT JOIN users fu ON fu.id=t.from_user LEFT JOIN users tu ON tu.id=t.to_user ";

    static Map<String, Object> txnView(ResultSet rs, long me, long now) throws SQLException {
        boolean debit = rs.getLong(4) == me && rs.getObject(4) != null;
        String type = rs.getString(3);
        String counterparty, upi;
        if (type.equals("ADD_MONEY")) { counterparty = "Wallet top-up"; upi = ""; }
        else if (type.equals("WITHDRAW")) { counterparty = "Withdrawal to bank"; upi = ""; }
        else if (debit) { counterparty = rs.getString(18); upi = rs.getString(19); }
        else { counterparty = rs.getString(16); upi = rs.getString(17); }
        long created = rs.getLong(13);
        boolean canUndo = type.equals("TRANSFER") && debit && rs.getString(9).equals("SUCCESS") && now - created <= UNDO_WINDOW_MS;
        Object balAfter = debit ? rs.getObject(14) : rs.getObject(15);
        Map<String, Object> m = Json.obj("id", rs.getLong(1), "ref", rs.getString(2), "type", type, "direction", debit ? "DEBIT" : "CREDIT",
                "amount", money(rs.getLong(6)), "note", rs.getString(7), "category", rs.getString(8), "status", rs.getString(9),
                "flagged", rs.getInt(10) == 1, "flagReason", rs.getString(11), "source", rs.getString(12), "time", created,
                "counterparty", counterparty == null ? "" : counterparty, "counterpartyUpi", upi == null ? "" : upi,
                "balanceAfter", balAfter == null ? null : money(((Number) balAfter).longValue()), "undoMsLeft", canUndo ? UNDO_WINDOW_MS - (now - created) : 0);
        return m;
    }

    static List<Map<String, Object>> history(Connection c, long me, String filter, int limit, Long fromMs, Long toMs) throws SQLException {
        StringBuilder sql = new StringBuilder(TXN_SELECT + "WHERE (t.from_user=? OR t.to_user=?) ");
        switch (filter) {
            case "credit": sql.append("AND t.to_user=? AND (t.from_user IS NULL OR t.from_user<>?) "); break;
            case "debit": sql.append("AND t.from_user=? AND (t.to_user IS NULL OR t.to_user<>?) "); break;
            case "flagged": sql.append("AND t.flagged=1 AND t.from_user=? "); break;
            default: break;
        }
        if (fromMs != null) sql.append("AND t.created_at>=").append(fromMs.longValue()).append(' ');
        if (toMs != null) sql.append("AND t.created_at<").append(toMs.longValue()).append(' ');
        sql.append("ORDER BY t.id DESC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int k = 1;
            ps.setLong(k++, me);
            ps.setLong(k++, me);
            if (filter.equals("credit") || filter.equals("debit")) { ps.setLong(k++, me); ps.setLong(k++, me); }
            if (filter.equals("flagged")) ps.setLong(k++, me);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(txnView(rs, me, now));
        }
        return out;
    }

    // ------------------------------------------------------------------ analytics

    static final class BudgetStatus { long limit, spent; }

    /** Money the user has sent this month (reversed payments excluded). */
    static long spentBetween(Connection c, long userId, long from, long to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount_paise),0) FROM txns WHERE from_user=? AND type='TRANSFER' AND status='SUCCESS' AND created_at>=? AND created_at<?")) {
            ps.setLong(1, userId);
            ps.setLong(2, from);
            ps.setLong(3, to);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    static BudgetStatus budget(Connection c, User u, long now) throws SQLException {
        YearMonth ym = YearMonth.from(Instant.ofEpochMilli(now).atZone(IST));
        BudgetStatus b = new BudgetStatus();
        b.limit = user(c, u.id()).monthlyBudget();
        b.spent = spentBetween(c, u.id(), monthStart(ym), monthStart(ym.plusMonths(1)));
        return b;
    }

    static Map<String, Object> analytics(Connection c, User me) throws SQLException {
        long now = System.currentTimeMillis();
        YearMonth ym = YearMonth.from(Instant.ofEpochMilli(now).atZone(IST));
        long from = monthStart(ym), to = monthStart(ym.plusMonths(1));
        List<Map<String, Object>> byCat = new ArrayList<>(), topPayees = new ArrayList<>(), months = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT category, SUM(amount_paise) s FROM txns WHERE from_user=? AND type='TRANSFER' AND status='SUCCESS' AND created_at>=? AND created_at<? GROUP BY category ORDER BY s DESC")) {
            ps.setLong(1, me.id()); ps.setLong(2, from); ps.setLong(3, to);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) byCat.add(Json.obj("category", rs.getString(1), "amount", money(rs.getLong(2))));
        }
try (PreparedStatement ps = c.prepareStatement(
    "SELECT u.name, SUM(t.amount_paise) s, COUNT(*) n " +
    "FROM txns t JOIN users u ON u.id=t.to_user " +
    "WHERE t.from_user=? AND t.type='TRANSFER' " +
    "AND t.status='SUCCESS' AND t.created_at>=? AND t.created_at<? " +
    "GROUP BY t.to_user, u.name ORDER BY s DESC LIMIT 5"
)) {ps.setLong(1, me.id()); ps.setLong(2, from); ps.setLong(3, to);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) topPayees.add(Json.obj("name", rs.getString(1), "amount", money(rs.getLong(2)), "count", rs.getInt(3)));
        }
        for (int i = 5; i >= 0; i--) {
            YearMonth m = ym.minusMonths(i);
            months.add(Json.obj("month", m.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + m.getYear() % 100,
                    "spent", money(spentBetween(c, me.id(), monthStart(m), monthStart(m.plusMonths(1))))));
        }
        long received;
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount_paise),0) FROM txns WHERE to_user=? AND type='TRANSFER' AND status='SUCCESS' AND created_at>=? AND created_at<?")) {
            ps.setLong(1, me.id()); ps.setLong(2, from); ps.setLong(3, to);
            ResultSet rs = ps.executeQuery();
            rs.next();
            received = rs.getLong(1);
        }
        long flagged;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM txns WHERE from_user=? AND flagged=1 AND created_at>=? AND created_at<?")) {
            ps.setLong(1, me.id()); ps.setLong(2, from); ps.setLong(3, to);
            ResultSet rs = ps.executeQuery();
            rs.next();
            flagged = rs.getLong(1);
        }
        BudgetStatus b = budget(c, me, now);
        int pct = b.limit > 0 ? (int) Math.min(999, b.spent * 100 / b.limit) : 0;
        String level = b.limit == 0 ? "none" : pct >= 100 ? "over" : pct >= 80 ? "warn" : "ok";
        return Json.obj("month", ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear(),
                "spent", money(b.spent), "received", money(received), "byCategory", byCat, "topPayees", topPayees, "monthly", months,
                "flaggedCount", flagged, "budget", Json.obj("limit", money(b.limit), "used", money(b.spent), "percent", pct, "level", level));
    }
}
