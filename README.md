# JavaBank Pay

A secure **UPI payment simulator** with an AI payment assistant, contact-name resolution, time-limited QR codes, bill splitting and spending insights. All money is fake: no bank, no real UPI network.

🌐 Live demo: https://javabank-tfsn.onrender.com

## What you can do

| Feature | How it works |
|---|---|
| **Pay by typing** | "Pay 300 to Priya for lunch" is turned into a payment *preview*. You confirm with your UPI PIN. |
| **Names, not numbers** | Contacts are `(name, mobile)`. The mobile links to a registered account (`mobile@javapay`). Typos and partial names are fuzzy-matched; two Priyas means it asks which one. |
| **QR codes** | Generate a code with an optional amount and a 5 min / 15 min / 1 hour expiry. Amount codes work once. Scan with the camera, upload an image, or paste the link. |
| **Split bills** | "Split 900 among me, Arun, Meena for dinner". Friends get a request and pay their share with their own PIN. |
| **Undo** | 30 seconds to reverse a payment, if the receiver hasn't spent it. |
| **Fraud flags** | Unusually large payment (>5x your average), 3rd payment to one person in 5 minutes, night-time payments, large first payment. Flagged payments show a warning first. |
| **Insights** | Auto-categorised spending (food, travel, rent, bills…), monthly budget with 80% / 100% alerts, 6-month chart, top payees, PDF statement. |

## Architecture

```
Browser (web/: HTML + CSS + vanilla JS)  ──JSON over HTTPS──▶  Java server (src/, JDK HttpServer)  ──▶  SQLite
                                                                   └── optional: Claude API (parses the typed sentence only)
```

No framework and no build step: only the JDK plus the SQLite driver already in this repo.

| File | Role |
|---|---|
| `src/Main.java` | HTTP server, routing, auth, all endpoints |
| `src/Wallet.java` | Money logic: atomic transfers, undo, fraud rules, contact matching, analytics |
| `src/Security.java` | PBKDF2 hashing, JWT (HS256) sessions, rate limiting |
| `src/Db.java` | SQLite schema and transactions |
| `src/Ai.java` | Sentence → payment suggestion (Claude API, or rule-based fallback) |
| `src/Qr.java` | QR encoder written from scratch (no library) |
| `src/Pdf.java` | PDF statement writer (no library) |
| `src/Json.java` | Small JSON reader/writer |
| `web/` | The single-page app |
| `tests/` | API suite (115 checks) and browser suite (39 checks) |

## Run locally

Requires Java 17+.

```bash
mkdir -p out
javac -d out -cp "sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar" src/*.java
java -cp "out:sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar" Main
# Windows: use ; instead of : in the classpath
```

Open http://localhost:8080, create an account (you get ₹1,000 of simulated money), and create a second account in another browser profile to pay between users.

| Environment variable | Purpose | Default |
|---|---|---|
| `PORT` | HTTP port | `8080` |
| `DB_PATH` | SQLite file | `upi.db` |
| `APP_SECRET` | Signs sessions and peppers PIN/password hashes. **Set a long random value in production.** | generated once and stored in the DB |
| `ANTHROPIC_API_KEY` | Use Claude to parse typed payments | unset → rule-based parser |
| `ANTHROPIC_MODEL` | Claude model for parsing | `claude-haiku-5-5` |

## Deploy on Render

`render.yaml` and the `Dockerfile` are ready. Push to GitHub and Render builds it. Render generates `APP_SECRET` for you; add `ANTHROPIC_API_KEY` in the dashboard if you want Claude parsing.

> **Data persistence:** Render's free plan has no persistent disk, so the SQLite file resets on every deploy/restart. That is fine for a demo. For lasting data, attach a Render disk mounted at `/app/data` (paid plan) or move to Postgres.

## Security design

- **Passwords and UPI PINs**: PBKDF2-HMAC-SHA256, 210,000 iterations, per-user salt, plus a server-side pepper (`APP_SECRET`). Never stored or logged in plain text.
- **Sessions**: signed JWT (HS256), 1-hour expiry. Logout and password change revoke every older token. The algorithm is never read from the token, so "alg: none" forgery fails.
- **Every payment needs the UPI PIN**; 5 wrong PINs lock it for 15 minutes. 5 wrong passwords lock the account for 15 minutes. Login/register are also IP rate-limited, and unknown accounts take the same time as real ones (no user enumeration).
- **Money safety**: amounts are stored as integer paise (no float rounding); debit + credit + ledger row happen in one DB transaction; the debit is `balance >= amount` guarded and the column has `CHECK(balance >= 0)`; an idempotency key stops double-click double-pay; per-payment and daily limits apply.
- **QR safety**: for a code with an amount, the payee and amount are read from the server, never from the scanned text, so a tampered QR can't change who or how much you pay. Codes expire and are single-use.
- **AI safety**: the model only *suggests*. The server validates the amount, matches the name against **your own** contacts, and nothing moves without your PIN. Only your typed sentence is sent to the API (not contacts, balance or history).
- **Web hardening**: strict Content-Security-Policy (no inline scripts), `X-Frame-Options: DENY`, `nosniff`, HSTS on HTTPS, `Cache-Control: no-store` on API responses, a 16 KB body limit, and all user text rendered with `textContent` (no XSS).
- **Authorisation**: every query is scoped to the signed-in user (contacts, payments, splits), so IDs from another account are rejected.

## Tests

```bash
# terminal 1: a throwaway database
DB_PATH=/tmp/test.db PORT=8099 java -cp "out:sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar" Main
# terminal 2
python3 tests/api_test.py /tmp/test.db /tmp          # 115 checks, ~2 min
npm i playwright && npx playwright install chromium
node tests/ui_test.js ./screenshots                 # 39 browser checks (use a fresh DB)
```

## Known limits

- Live camera scanning uses the browser's built-in `BarcodeDetector` (Chrome/Edge). Other browsers can upload a QR image or paste the link.
- Only JavaPay users can be paid; there is no real UPI network.
- The simulated top-up is capped and meant for testing.

## Ideas for next steps

Recurring payments and reminders, payment requests ("request money"), Postgres + Flyway migrations, email/SMS OTP simulation, an admin view of flagged payments, and a Spring Boot port of the service layer.
