#!/usr/bin/env node
/**
 * Automated accessibility audit of the ClaimFlow console.
 *
 * What this is: axe-core plus keyboard, focus and accessibility-tree probes in headless Google
 * Chrome, against the packaged API JAR (default profile, in-memory H2) and the production console
 * bundle served by `vite preview`. All claims are synthetic.
 *
 * What this is not: a conformance test. axe-core finds a subset of problems. A clean result does
 * not make the console accessible or WCAG conformant. A manual screen-reader pass by a person
 * (VoiceOver or NVDA) has not been done, and neither has a test with disabled users.
 *
 * The protocol is this file, fixed before the first (before-fix) run:
 *
 * - axe-core 4.13.0 with tags wcag2a, wcag2aa, wcag21a, wcag21aa, wcag22aa and best-practice.
 * - Every state below is scanned at 1280x900 and again at 390x844. At 320x800 the page is checked
 *   for horizontal overflow (a rough automated stand-in for reflow).
 * - States: see the `state(...)` calls, all three roles plus the signed-out page: empty lists,
 *   populated lists, the submit form, every receipt-upload validation error, an upload in progress
 *   (the browser holds the request while the scan runs, then the real API answers), the server's
 *   OCR 503, server 400 and 409 errors, policy review and policy block, decision and payment
 *   notices, stale decision and payment conflicts, policy answers, and the API really stopped.
 * - Not reachable on this API and so not scanned: the `SUBMITTED` badge except by chance (the
 *   worker moves claims within about a second) and the manager's note on an image claim, because
 *   no OCR engine is configured and no image claim can exist.
 * - Reported: unique violations as (rule, element selector) across all scans, by rule and impact,
 *   and as (rule, tag and classes), because axe writes different selectors for one element as the
 *   page changes; scans with at least one violation out of all scans; rules that axe could not
 *   decide ("incomplete"), which need a person.
 * - Keyboard: a counted Tab cycle on the signed-out page and on each role's populated view (stops,
 *   empty accessible names, stops without a visible focus style, positive tabindex, and whether
 *   Tab gets back to the first stop); a whole employee-manager-finance-employee flow with no mouse,
 *   counting keys; opening the file chooser with the keyboard; focus retained across the
 *   10-second poll while typing.
 * - Focus and announcements, recorded at every state: where focus is, the page title, whether a
 *   message appeared in a live region that already existed (assistive technology often misses a
 *   message inserted together with its region), and each form control's invalid state and
 *   description as Chrome's accessibility tree reports them.
 *
 * Usage, from frontend/: node a11y/audit.mjs --label before
 * Needs target/claimflow-0.1.0.jar, Google Chrome and free ports 8080 and 5173.
 * Writes ../evidence/a11y/audit_<label>_<date>.json. Exits 0 when the audit ran to the end,
 * whatever it found; exits 2 if a state could not be reached.
 */
import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND = resolve(HERE, '..');
const ROOT = resolve(FRONTEND, '..');
const API = 'http://127.0.0.1:8080';
const CONSOLE = 'http://127.0.0.1:5173';
const PASSWORD = 'local-only';
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];
const WCAG_TAGS = new Set(TAGS.filter(tag => tag !== 'best-practice'));
const AXE = readFileSync(join(FRONTEND, 'node_modules', 'axe-core', 'axe.min.js'), 'utf8');
const AXE_VERSION = JSON.parse(readFileSync(join(FRONTEND, 'node_modules', 'axe-core', 'package.json'), 'utf8')).version;
const WIDE = { width: 1280, height: 900 };
const NARROW = { width: 390, height: 844 };
const REFLOW = { width: 320, height: 800 };
const MAX_TABS = 150;
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d]);

const label = process.argv[process.argv.indexOf('--label') + 1];
if (!process.argv.includes('--label') || !label) { console.error('usage: audit.mjs --label NAME'); process.exit(64); }
const outDir = process.argv.includes('--out') ? resolve(process.argv[process.argv.indexOf('--out') + 1]) : join(ROOT, 'evidence', 'a11y');

const report = {
  label, startedAt: new Date().toISOString(), tool: { axeCore: AXE_VERSION, tags: TAGS }, viewports: { WIDE, NARROW, REFLOW },
  api: 'target/claimflow-0.1.0.jar, default profile, in-memory H2', console: 'production bundle via vite preview',
  scans: [], unreached: [], keyboard: {},
  limits: 'Automated checks only. Not a conformance claim. No manual screen-reader pass has been done.',
};

