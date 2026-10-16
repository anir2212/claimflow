import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from './App';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function reply(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

it('lets an employee sign in, submit a text receipt, and see the updated claim', async () => {
  const claim = { id: 'claim-123456', employeeId: 'employee', category: 'meal', amountCents: 1250,
    merchant: 'Example Cafe', expenseDate: '2026-10-01', state: 'IN_REVIEW' };
  let created = false;
  const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/api/auth/token')) return reply({ access_token: 'test-token' });
    if (url.endsWith('/api/claims/mine')) return reply(created ? [claim] : []);
    if (url.endsWith('/api/claims/async') && options?.method === 'POST') { created = true; return reply(claim); }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  vi.stubGlobal('crypto', { randomUUID: () => 'test-key' });
  render(<App />);
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-only' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByText('My claims');
  fireEvent.change(screen.getByLabelText('Amount (SGD)'), { target: { value: '12.50' } });
  fireEvent.change(screen.getByLabelText('Receipt transcription'), {
    target: { value: 'Merchant: Example Cafe\nDate: 2026-10-01\nTotal: SGD 12.50' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }));
  await screen.findByText('Example Cafe');
  expect(screen.getByText('SGD 12.50')).toBeTruthy();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining('/api/claims/async'), expect.objectContaining({ method: 'POST' }),
  ));
});

it('lets a manager correct a receipt that needs human review', async () => {
  const claim = { id: 'manual-123456', employeeId: 'employee', category: 'meal', amountCents: 1900,
    merchant: null, expenseDate: null, state: 'NEEDS_HUMAN_CHECK' };
  let corrected = false;
  const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/api/auth/token')) return reply({ access_token: 'manager-token' });
    if (url.endsWith('/api/claims/queue')) return reply([]);
    if (url.endsWith('/api/claims/receipt-check') && !options?.method)
      return reply(corrected ? [] : [claim]);
    if (url.endsWith('/api/claims/manual-123456/receipt-check') && options?.method === 'POST') {
      corrected = true;
      return reply({ ...claim, state: 'IN_REVIEW', merchant: 'Manual Cafe' });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'manager' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-only' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByText('manual-1 · employee');
  fireEvent.change(screen.getByLabelText('Corrected receipt transcription'), {
    target: { value: 'Merchant: Manual Cafe\nDate: 2026-10-04\nTotal: SGD 19.00' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Complete receipt check' }));
  await screen.findByText('Receipt checked and claim sent to review.');
  expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining('/api/claims/manual-123456/receipt-check'),
    expect.objectContaining({ method: 'POST' }),
  );
});
