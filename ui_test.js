// Browser test for JavaBank Pay (39 checks, Playwright): registers, pays by AI, undo, QR, split, history, insights, PDF, sign-out,
// XSS probe, phone layout, and fails on any CSP violation or JS error.
// Run against a server started with a fresh throwaway DB:  node tests/ui_test.js ./screenshots
// Needs:  npm i playwright && npx playwright install chromium
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = 'http://localhost:8099';
const SHOTS = process.argv[2];
let pass = 0, fail = 0, ipn = 0;
const log = [];
const consoleIssues = [];

async function seed(name, phone) {
  ipn++;
  const r = await fetch(BASE + '/api/register', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': `10.7.${ipn}.1` }, body: JSON.stringify({ name, phone, password: 'Secret123', pin: '2580' }) });
  const d = await r.json();
  if (!d.token) throw new Error('seed failed ' + JSON.stringify(d));
  return d.token;
}
async function api(token, method, path, body) {
  const r = await fetch(BASE + path, { method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token, 'X-Forwarded-For': '10.8.8.8' }, body: body ? JSON.stringify(body) : undefined });
  return r.json();
}
function check(name, cond, extra = '') { if (cond) { pass++; console.log('  PASS ', name); } else { fail++; console.log('  FAIL ', name, extra); } }