/* Runs in the page from before first paint: records how every message reaches a live region. */
function liveRegionRecorder() {
  window.__liveEvents = [];
  const LIVE = '[role="alert"],[role="status"],[aria-live]:not([aria-live="off"])';
  const describe = (region, preexisted) => {
    const text = (region.textContent || '').trim();
    if (text) window.__liveEvents.push({ role: region.getAttribute('role') || region.getAttribute('aria-live'), text: text.slice(0, 160), regionPreexisted: preexisted });
  };
  new MutationObserver(records => {
    const seen = new Set();
    for (const record of records) {
      const target = record.target.nodeType === 1 ? record.target : record.target.parentElement;
      const container = target && target.closest ? target.closest(LIVE) : null;
      if (container && !seen.has(container)) { seen.add(container); describe(container, true); }
      for (const node of record.addedNodes) {
        if (node.nodeType !== 1) continue;
        const regions = [...(node.matches(LIVE) ? [node] : []), ...node.querySelectorAll(LIVE)];
        for (const region of regions) if (!seen.has(region)) { seen.add(region); describe(region, false); }
      }
    }
  }).observe(document, { subtree: true, childList: true, characterData: true });
}

const focusInfo = () => {
  const e = document.activeElement;
  if (!e || e === document.body || e === document.documentElement) return { onBody: true };
  const label = e.labels && e.labels[0] ? e.labels[0].innerText.split('\n')[0] : null;
  return { onBody: false, tag: e.tagName.toLowerCase(), type: e.getAttribute('type'), id: e.id || null,
    name: e.getAttribute('aria-label') || label || (e.innerText || '').trim().slice(0, 80) || null,
    insideClaim: !!e.closest('article.claim') };
};

async function runAxe(page) {
  if (!(await page.evaluate(() => typeof window.axe !== 'undefined'))) await page.addScriptTag({ content: AXE });
  return page.evaluate(tags => window.axe.run(document, {
    runOnly: { type: 'tag', values: tags }, resultTypes: ['violations', 'incomplete'],
  }).then(r => ({
    passes: r.passes.length, inapplicable: r.inapplicable.length,
    violations: r.violations.map(v => ({ id: v.id, impact: v.impact, tags: v.tags, help: v.help,
      nodes: v.nodes.map(n => ({ target: n.target.join(' '), html: n.html.slice(0, 200), summary: (n.failureSummary || '').slice(0, 400) })) })),
    incomplete: r.incomplete.map(v => ({ id: v.id, impact: v.impact, nodes: v.nodes.map(n => n.target.join(' ')) })),
  })), TAGS);
}

const KEPT_ROLES = new Set(['banner', 'main', 'contentinfo', 'complementary', 'navigation', 'form', 'region', 'heading',
  'button', 'textbox', 'combobox', 'spinbutton', 'progressbar', 'alert', 'status', 'article', 'list', 'listitem', 'link', 'group']);

async function axTree(cdp) {
  const { nodes } = await cdp.send('Accessibility.getFullAXTree');
  const out = [];
  for (const node of nodes) {
    if (node.ignored) continue;
    const role = node.role && node.role.value;
    if (!KEPT_ROLES.has(role)) continue;
    const props = Object.fromEntries((node.properties || []).map(p => [p.name, p.value && p.value.value]));
    const row = { role, name: ((node.name && node.name.value) || '').trim().slice(0, 120) };
    if (node.description && node.description.value) row.description = node.description.value.slice(0, 200);
    for (const key of ['level', 'invalid', 'required', 'disabled', 'live', 'focused']) if (props[key] !== undefined && props[key] !== false && props[key] !== 'false') row[key] = props[key];
    out.push(row);
  }
  return out;
}

class Audit {
  constructor(page, cdp) { this.page = page; this.cdp = cdp; }

  /** Brings the page to one state and scans it. A state that fails is recorded, not skipped silently. */
  async state(id, description, drive) {
    try {
      if (drive) await drive();
      await this.scan(id, description);
      console.log(`state ${id}: scanned`);
    } catch (error) {
      report.unreached.push({ id, description, error: String(error).slice(0, 400) });
      console.log(`state ${id}: NOT REACHED: ${String(error).slice(0, 200)}`);
    }
  }

  async scan(id, description) {
    const page = this.page;
    const focus = await page.evaluate(focusInfo);
    const live = await page.evaluate(() => { const e = window.__liveEvents || []; window.__liveEvents = []; return e; });
    const title = await page.title();
    const tree = await axTree(this.cdp);
    const wide = await runAxe(page);
    await page.setViewportSize(NARROW);
    const narrow = await runAxe(page);
    await page.setViewportSize(REFLOW);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    await page.setViewportSize(WIDE);
    report.scans.push({ id, description, focus, title, liveEvents: live, reflowOverflowPx320: overflow, accessibilityTree: tree, wide, narrow });
  }
}

const authHeaders = token => ({ 'Content-Type': 'application/json', Authorization: `Bearer ${token}` });

async function token(user) {
  const reply = await fetch(`${API}/api/auth/token`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: user, password: PASSWORD }) });
  return (await reply.json()).access_token;
}

let counter = 0;
const receipt = (merchant, cents) => `Merchant: ${merchant}\nDate: 2026-11-06\nTotal: SGD ${(cents / 100).toFixed(2)}`;

