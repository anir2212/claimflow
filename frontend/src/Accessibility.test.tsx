import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import App from './App';

// Regression tests for the fixes in RESULTS.md's accessibility section. jsdom has no layout, so
// colour contrast is checked in real Chrome by e2e/accessibility.spec.ts and a11y/audit.mjs instead.
// Passing these tests does not make the console accessible; a manual screen-reader pass is still open.
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

function reply(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

const claim = (id: string, state: string, merchant: string | null = `Cafe ${id}`) => ({
  id, employeeId: 'employee', category: 'meal', amountCents: 1250, merchant, expenseDate: '2026-11-06', state,
});

type Handler = (url: string, options?: RequestInit) => Response | Promise<Response> | undefined;

function server(handler: Handler) {
  const mock = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/api/claims/image/limits')) return reply({ maxBytes: 64, mediaTypes: ['image/png', 'image/jpeg'], ocrConfigured: false });
    const answer = await handler(url, options);
    if (answer) return answer;
    if (url.endsWith('/api/auth/token')) return reply({ access_token: 'test-token' });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}

async function signIn(role: 'employee' | 'manager' | 'finance') {
  render(<App />);
  fireEvent.change(screen.getByLabelText('Role'), { target: { value: role } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-only' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByRole('button', { name: 'Sign out' });
}

async function axeViolations() {
  const result = await axe.run(document, { runOnly: { type: 'tag', values: TAGS }, rules: { 'color-contrast': { enabled: false } } });
  return result.violations.map(violation => `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`);
}

// index.html sets lang="en"; jsdom's blank document does not have it.
beforeEach(() => { document.documentElement.lang = 'en'; vi.stubGlobal('crypto', { randomUUID: () => 'test-key' }); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); document.title = ''; });

describe('axe-core in jsdom, without contrast', () => {
  it('finds nothing on the signed-out page or after a failed sign-in', async () => {
    server(url => (url.endsWith('/api/auth/token') ? reply({}, 401) : undefined));
    render(<App />);
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'nope' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByText(/password is not right/);
    expect(await axeViolations()).toEqual([]);
  });

  it('finds nothing on the employee form with an upload error', async () => {
    server(url => (url.endsWith('/api/claims/mine') ? reply([claim('a', 'NEEDS_HUMAN_CHECK')]) : undefined));
    await signIn('employee');
    await screen.findByText('Cafe a');
    fireEvent.change(screen.getByLabelText(/Receipt image/), { target: { files: [new File(['x'], 'n.txt', { type: 'text/plain' })] } });
    await screen.findByText('Choose a PNG or JPEG image.');
    expect(await axeViolations()).toEqual([]);
  });

  it('finds nothing on the manager queue with review and correction cards', async () => {
    server(url => {
      if (url.endsWith('/api/claims/queue')) return reply([claim('a', 'IN_REVIEW')]);
      if (url.endsWith('/api/claims/receipt-check')) return reply([claim('b', 'NEEDS_HUMAN_CHECK', null)]);
      if (url.endsWith('/api/claims/a/review')) return reply({ eligible: true, violations: [], citations: [] });
      return undefined;
    });
    await signIn('manager');
    fireEvent.click(await screen.findByRole('button', { name: /^Review policy claim: Cafe a/ }));
    await screen.findByText('Eligible under hard rules');
    expect(await axeViolations()).toEqual([]);
  });

  it('finds nothing on the finance list', async () => {
    server(url => {
      if (url.endsWith('/api/claims/approved')) return reply([claim('a', 'APPROVED')]);
      if (url.endsWith('/api/claims/stats')) return reply({ claimCount: 1, totalCents: 1250, byCategoryCents: { meal: 1250 }, byStateCount: { APPROVED: 1 } });
      return undefined;
    });
    await signIn('finance');
    await screen.findByText('Cafe a');
    expect(await axeViolations()).toEqual([]);
  });
});

describe('focus', () => {
  it('moves to the new heading and names the page after signing in and out', async () => {
    server(url => (url.endsWith('/api/claims/queue') || url.endsWith('/api/claims/receipt-check') ? reply([]) : undefined));
    render(<App />);
    expect(document.activeElement).toBe(document.body); // nothing is stolen on first load
    expect(document.title).toBe('Sign in · ClaimFlow');
    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'manager' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-only' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    const heading = await screen.findByRole('heading', { name: 'Approval queue' });
    expect(document.activeElement).toBe(heading);
    expect(document.title).toBe('Approval queue · ClaimFlow');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(document.activeElement).toBe(await screen.findByRole('heading', { name: 'Open local demo' }));
    expect(document.title).toBe('Sign in · ClaimFlow');
  });

  it('goes to the password field, linked to the message, after a wrong password', async () => {
    server(url => (url.endsWith('/api/auth/token') ? reply({ error: 'Unauthorized' }, 401) : undefined));
    render(<App />);
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    const message = await screen.findByText(/password is not right/);
    const password = screen.getByLabelText('Password');
    expect(document.activeElement).toBe(password);
    expect(password.getAttribute('aria-invalid')).toBe('true');
    expect(password.getAttribute('aria-describedby')).toContain(message.id);
  });

  it('stays on the file input, linked to the message, after an invalid image', async () => {
    server(url => (url.endsWith('/api/claims/mine') ? reply([]) : undefined));
    await signIn('employee');
    await screen.findByText(/no OCR engine configured/);
    screen.getByLabelText(/Receipt image/).focus();
    fireEvent.change(screen.getByLabelText(/Receipt image/), { target: { files: [new File(['x'], 'n.txt', { type: 'text/plain' })] } });
    const message = await screen.findByText('Choose a PNG or JPEG image.');
    const input = screen.getByLabelText(/Receipt image/); // an invalid choice remounts the input
    expect(document.activeElement).toBe(input);
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toContain(message.id);
    expect(input.getAttribute('aria-describedby')).toContain('image-hint');
  });

  it('moves to the list heading when the decided claim leaves the queue', async () => {
    let decided = false;
    server((url, options) => {
      if (url.endsWith('/api/claims/queue')) return reply(decided ? [] : [claim('a', 'IN_REVIEW')]);
      if (url.endsWith('/api/claims/receipt-check')) return reply([]);
      if (url.endsWith('/api/claims/a/decision') && options?.method === 'POST') { decided = true; return reply(claim('a', 'APPROVED')); }
      return undefined;
    });
    await signIn('manager');
    const approve = await screen.findByRole('button', { name: /^Approve claim: Cafe a/ });
    approve.focus();
    fireEvent.click(approve);
    await screen.findByText('Claim approved.');
    await screen.findByText('No claims in this view.');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Approval queue' })));
  });

  it('keeps a busy button focusable and ignores a second press', async () => {
    let finish: (value: Response) => void = () => {};
    const mock = server((url, options) => {
      if (url.endsWith('/api/claims/queue')) return reply([claim('a', 'IN_REVIEW')]);
      if (url.endsWith('/api/claims/receipt-check')) return reply([]);
      if (url.endsWith('/api/claims/a/decision') && options?.method === 'POST') return new Promise<Response>(done => { finish = done; });
      return undefined;
    });
    await signIn('manager');
    const approve = await screen.findByRole('button', { name: /^Approve claim: Cafe a/ });
    approve.focus();
    fireEvent.click(approve);
    await waitFor(() => expect(approve.getAttribute('aria-disabled')).toBe('true'));
    expect(document.activeElement).toBe(approve);
    fireEvent.click(approve);
    expect(mock.mock.calls.filter(([url]) => String(url).endsWith('/decision'))).toHaveLength(1);
    finish(reply(claim('a', 'APPROVED')));
    await screen.findByText('Claim approved.');
  });

  it('goes to the correction box, linked to the message, when the server refuses a correction', async () => {
    server((url, options) => {
      if (url.endsWith('/api/claims/queue')) return reply([]);
      if (url.endsWith('/api/claims/receipt-check') && !options?.method) return reply([claim('b', 'NEEDS_HUMAN_CHECK', null)]);
      if (url.endsWith('/api/claims/b/receipt-check')) return reply({ error: 'Receipt needs human check' }, 400);
      return undefined;
    });
    await signIn('manager');
    const box = await screen.findByLabelText('Corrected receipt transcription');
    fireEvent.change(box, { target: { value: 'still unreadable' } });
    fireEvent.click(screen.getByRole('button', { name: /^Complete receipt check/ }));
    const message = await screen.findByText('Receipt needs human check');
    expect(document.activeElement).toBe(box);
    expect(box.getAttribute('aria-invalid')).toBe('true');
    expect(box.getAttribute('aria-describedby')).toContain(message.id);
  });
});

describe('announcements and names', () => {
  it('keeps both live regions in the page before any message arrives', async () => {
    server(url => (url.endsWith('/api/auth/token') ? reply({}, 401) : undefined));
    render(<App />);
    const alert = screen.getByRole('alert');
    const status = screen.getByRole('status');
    expect(alert.textContent).toBe('');
    expect(status.textContent).toBe('');
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByText(/password is not right/);
    expect(screen.getByRole('alert')).toBe(alert); // the same element, so the change is announced
  });

  it('puts a policy review result in a live region that was already there', async () => {
    server(url => {
      if (url.endsWith('/api/claims/queue')) return reply([claim('a', 'IN_REVIEW')]);
      if (url.endsWith('/api/claims/receipt-check')) return reply([]);
      if (url.endsWith('/api/claims/a/review')) return reply({ eligible: false, violations: ['Meal cap exceeded'], citations: ['P-2'] });
      return undefined;
    });
    await signIn('manager');
    const button = await screen.findByRole('button', { name: /^Review policy/ });
    const card = button.closest('article') as HTMLElement;
    const region = card.querySelector('[aria-live="polite"]') as HTMLElement;
    expect(region.textContent).toBe('');
    fireEvent.click(button);
    await within(region).findByText('Policy block');
  });

  it('names every action after its claim, so repeated buttons can be told apart', async () => {
    server(url => {
      if (url.endsWith('/api/claims/queue')) return reply([claim('a', 'IN_REVIEW'), claim('c', 'IN_REVIEW')]);
      if (url.endsWith('/api/claims/receipt-check')) return reply([]);
      return undefined;
    });
    await signIn('manager');
    await screen.findByRole('button', { name: 'Approve claim: Cafe a, SGD 12.50' });
    screen.getByRole('button', { name: 'Reject claim: Cafe c, SGD 12.50' });
    expect(screen.getByRole('article', { name: 'Cafe c' })).toBeTruthy();
  });

  it('writes the whole state name in words on the badge', async () => {
    server(url => (url.endsWith('/api/claims/mine') ? reply([claim('a', 'NEEDS_HUMAN_CHECK')]) : undefined));
    await signIn('employee');
    expect(await screen.findByText('NEEDS HUMAN CHECK')).toBeTruthy();
  });

  it('turns a stale-claim 409 and an unreachable API into sentences, and refreshes the stale list', async () => {
    let listed = 0;
    server((url, options) => {
      if (url.endsWith('/api/claims/queue')) { listed += 1; return reply(listed === 1 ? [claim('a', 'IN_REVIEW')] : []); }
      if (url.endsWith('/api/claims/receipt-check')) return reply([]);
      if (url.endsWith('/api/claims/a/decision') && options?.method === 'POST') return reply({ error: 'Illegal transition APPROVED -> APPROVED' }, 409);
      return undefined;
    });
    await signIn('manager');
    fireEvent.click(await screen.findByRole('button', { name: /^Approve/ }));
    await screen.findByText('This claim was already changed in another session. The list has been refreshed.');
    await screen.findByText('No claims in this view.');
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByText('Cannot reach the ClaimFlow API. Check that it is running, then try again.');
  });
});
