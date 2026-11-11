import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

// Regression checks in real Chrome, including colour contrast, which jsdom cannot measure. These
// guard the fixes recorded in RESULTS.md. They do not show the console is accessible: axe-core finds
// a subset of problems, and a manual screen-reader pass by a person is still open.
const HERE = dirname(fileURLToPath(import.meta.url));
const AXE = readFileSync(join(HERE, '..', 'node_modules', 'axe-core', 'axe.min.js'), 'utf8');
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

async function expectNoAxeViolations(page: Page, state: string) {
  await page.addScriptTag({ content: AXE });
  const violations = await page.evaluate(async tags => {
    const result = await (window as unknown as { axe: { run: (context: Document, options: object) => Promise<{ violations: { id: string; nodes: { target: string[] }[] }[] }> } })
      .axe.run(document, { runOnly: { type: 'tag', values: tags } });
    return result.violations.map(violation => `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`);
  }, TAGS);
  expect(violations, state).toEqual([]);
}

async function signIn(page: Page, role: 'employee' | 'manager' | 'finance') {
  await page.getByLabel('Role').selectOption(role);
  await page.getByLabel('Password').fill('local-only');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

async function focusedName(page: Page) {
  return page.evaluate(() => {
    const element = document.activeElement as HTMLElement;
    return element === document.body ? '' : (element.textContent || '').trim();
  });
}

test('every role and its error states have no axe violations, contrast included', async ({ page }) => {
  const merchant = `Axe Cafe ${Date.now().toString(36)}`;
  await page.goto('/');
  await expectNoAxeViolations(page, 'signed out');
  await page.getByLabel('Password').fill('wrong');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.locator('.message.error')).toBeVisible();
  await expect(page.getByLabel('Password')).toBeFocused();
  await expectNoAxeViolations(page, 'wrong password');

  await signIn(page, 'employee');
  await page.getByLabel(/Receipt image/).setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('x') });
  await expect(page.getByText('Choose a PNG or JPEG image.')).toBeVisible();
  await expect(page.getByLabel(/Receipt image/)).toHaveAttribute('aria-invalid', 'true');
  await expectNoAxeViolations(page, 'upload error');
  await page.getByLabel('Amount (SGD)').fill('31.17');
  await page.getByLabel('Receipt transcription').fill(`Merchant: ${merchant}\nDate: 2026-11-06\nTotal: SGD 31.17`);
  await page.getByRole('button', { name: 'Submit claim' }).click();
  await expect(page.locator('article.claim').filter({ hasText: merchant })).toBeVisible({ timeout: 15_000 });
  await expectNoAxeViolations(page, 'employee list and success notice');
  await page.getByRole('button', { name: 'Sign out' }).click();

  await signIn(page, 'manager');
  const card = page.locator('article.claim').filter({ hasText: merchant });
  await card.getByRole('button', { name: 'Review policy' }).click();
  await expect(card.getByText('Eligible under hard rules')).toBeVisible();
  await expectNoAxeViolations(page, 'manager queue with a review panel');
  await card.getByRole('button', { name: 'Approve' }).click();
  await expect(page.getByText('Claim approved.')).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();

  await signIn(page, 'finance');
  await expect(page.locator('article.claim').filter({ hasText: merchant })).toBeVisible();
  await expectNoAxeViolations(page, 'finance list and summary');
});

test('a claim can go from submission to payment with the keyboard alone, and focus never falls to the page', async ({ page }) => {
  const merchant = `Keys Cafe ${Date.now().toString(36)}`;

  async function tabTo(name: RegExp) {
    for (let presses = 0; presses < 80; presses += 1) {
      await page.keyboard.press('Tab');
      if (name.test(await focusedName(page))) return;
    }
    throw new Error(`Tab did not reach ${name}`);
  }
  async function keyboardSignIn(roleKey: string, heading: string) {
    await page.keyboard.press('Tab');
    await page.keyboard.type(roleKey);
    await page.keyboard.press('Tab');
    await page.keyboard.type('local-only');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: heading })).toBeFocused();
    await expect(page).toHaveTitle(`${heading} · ClaimFlow`);
  }
  async function keyboardSignOut() {
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: 'Open local demo' })).toBeFocused();
    await expect(page).toHaveTitle('Sign in · ClaimFlow');
  }

  await page.goto('/');
  await keyboardSignIn('E', 'My claims');
  await page.keyboard.press('Tab'); // Refresh
  await page.keyboard.press('Tab'); // Category
  await page.keyboard.press('Tab'); // Amount
  await expect(page.getByLabel('Amount (SGD)')).toBeFocused();
  await page.keyboard.type('33.33');
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Receipt transcription')).toBeFocused();
  await page.keyboard.type(`Merchant: ${merchant}`);
  await page.keyboard.press('Enter');
  await page.keyboard.type('Date: 2026-11-06');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Total: SGD 33.33');
  await tabTo(/^Submit claim/);
  await page.keyboard.press('Enter');
  await expect(page.getByText('Claim queued for receipt validation.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Submit claim' })).toBeFocused();
  await expect(page.locator('article.claim').filter({ hasText: merchant })).toBeVisible({ timeout: 15_000 });
  await page.getByRole('heading', { name: 'My claims' }).focus();
  await keyboardSignOut();

  await keyboardSignIn('M', 'Approval queue');
  await tabTo(new RegExp(`^Approve.*${merchant}`));
  await page.keyboard.press('Space');
  await expect(page.getByText('Claim approved.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Approval queue' })).toBeFocused();
  await keyboardSignOut();

  await keyboardSignIn('F', 'Ready to pay');
  await tabTo(new RegExp(`^Mark paid.*${merchant}`));
  await page.keyboard.press('Enter');
  await expect(page.getByText('Claim marked paid.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Ready to pay' })).toBeFocused();
});