async function seed(employeeToken, merchant, cents, { category = 'meal', asyncInvalid = false } = {}) {
  counter += 1;
  const path = asyncInvalid ? '/api/claims/async' : '/api/claims';
  const body = { category, amountCents: cents, receiptText: asyncInvalid ? `Unreadable receipt ${merchant}` : receipt(merchant, cents) };
  const reply = await fetch(`${API}${path}`, { method: 'POST', headers: { ...authHeaders(employeeToken), 'Idempotency-Key': `audit-${label}-${counter}` }, body: JSON.stringify(body) });
  if (!reply.ok) throw new Error(`seed ${merchant}: ${reply.status} ${await reply.text()}`);
  const claim = await reply.json();
  if (asyncInvalid) {
    const deadline = Date.now() + 20000;
    for (;;) {
      const now = await (await fetch(`${API}/api/claims/${claim.id}`, { headers: authHeaders(employeeToken) })).json();
      if (now.state === 'NEEDS_HUMAN_CHECK') break;
      if (Date.now() > deadline) throw new Error(`seed ${merchant} stuck in ${now.state}`);
      await new Promise(done => setTimeout(done, 200));
    }
  }
  return claim;
}

const post = (path, tokenValue, body) => fetch(`${API}${path}`, { method: 'POST', headers: authHeaders(tokenValue), body: body === undefined ? undefined : JSON.stringify(body) });
const card = (page, merchant) => page.locator('article.claim').filter({ hasText: merchant });

async function signIn(page, role) {
  await page.getByLabel('Role').selectOption(role);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: 'Sign out' }).waitFor({ timeout: 10000 });
}
async function signOut(page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByRole('heading', { name: 'Open local demo' }).waitFor({ timeout: 10000 });
}
async function refreshView(page) { await page.getByRole('button', { name: 'Refresh' }).click(); await page.waitForTimeout(400); }

/* ---- Keyboard probes ---- */

const STOP_INFO = () => {
  const e = document.activeElement;
  if (!e || e === document.body || e === document.documentElement) return { body: true };
  if (!e.dataset.a11yStop) e.dataset.a11yStop = String(document.querySelectorAll('[data-a11y-stop]').length + 1);
  const style = getComputedStyle(e);
  const outlined = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0;
  return { body: false, stop: e.dataset.a11yStop, tag: e.tagName.toLowerCase(), type: e.getAttribute('type'), focusVisibleMatches: e.matches(':focus-visible'),
    focusStyle: outlined || style.boxShadow !== 'none', outline: `${style.outlineStyle} ${style.outlineWidth} ${style.outlineColor}`, tabIndex: e.tabIndex, insideClaim: !!e.closest('article.claim') };
};

async function focusedAxNode(cdp) {
  const handle = await cdp.send('Runtime.evaluate', { expression: 'document.activeElement' });
  const objectId = handle.result && handle.result.objectId;
  if (!objectId) return null;
  const { nodes } = await cdp.send('Accessibility.getPartialAXTree', { objectId, fetchRelatives: false });
  const node = nodes.find(n => !n.ignored) || nodes[0];
  return node ? { role: node.role && node.role.value, name: ((node.name && node.name.value) || '').trim() } : null;
}

async function tabCycle(page, cdp, name) {
  await page.evaluate(() => { document.querySelectorAll('[data-a11y-stop]').forEach(e => e.removeAttribute('data-a11y-stop')); document.activeElement && document.activeElement.blur(); });
  const stops = [];
  let first = null; let returned = false; let presses = 0;
  for (; presses < MAX_TABS; presses += 1) {
    await page.keyboard.press('Tab');
    const info = await page.evaluate(STOP_INFO);
    if (info.body) continue; // past the last stop: the next Tab should wrap round to the first
    if (first && info.stop === first) { returned = true; presses += 1; break; }
    if (!first) first = info.stop;
    const ax = await focusedAxNode(cdp);
    stops.push({ ...info, role: ax && ax.role, name: ax && ax.name });
  }
  const names = stops.map(s => `${s.role}:${s.name}`);
  const repeated = {};
  for (const n of names) repeated[n] = (repeated[n] || 0) + 1;
  return { view: name, stops: stops.length, tabPresses: presses, returnedToFirstStop: returned,
    emptyAccessibleNames: stops.filter(s => !s.name).length,
    noFocusStyle: stops.filter(s => !s.focusStyle).length,
    positiveTabIndex: stops.filter(s => s.tabIndex > 0).length,
    repeatedRoleAndName: Object.fromEntries(Object.entries(repeated).filter(([, count]) => count > 1)),
    stopList: stops.map(s => ({ role: s.role, name: s.name, outline: s.outline, insideClaim: s.insideClaim })) };
}

const FOCUS_NAME = () => {
  const e = document.activeElement;
  if (!e || e === document.body) return { name: '', claim: '' };
  const label = e.getAttribute('aria-label') || (e.labels && e.labels[0] ? e.labels[0].innerText : '') || e.innerText || '';
  const owner = e.closest('article.claim');
  return { name: label.trim(), claim: owner ? owner.innerText : '' };
};

