import { expect, test, type Page } from '@playwright/test';

async function signIn(page: Page, role: 'employee' | 'manager' | 'finance') {
  await page.getByLabel('Role').selectOption(role);
  await page.getByLabel('Password').fill('local-only');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Open local demo' })).toBeVisible();
}

test('employee submission reaches manager approval, finance payment, and employee history', async ({ page }) => {
  const merchant = `Browser Cafe ${Date.now().toString(36)}`;
  await page.goto('/');
  await signIn(page, 'employee');
  await page.getByLabel('Amount (SGD)').fill('12.50');
  await page.getByLabel('Receipt transcription').fill(
    `Merchant: ${merchant}\nDate: 2026-10-13\nTotal: SGD 12.50`,
  );
  await page.getByRole('button', { name: 'Submit claim' }).click();
  await expect(page.getByText('Claim queued for receipt validation.')).toBeVisible();
  await expect(page.locator('article.claim').filter({ hasText: merchant })).toBeVisible({ timeout: 15_000 });
  await signOut(page);

  await signIn(page, 'manager');
  const managerCard = page.locator('article.claim').filter({ hasText: merchant });
  await expect(managerCard).toBeVisible();
  await managerCard.getByRole('button', { name: 'Review policy' }).click();
  await expect(managerCard.getByText('Eligible under hard rules')).toBeVisible();
  await managerCard.getByRole('button', { name: 'Approve' }).click();
  await expect(page.getByText('Claim approved.')).toBeVisible();
  await signOut(page);

  await signIn(page, 'finance');
  const financeCard = page.locator('article.claim').filter({ hasText: merchant });
  await expect(financeCard).toBeVisible();
  await financeCard.getByRole('button', { name: 'Mark paid' }).click();
  await expect(page.getByText('Claim marked paid.')).toBeVisible();
  await signOut(page);

  await signIn(page, 'employee');
  const paidCard = page.locator('article.claim').filter({ hasText: merchant });
  await expect(paidCard).toBeVisible();
  await expect(paidCard.locator('.badge')).toHaveText('PAID');
});

test('manager corrects an invalid transcription before policy review', async ({ page }) => {
  const merchant = `Corrected Cafe ${Date.now().toString(36)}`;
  await page.goto('/');
  await signIn(page, 'employee');
  await page.getByLabel('Amount (SGD)').fill('19.00');
  await page.getByLabel('Receipt transcription').fill(`Unreadable receipt ${merchant}`);
  await page.getByRole('button', { name: 'Submit claim' }).click();
  await expect(page.getByText('Claim queued for receipt validation.')).toBeVisible();
  await signOut(page);

  await signIn(page, 'manager');
  const manualCard = page.locator('article.claim').filter({ hasText: 'SGD 19.00' });
  await expect(manualCard).toBeVisible({ timeout: 15_000 });
  await manualCard.getByLabel('Corrected receipt transcription').fill(
    `Merchant: ${merchant}\nDate: 2026-10-13\nTotal: SGD 19.00`,
  );
  await manualCard.getByRole('button', { name: 'Complete receipt check' }).click();
  await expect(page.getByText('Receipt checked and claim sent to review.')).toBeVisible();
  await expect(page.locator('article.claim').filter({ hasText: merchant })).toBeVisible();
});

// No OCR engine is configured on the e2e API, so a valid upload must be refused with no claim.
test('employee image upload is validated in the browser and refused by a server without OCR', async ({ page }) => {
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d]);
  await page.goto('/');
  await signIn(page, 'employee');
  await expect(page.getByText('This server has no OCR engine configured.', { exact: false })).toBeVisible();
  await expect(page.locator('article.claim, .card.empty').first()).toBeVisible();
  const claimsBefore = await page.locator('article.claim').count();
  await page.getByLabel('Amount (SGD)').fill('7.77');

  await page.getByLabel(/Receipt image/).setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('Merchant: x') });
  await expect(page.getByText('Choose a PNG or JPEG image.')).toBeVisible();
  const oversized = Buffer.concat([png, Buffer.alloc(6 * 1024 * 1024)]);
  await page.getByLabel(/Receipt image/).setInputFiles({ name: 'large.png', mimeType: 'image/png', buffer: oversized });
  await expect(page.getByText('The receipt image is 6.0 MB; the limit is 5.0 MB.')).toBeVisible();
  await expect(page.getByText(/Attached:/)).toHaveCount(0);

  await page.getByLabel(/Receipt image/).setInputFiles({ name: 'receipt.png', mimeType: 'image/png', buffer: png });
  await expect(page.getByText('Attached: receipt.png · 12 B')).toBeVisible();
  await expect(page.getByLabel('Receipt transcription')).toBeDisabled();
  const response = page.waitForResponse(r => r.url().endsWith('/api/claims/image') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Submit claim' }).click();
  expect((await response).status()).toBe(503);
  await expect(page.getByText(
    'Image OCR is not configured on this server. No claim was created; submit a typed transcription instead.',
  )).toBeVisible();
  await expect(page.getByLabel('Upload progress')).toHaveCount(0);
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.locator('article.claim')).toHaveCount(claimsBefore);
  await expect(page.locator('article.claim').filter({ hasText: 'SGD 7.77' })).toHaveCount(0);
});
