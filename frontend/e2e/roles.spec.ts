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