class KeyboardFlow {
  constructor(page) { this.page = page; this.keys = 0; this.typed = 0; this.steps = []; }
  async press(key) { await this.page.keyboard.press(key); this.keys += 1; }
  async type(text) {
    for (const ch of text) { if (ch === '\n') await this.page.keyboard.press('Enter'); else await this.page.keyboard.type(ch); this.typed += 1; }
  }
  /** Presses Tab until the focused control's name matches, optionally inside the claim with this merchant. */
  async tabTo(pattern, merchant, max = 60) {
    for (let i = 0; i < max; i += 1) {
      await this.press('Tab');
      const now = await this.page.evaluate(FOCUS_NAME);
      if (pattern.test(now.name) && (!merchant || now.claim.includes(merchant))) return i + 1;
    }
    throw new Error(`Tab did not reach ${pattern} ${merchant || ''} in ${max} presses`);
  }
  async note(step) { this.steps.push({ step, keysSoFar: this.keys, focus: await this.page.evaluate(focusInfo) }); }
}

async function keyboardFlow(page) {
  const merchant = `Keyboard Cafe ${label}`;
  const flow = new KeyboardFlow(page);
  await page.goto(CONSOLE);
  await flow.tabTo(/^Role/);
  await flow.tabTo(/^Password/);
  await flow.type(PASSWORD);
  await flow.press('Enter');
  await page.getByRole('button', { name: 'Sign out' }).waitFor();
  await flow.note('employee signed in');
  await flow.tabTo(/^Amount/);
  await flow.type('14.25');
  await flow.tabTo(/^Receipt transcription/);
  await flow.type(receipt(merchant, 1425));
  await flow.tabTo(/^Submit claim/);
  await flow.press('Enter');
  await page.getByText('Claim queued for receipt validation.').waitFor();
  await flow.note('claim submitted');
  await card(page, merchant).waitFor({ timeout: 15000 });
  await flow.tabTo(/^Sign out/);
  await flow.press('Enter');
  await page.getByRole('heading', { name: 'Open local demo' }).waitFor();
  await flow.note('employee signed out');

  await flow.tabTo(/^Role/);
  await page.keyboard.type('M'); flow.keys += 1;
  const managerSelected = await page.getByLabel('Role').inputValue();
  await flow.tabTo(/^Password/);
  await flow.type(PASSWORD);
  await flow.press('Enter');
  await page.getByRole('button', { name: 'Sign out' }).waitFor();
  await flow.note('manager signed in');
  await flow.tabTo(/Review policy/, merchant);
  await flow.press('Enter');
  await card(page, merchant).getByText('Eligible under hard rules').waitFor();
  await flow.note('policy reviewed');
  await flow.tabTo(/Approve/, merchant);
  await flow.press('Space');
  await page.getByText('Claim approved.').waitFor();
  await page.waitForTimeout(300);
  await flow.note('claim approved');
  await flow.tabTo(/^Sign out/);
  await flow.press('Enter');
  await page.getByRole('heading', { name: 'Open local demo' }).waitFor();

  await flow.tabTo(/^Role/);
  await page.keyboard.type('F'); flow.keys += 1;
  const financeSelected = await page.getByLabel('Role').inputValue();
  await flow.tabTo(/^Password/);
  await flow.type(PASSWORD);
  await flow.press('Enter');
  await page.getByRole('button', { name: 'Sign out' }).waitFor();
  await flow.note('finance signed in');
  await flow.tabTo(/Mark paid/, merchant);
  await flow.press('Enter');
  await page.getByText('Claim marked paid.').waitFor();
  await page.waitForTimeout(300);
  await flow.note('claim paid');
  await flow.tabTo(/^Sign out/);
  await flow.press('Enter');
  await page.getByRole('heading', { name: 'Open local demo' }).waitFor();

  await flow.tabTo(/^Role/);
  await page.keyboard.type('E'); flow.keys += 1;
  await flow.tabTo(/^Password/);
  await flow.type(PASSWORD);
  await flow.press('Enter');
  await page.getByRole('button', { name: 'Sign out' }).waitFor();
  await card(page, merchant).locator('.badge').filter({ hasText: 'PAID' }).waitFor();
  await flow.note('employee sees PAID');
  return { completed: true, nonTypingKeys: flow.keys, typedCharacters: flow.typed, managerSelected, financeSelected, steps: flow.steps };
}

async function retainedFocusOnPoll(page) {
  const box = page.getByLabel('Corrected receipt transcription').first();
  await box.click();
  await page.keyboard.type('Merchant: typing during a poll');
  await page.waitForTimeout(11000);
  const after = await page.evaluate(focusInfo);
  const value = await box.inputValue();
  await box.fill('');
  return { focusAfterPoll: after, valueKept: value === 'Merchant: typing during a poll' };
}

/* ---- Processes ---- */

function start(command, args, options) {
  const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], detached: true, ...options });
  child.stdout.on('data', () => {}); child.stderr.on('data', () => {});
  return child;
}
function stop(child) { try { process.kill(-child.pid, 'SIGTERM'); } catch { /* already gone */ } }
async function ready(url, seconds) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    try { const r = await fetch(url); if (r.ok) return; } catch { /* not yet */ }
    await new Promise(done => setTimeout(done, 250));
  }
  throw new Error(`${url} not ready after ${seconds}s`);
}