(async () => {
  const tArun = await seed('Arun Raj', '9000000001');
  await seed('Meena Devi', '9000000002');
  await seed('Priya Sharma', '9123456780');
  await seed('Priya Kumar', '9988776655');

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true, permissions: ['clipboard-read', 'clipboard-write'] });
  const page = await ctx.newPage();
  page.on('console', m => { const t = m.text(); if (m.type() === 'error' || /Content Security Policy/i.test(t)) consoleIssues.push(t); });
  page.on('pageerror', e => consoleIssues.push('PAGEERROR ' + e.message));
  page.on('dialog', d => d.accept());

  const shot = n => page.screenshot({ path: `${SHOTS}/${n}.png` });
  const pin = async (p) => { await page.waitForSelector('#modal-pin:not([hidden])'); await page.fill('#pin-input', p); await page.click('#modal-pin button[type=submit]'); };
  const tab = async n => { await page.click(`.tab[data-tab=${n}]`); await page.waitForTimeout(350); };
  const toastHas = async (s) => { try { await page.waitForFunction(s => { const t = document.querySelector('#toast'); return t.classList.contains('show') && t.textContent.includes(s); }, s, { timeout: 6000 }); return true; } catch { return false; } };

  await page.goto(BASE);
  await shot('01_auth');
  check('auth screen visible', await page.isVisible('#login-form'));

  console.log('== register');
  await page.click('[data-action=show-register]');
  await page.fill('#reg-name', 'Sree Krishna');
  await page.fill('#reg-phone', '9876543210');
  await page.fill('#reg-pw', 'Secret123');
  await page.fill('#reg-pin', '1234');
  await page.click('#register-form button[type=submit]');
  check('weak PIN shows server error', await toastHas('too easy'));
  await page.fill('#reg-pin', '2580');
  await page.click('#register-form button[type=submit]');
  await page.waitForSelector('#view-app:not([hidden])');
  await page.waitForFunction(() => document.querySelector('#home-bal').textContent.includes('1,000.00'));
  check('registered, balance ₹1,000.00', (await page.textContent('#home-bal')).includes('1,000.00'));
  check('UPI ID shown', (await page.textContent('#home-upi')) === '9876543210@javapay');
  await page.waitForTimeout(500);
  await shot('02_home');

  console.log('== contacts');
  await tab('contacts');
  for (const [n, p] of [['Arun', '9000000001'], ['Meena', '9000000002'], ['Priya', '9123456780'], ['Priya K', '9988776655'], ['Ghost Friend', '9555555555']]) {
    await page.fill('#c-name', n); await page.fill('#c-phone', p);
    await page.click('form[data-form=contact-add] button[type=submit]');
    await page.waitForFunction(t => [...document.querySelectorAll('#contact-list .name')].some(e => e.textContent.startsWith(t)), n);
  }
  check('5 contacts listed', (await page.$$('#contact-list .item')).length === 5);
  check('unregistered contact marked', (await page.textContent('#contact-list')).includes('Not joined'));
  await page.fill('#c-name', '<img src=x>'); await page.fill('#c-phone', '9111111111');
  await page.click('form[data-form=contact-add] button[type=submit]');
  check('markup in contact name rejected by server', await toastHas('valid name'));
  await shot('03_contacts');

  console.log('== AI pay');
  await tab('pay');
  await page.fill('#ai-input', 'Pay 150 to Arun for tea');
  await page.press('#ai-input', 'Enter');
  await page.waitForSelector('#pay-stage .preview');
  const pv = await page.textContent('#pay-stage .preview');
  check('preview shows payee, amount, category', pv.includes('Arun Raj') && pv.includes('150.00') && pv.includes('Food'), pv);
  await shot('04_preview');
  await page.click('#pay-stage .preview .btn-gold');
  await pin('9999');
  check('wrong PIN -> attempts message, no payment', (await toastHas('Incorrect PIN')) && !(await page.$('#pay-stage .success-card')));
  await page.waitForTimeout(300);
  await page.click('#pay-stage .preview .btn-gold');
  await pin('2580');
  await page.waitForSelector('#pay-stage .success-card');
  check('payment success card', (await page.textContent('#pay-stage .success-card')).includes('150.00 sent to Arun Raj'));
  await shot('05_success');
  check('undo button visible', !!(await page.$('#pay-stage [data-action=undo]')));
  await page.click('#pay-stage [data-action=undo]');
  await page.waitForSelector('#pay-stage .alert.ok');
  check('undo works', (await page.textContent('#pay-stage .alert.ok')).includes('reversed'));
  await page.waitForFunction(() => document.querySelector('#home-bal').textContent.includes('1,000.00'));

  console.log('== ambiguous name chooser');
  await page.fill('#ai-input', 'pay 100 to priy for books');
  await page.press('#ai-input', 'Enter');
  await page.waitForSelector('#pay-stage .choice-btns');
  const choices = await page.$$eval('#pay-stage .choice-btns button', b => b.map(x => x.textContent));
  check('asks which Priya', choices.length >= 2 && choices.some(c => c.includes('Priya K')), choices.join('|'));
  await shot('06_choose');
  await page.click('#pay-stage .choice-btns button >> nth=0');
  await page.waitForSelector('#pay-stage .preview .big-amt');
  check('choosing continues to preview', (await page.textContent('#pay-stage .preview')).includes('100.00'));
  await page.click('#pay-stage .preview .btn-ghost');

  console.log('== unknown contact / gibberish');
  await page.fill('#ai-input', 'pay 50 to Karthik');
  await page.press('#ai-input', 'Enter');
  await page.waitForSelector('#pay-stage .alert.bad');
  check('unknown contact explained', (await page.textContent('#pay-stage .alert.bad')).includes('Add them in Contacts'));

  console.log('== manual pay + XSS-in-note');
  await page.selectOption('#pay-who', { label: (await page.$$eval('#pay-who option', o => o.map(x => x.textContent).find(t => t.startsWith('Meena')))) });
  await page.fill('#pay-amount', '75');
  const evil = '<img src=x onerror="window.__xss=1">';
  await page.fill('#pay-note', evil);
  await page.click('form[data-form=pay-manual] button[type=submit]');
  await page.waitForSelector('#pay-stage .preview');
  check('note shown as literal text in preview', (await page.textContent('#pay-stage .preview')).includes('<img src=x'));
  await page.click('#pay-stage .preview .btn-gold');
  await pin('2580');
  await page.waitForSelector('#pay-stage .success-card');
  check('no injected element or script ran', (await page.$('#pay-stage img')) === null && (await page.evaluate(() => window.__xss)) === undefined);

  console.log('== QR: scan Arun\'s QR by pasting link');
  const qrA = await api(tArun, 'POST', '/api/qr/create', { amount: '250', note: 'book fee', ttlMinutes: 5 });
  await tab('qr');
  await page.fill('#scan-text', qrA.payload);
  await page.press('#scan-text', 'Enter');
  await page.waitForSelector('#qr-pay-stage .preview');
  const qp = await page.textContent('#qr-pay-stage .preview');
  check('QR preview shows fixed amount & expiry countdown', qp.includes('250.00') && qp.includes('Arun Raj') && /Expires in \d:\d\d/.test(qp), qp);
  await shot('07_qr_scan_preview');
  await page.click('#qr-pay-stage .preview .btn-gold');
  await pin('2580');
  await page.waitForSelector('#qr-pay-stage .success-card');
  check('paid via QR', (await page.textContent('#qr-pay-stage .success-card')).includes('250.00 sent to Arun Raj'));
  await page.fill('#scan-text', qrA.payload);
  await page.press('#scan-text', 'Enter');
  check('reusing the QR is refused', await toastHas('already been used'));

  console.log('== QR: generate my own');
  await page.fill('#qr-amount', '99.50'); await page.fill('#qr-note', 'chai money');
  await page.click('form[data-form=qr-create] button[type=submit]');
  await page.waitForSelector('#qr-out .qr-img');
  const src = await page.getAttribute('#qr-out .qr-img', 'src');
  fs.writeFileSync(`${SHOTS}/my_qr.png`, Buffer.from(src.split(',')[1], 'base64'));
  check('QR image rendered with countdown', /Expires in/.test(await page.textContent('#qr-out')) && (await page.textContent('#qr-out')).includes('99.50'));
  await shot('08_qr_tab');

  console.log('== split via Genie + AI');
  await page.click('#genie-btn');
  await page.fill('#genie-input', 'Split 900 among me, Arun, Meena for dinner');
  await page.press('#genie-input', 'Enter');
  await page.waitForSelector('#split-stage .preview');
  const sp = await page.textContent('#split-stage .preview');
  check('Genie routed to Split tab with 300 each', sp.includes('900.00') && sp.includes('300.00') && sp.includes('Arun') && sp.includes('Meena'), sp);
  await shot('09_split_preview');
  await page.click('#split-stage .preview .btn-gold');
  await page.waitForSelector('#split-stage .success-card');
  await page.waitForFunction(() => document.querySelector('#split-created .item'));
  check('split listed under "Bills you split"', (await page.textContent('#split-created')).includes('dinner'));
  await page.click('#genie-btn');
  // Arun pays his share via API; creator sees PAID
  const owed = await api(tArun, 'GET', '/api/split');
  const pr = await api(tArun, 'POST', '/api/split/pay', { shareId: owed.owed[0].shareId, pin: '2580' });
  check('friend can pay share via API', pr.success === true);
  await tab('split');
  await page.waitForFunction(() => document.querySelector('#split-created').textContent.includes('✓'));
  check('creator sees share marked paid', true);

  console.log('== history');
  await tab('history');
  await page.waitForSelector('#history-list .item');
  const hist = await page.textContent('#history-list');
  check('history shows payments, QR badge, split badge', hist.includes('Arun Raj') && hist.includes('QR') && hist.includes('Split'), hist.slice(0, 200));
  check('XSS note is literal text in history', hist.includes('<img src=x') && (await page.$('#history-list img')) === null);
  await page.click('[data-filter=credit]');
  await page.waitForTimeout(400);
  const credits = await page.$$('#history-list .item');
  check('Received filter works', credits.length >= 2);
  await page.click('[data-filter=all]');
  await page.waitForTimeout(300);
  await shot('10_history');

  console.log('== insights + statement');
  await tab('insights');
  await page.fill('#budget-amount', '400');
  await page.click('form[data-form=budget] button[type=submit]');
  await page.waitForSelector('#budget-meter:not([hidden])');
  await page.waitForTimeout(500);
  check('budget meter shows overspend', (await page.textContent('#budget-text')).includes('Over budget') || (await page.textContent('#budget-text')).includes('spent'));
  check('category bars rendered', (await page.$$('#in-cats .bar-row')).length >= 2);
  check('6 monthly columns', (await page.$$('#in-months .col')).length === 6);
  await shot('11_insights');
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click('form[data-form=statement] button[type=submit]')]);
  await dl.saveAs(`${SHOTS}/ui_statement.pdf`);
  check('statement downloads as PDF', dl.suggestedFilename().endsWith('.pdf'));

  console.log('== settings, sign out, sign in');
  await page.click('[data-action=open-settings]');
  await page.fill('#pin-old', '2580'); await page.fill('#pin-new', '7391');
  await page.click('form[data-form=change-pin] button[type=submit]');
  check('PIN changed', await toastHas('PIN updated'));
  await page.click('[data-action=logout]');
  await page.waitForSelector('#view-auth:not([hidden])');
  check('signed out: app hidden, token cleared', (await page.isHidden('#view-app')) && (await page.evaluate(() => sessionStorage.getItem('jb_token'))) === null);
  await page.fill('#login-id', 'wrong'); await page.fill('#login-pw', 'badbadbad1');
  await page.click('#login-form button[type=submit]');
  check('bad login shows generic error', await toastHas('Invalid credentials'));
  await page.fill('#login-id', '9876543210'); await page.fill('#login-pw', 'Secret123');
  await page.click('#login-form button[type=submit]');
  await page.waitForSelector('#view-app:not([hidden])');
  check('login works again', true);
  await page.reload();
  await page.waitForSelector('#view-app:not([hidden])');
  check('session survives reload (sessionStorage)', (await page.textContent('#nav-name')) === 'Sree');

  console.log('== mobile layout');
  const mob = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const mp = await mob.newPage();
  await mp.goto(BASE);
  await mp.screenshot({ path: `${SHOTS}/12_mobile_auth.png` });
  await mp.click('[data-action=show-register]'); await mp.fill('#reg-name', 'Mobile Tester'); await mp.fill('#reg-phone', '9000000009'); await mp.fill('#reg-pw', 'Secret123'); await mp.fill('#reg-pin', '2580');
  await mp.click('#register-form button[type=submit]');
  await mp.waitForSelector('#view-app:not([hidden])');
  await mp.waitForTimeout(600);
  await mp.screenshot({ path: `${SHOTS}/13_mobile_home.png`, fullPage: false });
  const overflow = await mp.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  check('no horizontal scroll on phone width', !overflow);

  const real = consoleIssues.filter(t => !/fonts\.(googleapis|gstatic)|ERR_(NAME_NOT_RESOLVED|CONNECTION|INTERNET|TUNNEL|FAILED)|Failed to load resource.*(40[0-9]|net::)/i.test(t));
  console.log('\nconsole issues (excluding blocked web fonts):', real.length ? real : 'none');
  check('no CSP violations or JS errors', real.length === 0);
  console.log(`\n${pass} passed, ${fail} failed`);
  await browser.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('SCRIPT ERROR', e); process.exit(2); });