/* ---- Main ---- */

async function main() {
  execFileSync('npm', ['run', 'build'], { cwd: FRONTEND, stdio: 'ignore' });
  const api = start('sh', ['../scripts/run_e2e_api.sh'], { cwd: FRONTEND });
  const web = start('npx', ['vite', 'preview', '--host', '127.0.0.1', '--port', '5173', '--strictPort'], { cwd: FRONTEND });
  let browser;
  try {
    await ready(`${API}/actuator/health`, 60);
    await ready(CONSOLE, 30);
    browser = await chromium.launch({ channel: process.env.CI ? undefined : 'chrome', headless: true });
    report.tool.chrome = browser.version();
    const context = await browser.newContext({ viewport: WIDE });
    await context.addInitScript(liveRegionRecorder);
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send('Accessibility.enable');
    const audit = new Audit(page, cdp);

    // Signed out
    await audit.state('login', 'Signed-out page as loaded', async () => { await page.goto(CONSOLE); await page.getByRole('heading', { name: 'Open local demo' }).waitFor(); });
    await audit.state('login-error', 'Wrong password: real 401 from the API', async () => {
      await page.getByLabel('Password').fill('wrong-password');
      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.locator('.message.error').waitFor();
    });

    // Empty views, on a fresh database
    await page.goto(CONSOLE);
    await audit.state('manager-empty', 'Manager view with an empty queue', async () => { await signIn(page, 'manager'); await page.locator('.empty').waitFor(); });
    await audit.state('policy-answer', 'Policy desk answer with a cited clause', async () => {
      await page.getByLabel('Question').fill('What is the meal limit?');
      await page.getByRole('button', { name: 'Ask' }).click();
      await page.locator('.answer').waitFor();
    });
    await audit.state('policy-abstain', 'Policy desk abstains with no supporting clause', async () => {
      await page.getByLabel('Question').fill('zzz qqq xxx');
      await page.getByRole('button', { name: 'Ask' }).click();
      await page.getByText('No supporting clause').waitFor();
    });
    await audit.state('after-sign-out', 'Signed-out page straight after signing out', async () => { await signOut(page); });
    await audit.state('finance-empty', 'Finance view with nothing to pay and a zero summary', async () => { await signIn(page, 'finance'); await page.locator('.empty').waitFor(); });
    await signOut(page);
    await audit.state('employee-empty', 'Employee view: empty list and new-claim form, straight after signing in', async () => {
      await signIn(page, 'employee'); await page.locator('.empty').waitFor();
      await page.getByText('This server has no OCR engine configured.', { exact: false }).waitFor();
    });

    // Submit form and receipt upload
    await audit.state('submit-native-validation', 'Submit pressed with no amount: browser validation', async () => {
      await page.getByRole('button', { name: 'Submit claim' }).click();
      await page.waitForTimeout(200);
    });
    await page.getByLabel('Amount (SGD)').fill('7.77');
    const imageInput = page.getByLabel(/Receipt image/);
    // A keyboard user's focus is on the file input when the chooser closes, so start from there.
    const choose = async file => { await imageInput.focus(); await imageInput.setInputFiles(file); };
    await audit.state('upload-wrong-type', 'Receipt upload: text file refused in the browser', async () => {
      await choose({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('Merchant: x') });
      await page.getByText('Choose a PNG or JPEG image.').waitFor();
    });
    await audit.state('upload-bad-bytes', 'Receipt upload: declared PNG whose bytes are not an image', async () => {
      await choose({ name: 'fake.png', mimeType: 'image/png', buffer: Buffer.from('not really a png') });
      await page.getByText('This file is not a PNG or JPEG image.').waitFor();
    });
    await audit.state('upload-empty', 'Receipt upload: empty file', async () => {
      await choose({ name: 'empty.png', mimeType: 'image/png', buffer: Buffer.alloc(0) });
      await page.getByText('The receipt image is empty.').waitFor();
    });
    await audit.state('upload-oversize', 'Receipt upload: image over the 5 MB limit', async () => {
      await choose({ name: 'large.png', mimeType: 'image/png', buffer: Buffer.concat([PNG, Buffer.alloc(6 * 1024 * 1024)]) });
      await page.getByText('The receipt image is 6.0 MB; the limit is 5.0 MB.').waitFor();
    });
    await audit.state('upload-attached', 'Receipt upload: valid image attached, transcription disabled', async () => {
      await choose({ name: 'receipt.png', mimeType: 'image/png', buffer: PNG });
      await page.getByText(/Attached: receipt.png/).waitFor();
    });
    let release;
    const held = new Promise(done => { release = done; });
    await page.route('**/api/claims/image', async route => { if (route.request().method() === 'POST') { await held; } await route.continue(); });
    await audit.state('upload-in-progress', 'Upload in progress: the browser holds the request during the scan', async () => {
      await page.getByRole('button', { name: 'Submit claim' }).click();
      await page.getByLabel('Upload progress').waitFor();
    });
    release();
    await audit.state('upload-refused-503', 'Real 503 from the API: no OCR engine, no claim created', async () => {
      await page.getByText('Image OCR is not configured on this server.', { exact: false }).waitFor();
    });
    await page.unroute('**/api/claims/image');
    await page.getByRole('button', { name: 'Remove image' }).click();

    await audit.state('submit-server-400', 'Real 400 from the API: transcription over 10000 characters', async () => {
      await page.getByLabel('Amount (SGD)').fill('8.88');
      await page.getByLabel('Receipt transcription').fill('x'.repeat(10001));
      await page.getByRole('button', { name: 'Submit claim' }).click();
      await page.locator('.message.error').waitFor();
    });
    const employeeMerchant = `Audit Deli ${label}`;
    await audit.state('submit-success', 'Typed receipt accepted: success notice', async () => {
      await page.getByLabel('Amount (SGD)').fill('12.50');
      await page.getByLabel('Receipt transcription').fill(receipt(employeeMerchant, 1250));
      await page.getByRole('button', { name: 'Submit claim' }).click();
      await page.getByText('Claim queued for receipt validation.').waitFor();
    });
    await audit.state('employee-one-claim', 'Employee list after the worker validated the receipt', async () => {
      await card(page, employeeMerchant).waitFor({ timeout: 15000 });
    });
    await signOut(page);

    // Seed the claims the later states need, through the real API.
    const employee = await token('employee');
    const manager = await token('manager');
    const finance = await token('finance');
    // Merchant names avoid action words, so a button name that mentions its claim stays unambiguous.
    const names = Object.fromEntries(['review', 'blocked', 'approve', 'reject', 'stale', 'pay', 'staleFinance', 'approvedLeft', 'correct', 'stillManual', 'stateApproved', 'statePaid', 'stateRejected']
      .map((key, index) => [key, `Cafe ${String.fromCharCode(65 + index)}${String.fromCharCode(65 + index)} ${label}`]));
    const ids = {};
    ids.review = await seed(employee, names.review, 1100);
    ids.blocked = await seed(employee, names.blocked, 7500);
    ids.approve = await seed(employee, names.approve, 1200);
    ids.reject = await seed(employee, names.reject, 1300);
    ids.stale = await seed(employee, names.stale, 1400);
    ids.pay = await seed(employee, names.pay, 1500);
    ids.staleFinance = await seed(employee, names.staleFinance, 1600);
    ids.approvedLeft = await seed(employee, names.approvedLeft, 1700);
    ids.stateApproved = await seed(employee, names.stateApproved, 1800);
    ids.statePaid = await seed(employee, names.statePaid, 1900);
    ids.stateRejected = await seed(employee, names.stateRejected, 2000);
    ids.correct = await seed(employee, names.correct, 2100, { asyncInvalid: true });
    ids.stillManual = await seed(employee, names.stillManual, 2200, { asyncInvalid: true });
    for (const key of ['pay', 'staleFinance', 'approvedLeft', 'stateApproved', 'statePaid']) await post(`/api/claims/${ids[key].id}/decision`, manager, { approve: true });
    await post(`/api/claims/${ids.stateRejected.id}/decision`, manager, { approve: false });
    await post(`/api/claims/${ids.statePaid.id}/paid`, finance);

    // Manager
    await audit.state('manager-queue', 'Manager queue with review cards and receipt-correction forms, straight after signing in', async () => {
      await signIn(page, 'manager'); await card(page, names.review).waitFor(); await card(page, 'SGD 21.00').waitFor();
    });
    report.keyboard.pollWhileTyping = await retainedFocusOnPoll(page).catch(error => ({ error: String(error) }));
    await audit.state('correction-error', 'Real 400 from the API: unreadable correction', async () => {
      const form = card(page, 'employee').filter({ hasText: 'SGD 21.00' });
      await form.getByLabel('Corrected receipt transcription').fill('still unreadable');
      await form.getByRole('button', { name: 'Complete receipt check' }).click();
      await page.locator('.message.error').waitFor();
    });
    await audit.state('correction-success', 'Correction accepted: success notice', async () => {
      const form = card(page, 'employee').filter({ hasText: 'SGD 21.00' });
      await form.getByLabel('Corrected receipt transcription').fill(receipt(names.correct, 2100));
      await form.getByRole('button', { name: 'Complete receipt check' }).click();
      await page.getByText('Receipt checked and claim sent to review.').waitFor();
    });
    await audit.state('review-eligible', 'Policy review panel: eligible', async () => {
      await card(page, names.review).getByRole('button', { name: 'Review policy' }).click();
      await card(page, names.review).getByText('Eligible under hard rules').waitFor();
    });
    await audit.state('review-blocked', 'Policy review panel: hard rule blocks the claim', async () => {
      await card(page, names.blocked).getByRole('button', { name: 'Review policy' }).click();
      await card(page, names.blocked).getByText('Policy block').waitFor();
    });
    await audit.state('decision-blocked-409', 'Real 409 from the API: approval blocked by policy', async () => {
      await card(page, names.blocked).getByRole('button', { name: 'Approve' }).click();
      await page.getByText('Hard policy rule blocks approval').waitFor();
    });
    await audit.state('decision-approved', 'Claim approved: the card leaves the queue', async () => {
      await card(page, names.approve).getByRole('button', { name: 'Approve' }).click();
      await page.getByText('Claim approved.').waitFor();
      await page.waitForTimeout(300);
    });
    await audit.state('decision-rejected', 'Claim rejected: the card leaves the queue', async () => {
      await card(page, names.reject).getByRole('button', { name: 'Reject' }).click();
      await page.getByText('Claim rejected.').waitFor();
      await page.waitForTimeout(300);
    });
    await audit.state('decision-stale-409', 'Real 409 from the API: another session decided this claim first', async () => {
      await refreshView(page);
      await post(`/api/claims/${ids.stale.id}/decision`, manager, { approve: true });
      await card(page, names.stale).getByRole('button', { name: 'Approve' }).click();
      await page.locator('.message.error').waitFor();
    });
    await signOut(page);

    // Finance
    await audit.state('finance-list', 'Finance list with approved claims and a populated summary, straight after signing in', async () => {
      await signIn(page, 'finance'); await card(page, names.pay).waitFor();
    });
    await audit.state('payment-success', 'Claim paid: the card leaves the list', async () => {
      await card(page, names.pay).getByRole('button', { name: 'Mark paid' }).click();
      await page.getByText('Claim marked paid.').waitFor();
      await page.waitForTimeout(300);
    });
    await audit.state('payment-stale-409', 'Real 409 from the API: already paid in another session', async () => {
      await refreshView(page);
      await post(`/api/claims/${ids.staleFinance.id}/paid`, finance);
      await card(page, names.staleFinance).getByRole('button', { name: 'Mark paid' }).click();
      await page.locator('.message.error').waitFor();
    });
    await signOut(page);

    // Every badge colour an employee can see
    await audit.state('employee-all-states', 'Employee list with IN REVIEW, NEEDS HUMAN CHECK, APPROVED, REJECTED and PAID badges', async () => {
      await signIn(page, 'employee');
      for (const merchant of [names.stateApproved, names.statePaid, names.stateRejected]) await card(page, merchant).waitFor();
      await page.locator('.badge').filter({ hasText: /NEEDS HUMAN.CHECK/ }).first().waitFor();
    });

    // Keyboard: chooser, then Tab cycles on populated views
    report.keyboard.fileChooserOpensWithSpace = await (async () => {
      const chooser = page.waitForEvent('filechooser', { timeout: 4000 }).then(() => true).catch(() => false);
      await page.getByLabel(/Receipt image/).focus();
      await page.keyboard.press('Space');
      return chooser;
    })();
    report.keyboard.tabCycles = [];
    await page.waitForTimeout(200);
    report.keyboard.tabCycles.push(await tabCycle(page, cdp, 'employee, populated'));
    await signOut(page);
    report.keyboard.tabCycles.push(await tabCycle(page, cdp, 'signed out'));
    await signIn(page, 'manager');
    await card(page, names.review).waitFor();
    report.keyboard.tabCycles.push(await tabCycle(page, cdp, 'manager, populated'));
    await signOut(page);
    await signIn(page, 'finance');
    await card(page, names.approvedLeft).waitFor();
    report.keyboard.tabCycles.push(await tabCycle(page, cdp, 'finance, populated'));
    await signOut(page);

    // Keyboard: a whole flow with no mouse
    report.keyboard.flow = await keyboardFlow(page).catch(error => ({ completed: false, error: String(error).slice(0, 400) }));
    await page.goto(CONSOLE);

    // The API really stopped
    await signIn(page, 'employee');
    await card(page, names.statePaid).waitFor();
    stop(api);
    await new Promise(done => setTimeout(done, 2500));
    await audit.state('api-down-refresh', 'API process stopped: Refresh fails', async () => {
      await page.getByRole('button', { name: 'Refresh' }).click();
      await page.locator('.message.error').waitFor();
    });
    await audit.state('api-down-submit', 'API process stopped: submitting a claim fails', async () => {
      await page.getByLabel('Amount (SGD)').fill('9.99');
      await page.getByRole('button', { name: 'Submit claim' }).click();
      await page.locator('.message.error').waitFor();
    });
    await audit.state('api-down-login', 'API process stopped: signing in fails', async () => {
      await page.goto(CONSOLE);
      await page.getByLabel('Password').fill(PASSWORD);
      await page.getByRole('button', { name: 'Sign in' }).click();
      await page.locator('.message.error').waitFor();
    });
  } finally {
    if (browser) await browser.close();
    stop(api); stop(web);
  }
}

function summarise() {
  const unique = new Map();
  const scansWithViolations = { total: 0, withViolations: 0 };
  const rulesSeen = new Set();
  const incomplete = {};
  for (const scan of report.scans) {
    for (const [viewport, result] of [['wide', scan.wide], ['narrow', scan.narrow]]) {
      scansWithViolations.total += 1;
      if (result.violations.length) scansWithViolations.withViolations += 1;
      for (const violation of result.violations) {
        rulesSeen.add(violation.id);
        for (const node of violation.nodes) {
          const key = `${violation.id}\u0000${node.target}`;
          if (!unique.has(key)) unique.set(key, { rule: violation.id, impact: violation.impact, wcag: violation.tags.some(tag => WCAG_TAGS.has(tag)), help: violation.help, target: node.target, html: node.html, states: new Set(), viewports: new Set() });
          unique.get(key).states.add(scan.id); unique.get(key).viewports.add(viewport);
        }
      }
      for (const item of result.incomplete) {
        incomplete[item.id] = incomplete[item.id] || { impact: item.impact, targets: new Set() };
        item.nodes.forEach(target => incomplete[item.id].targets.add(target));
      }
    }
  }
  const rows = [...unique.values()].map(row => ({ ...row, states: [...row.states], viewports: [...row.viewports] }));
  const openingTag = html => { const match = /^<([a-z0-9]+)(?:[^>]*?\sclass="([^"]*)")?/i.exec(html); return match ? `${match[1]}${match[2] ? `.${match[2].trim().split(/\s+/).sort().join('.')}` : ''}` : html; };
  const patterns = [...new Set(rows.map(row => `${row.rule} ${openingTag(row.html)}`))].sort();
  const byRule = {}; const byImpact = {};
  for (const row of rows) {
    byRule[row.rule] = byRule[row.rule] || { impact: row.impact, wcagTagged: row.wcag, help: row.help, uniqueElements: 0, states: new Set() };
    byRule[row.rule].uniqueElements += 1; row.states.forEach(state => byRule[row.rule].states.add(state));
    byImpact[row.impact] = (byImpact[row.impact] || 0) + 1;
  }
  for (const value of Object.values(byRule)) value.states = value.states.size;
  const scans = report.scans.length;
  const reach = report.scans.flatMap(scan => scan.liveEvents);
  report.summary = {
    statesPlanned: scans + report.unreached.length, statesScanned: scans, statesNotReached: report.unreached.map(item => item.id),
    axeRuns: scansWithViolations.total, axeRunsWithViolations: scansWithViolations.withViolations,
    axeRulesEvaluatedPerRun: report.scans.length ? report.scans[0].wide.passes + report.scans[0].wide.inapplicable + report.scans[0].wide.violations.length + report.scans[0].wide.incomplete.length : 0,
    uniqueViolations: rows.length, uniqueRuleAndElementPatterns: patterns.length, rulePatterns: patterns, wcagTaggedUniqueViolations: rows.filter(row => row.wcag).length, bestPracticeOnlyUniqueViolations: rows.filter(row => !row.wcag).length,
    byImpact, byRule,
    incompleteNeedingAPerson: Object.fromEntries(Object.entries(incomplete).map(([id, value]) => [id, { impact: value.impact, uniqueElements: value.targets.size }])),
    uploadErrorsLinkedToTheControl: (() => {
      const scans = report.scans.filter(scan => ['upload-wrong-type', 'upload-bad-bytes', 'upload-empty', 'upload-oversize'].includes(scan.id));
      const linked = scans.filter(scan => { const row = scan.accessibilityTree.find(node => /Receipt image/.test(node.name)); return row && row.invalid && row.description; });
      return { linked: linked.length, of: scans.length };
    })(),
    statesWithFocusOnBody: report.scans.filter(scan => scan.focus.onBody).map(scan => scan.id),
    statesWithReflowOverflowAt320: report.scans.filter(scan => scan.reflowOverflowPx320 > 0).map(scan => ({ id: scan.id, px: scan.reflowOverflowPx320 })),
    pageTitles: [...new Set(report.scans.map(scan => scan.title))],
    liveMessages: reach.length, liveMessagesInPreexistingRegion: reach.filter(item => item.regionPreexisted).length,
    tabCycles: (report.keyboard.tabCycles || []).map(({ stopList, ...rest }) => rest),
    keyboardFlowCompleted: !!(report.keyboard.flow && report.keyboard.flow.completed),
  };
  report.uniqueViolations = rows;
}

const started = Date.now();
let failed = null;
await main().catch(error => { failed = error; });
report.durationSeconds = Math.round((Date.now() - started) / 1000);
if (failed) report.aborted = String(failed.stack || failed).slice(0, 1000);
summarise();
mkdirSync(outDir, { recursive: true });
const date = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Singapore' });
const path = join(outDir, `audit_${label}_${date}.json`);
writeFileSync(path, `${JSON.stringify(report, null, 1)}\n`);
console.log(JSON.stringify(report.summary, null, 1));
console.log(path);
process.exit(failed || report.unreached.length ? 2 : 0);
