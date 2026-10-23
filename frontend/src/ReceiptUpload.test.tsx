import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from './App';
import { DEFAULT_IMAGE_LIMITS, validateReceiptImage } from './api';

// Every image here is a few authored bytes with a PNG or JPEG signature. No OCR runs anywhere in
// these tests; server replies are mocked to check how the console reacts to each status.
const PNG_BYTES = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01];
const JPEG_BYTES = [0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10];

function file(bytes: number[], name: string, type: string) {
  return new File([new Uint8Array(bytes)], name, { type });
}

function reply(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

class FakeXhr {
  static sent: FakeXhr[] = [];
  method = '';
  url = '';
  headers: Record<string, string> = {};
  body: FormData | null = null;
  status = 0;
  responseText = '';
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } =
    { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  open(method: string, url: string) { this.method = method; this.url = url; }
  setRequestHeader(name: string, value: string) { this.headers[name] = value; }
  send(body: FormData) { this.body = body; FakeXhr.sent.push(this); }
  progress(loaded: number, total: number) { this.upload.onprogress?.({ lengthComputable: true, loaded, total }); }
  finish(status: number, body: unknown) { this.status = status; this.responseText = JSON.stringify(body); this.onload?.(); }
}

beforeEach(() => {
  FakeXhr.sent = [];
  vi.stubGlobal('XMLHttpRequest', FakeXhr);
  vi.stubGlobal('crypto', { randomUUID: () => 'upload-key' });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('validateReceiptImage', () => {
  const limits = { ...DEFAULT_IMAGE_LIMITS, maxBytes: 16 };

  it('accepts PNG and JPEG bytes, including a file with no declared type', async () => {
    expect(await validateReceiptImage(file(PNG_BYTES, 'r.png', 'image/png'), limits)).toBeNull();
    expect(await validateReceiptImage(file(JPEG_BYTES, 'r.jpg', 'image/jpeg'), limits)).toBeNull();
    expect(await validateReceiptImage(file(JPEG_BYTES, 'r.jpg', 'image/jpg'), limits)).toBeNull();
    expect(await validateReceiptImage(file(PNG_BYTES, 'receipt', ''), limits)).toBeNull();
  });

  it('rejects empty, oversized, wrong-type, non-image and mislabelled files', async () => {
    expect(await validateReceiptImage(file([], 'r.png', 'image/png'), limits)).toBe('The receipt image is empty.');
    expect(await validateReceiptImage(file(new Array(17).fill(0), 'r.png', 'image/png'), limits))
      .toBe('The receipt image is 17 B; the limit is 16 B.');
    expect(await validateReceiptImage(file(new Array(5 * 1024 * 1024 + 1).fill(0), 'r.png', 'image/png'),
      DEFAULT_IMAGE_LIMITS)).toBe('The receipt image is 5242881 bytes; the limit is 5242880 bytes.');
    expect(await validateReceiptImage(file([0x47, 0x49, 0x46], 'r.gif', 'image/gif'), limits))
      .toBe('Choose a PNG or JPEG image.');
    expect(await validateReceiptImage(file([0x4d, 0x65, 0x72], 'r.txt', 'text/plain'), limits))
      .toBe('Choose a PNG or JPEG image.');
    expect(await validateReceiptImage(file([0x4d, 0x65, 0x72, 0x63], 'r.png', 'image/png'), limits))
      .toBe('This file is not a PNG or JPEG image.');
    expect(await validateReceiptImage(file(PNG_BYTES, 'r.jpg', 'image/jpeg'), limits))
      .toBe('The file type does not match its contents.');
  });
});

async function signIn(role: 'employee' | 'manager' | 'finance') {
  render(<App />);
  fireEvent.change(screen.getByLabelText('Role'), { target: { value: role } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-only' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByRole('button', { name: 'Sign out' });
}

function employeeServer(ocrConfigured: boolean, mine: () => unknown[]) {
  return vi.fn(async (url: string) => {
    if (url.endsWith('/api/auth/token')) return reply({ access_token: 'employee-token' });
    if (url.endsWith('/api/claims/image/limits'))
      return reply({ maxBytes: 64, mediaTypes: ['image/png', 'image/jpeg'], ocrConfigured });
    if (url.endsWith('/api/claims/mine')) return reply(mine());
    throw new Error(`Unexpected request ${url}`);
  });
}

describe('receipt image upload in the console', () => {
  it('rejects an invalid file on the client without sending a request', async () => {
    vi.stubGlobal('fetch', employeeServer(true, () => []));
    await signIn('employee');
    const input = await screen.findByLabelText('Receipt image (PNG or JPEG, up to 64 B)');
    fireEvent.change(input, { target: { files: [file([0x4d, 0x65, 0x72], 'notes.txt', 'text/plain')] } });
    expect(await screen.findByText('Choose a PNG or JPEG image.')).toBeTruthy();
    // An invalid choice resets the file input, so query the fresh element.
    fireEvent.change(screen.getByLabelText(/Receipt image/), { target: { files: [file(new Array(65).fill(0x89), 'big.png', 'image/png')] } });
    expect(await screen.findByText('The receipt image is 65 B; the limit is 64 B.')).toBeTruthy();
    expect(screen.queryByText(/Attached:/)).toBeNull();
    expect(FakeXhr.sent).toHaveLength(0);
  });

  it('uploads a valid image with progress and shows the queued image claim', async () => {
    const created = { id: 'image-claim-1', employeeId: 'employee', category: 'meal', amountCents: 1140,
      merchant: null, expenseDate: null, state: 'SUBMITTED', receiptSource: 'IMAGE' };
    let uploaded = false;
    vi.stubGlobal('fetch', employeeServer(true, () => (uploaded ? [created] : [])));
    await signIn('employee');
    await screen.findByText(/An attached image is sent instead of the transcription/);
    fireEvent.change(screen.getByLabelText('Amount (SGD)'), { target: { value: '11.40' } });
    fireEvent.change(screen.getByLabelText(/Receipt image/), {
      target: { files: [file(PNG_BYTES, 'lunch.png', 'image/png')] },
    });
    expect(await screen.findByText(/Attached: lunch\.png · 10 B/)).toBeTruthy();
    expect((screen.getByLabelText('Receipt transcription') as HTMLTextAreaElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }));
    await waitFor(() => expect(FakeXhr.sent).toHaveLength(1));
    const xhr = FakeXhr.sent[0];
    expect(xhr.method).toBe('POST');
    expect(xhr.url).toMatch(/\/api\/claims\/image$/);
    expect(xhr.headers).toMatchObject({ Authorization: 'Bearer employee-token', 'Idempotency-Key': 'upload-key' });
    expect(xhr.body?.get('category')).toBe('meal');
    expect(xhr.body?.get('amountCents')).toBe('1140');
    expect((xhr.body?.get('receipt') as File).name).toBe('lunch.png');
    act(() => xhr.progress(5, 10));
    expect(screen.getByText('Uploading 50%')).toBeTruthy();
    expect((screen.getByLabelText('Upload progress') as HTMLProgressElement).value).toBe(50);
    uploaded = true;
    await act(async () => xhr.finish(200, created));
    expect(await screen.findByText('Receipt image uploaded. Claim queued for receipt validation.')).toBeTruthy();
    expect(await screen.findByText('Image receipt')).toBeTruthy();
    expect(screen.queryByLabelText('Upload progress')).toBeNull();
    expect(screen.queryByText(/Attached:/)).toBeNull();
  });

  it('explains a 503 from a server without OCR and creates nothing', async () => {
    vi.stubGlobal('fetch', employeeServer(false, () => []));
    await signIn('employee');
    expect(await screen.findByText(/This server has no OCR engine configured/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Amount (SGD)'), { target: { value: '9.00' } });
    fireEvent.change(screen.getByLabelText(/Receipt image/), {
      target: { files: [file(JPEG_BYTES, 'taxi.jpg', 'image/jpeg')] },
    });
    await screen.findByText(/Attached: taxi\.jpg/);
    fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }));
    await waitFor(() => expect(FakeXhr.sent).toHaveLength(1));
    await act(async () => FakeXhr.sent[0].finish(503, { error: 'Image OCR is not configured on this server' }));
    expect(await screen.findByText(
      'Image OCR is not configured on this server. No claim was created; submit a typed transcription instead.',
    )).toBeTruthy();
    expect(screen.getByText('No claims in this view.')).toBeTruthy();
    expect(screen.getByText(/Attached: taxi\.jpg/)).toBeTruthy();
  });

  it('reports server-side size and network failures', async () => {
    vi.stubGlobal('fetch', employeeServer(true, () => []));
    await signIn('employee');
    fireEvent.change(screen.getByLabelText('Amount (SGD)'), { target: { value: '9.00' } });
    fireEvent.change(screen.getByLabelText(/Receipt image/), {
      target: { files: [file(PNG_BYTES, 'r.png', 'image/png')] },
    });
    await screen.findByText(/Attached: r\.png/);
    fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }));
    await waitFor(() => expect(FakeXhr.sent).toHaveLength(1));
    await act(async () => FakeXhr.sent[0].finish(413, { error: 'Receipt image is too large' }));
    expect(await screen.findByText('The server refused the image as too large. No claim was created.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }));
    await waitFor(() => expect(FakeXhr.sent).toHaveLength(2));
    await act(async () => FakeXhr.sent[1].onerror?.());
    expect(await screen.findByText('The upload could not reach the server. Check My claims before trying again.'))
      .toBeTruthy();
  });
});

describe('image receipt visibility by role', () => {
  const imageClaim = { id: 'image-claim-2', employeeId: 'employee', category: 'meal', amountCents: 1900,
    merchant: null, expenseDate: null, state: 'NEEDS_HUMAN_CHECK', receiptSource: 'IMAGE' };

  it('shows the manager the image source and that the image was not stored', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/api/auth/token')) return reply({ access_token: 'manager-token' });
      if (url.endsWith('/api/claims/queue')) return reply([]);
      if (url.endsWith('/api/claims/receipt-check')) return reply([imageClaim]);
      throw new Error(`Unexpected request ${url}`);
    }));
    await signIn('manager');
    expect(await screen.findByText('Image receipt')).toBeTruthy();
    expect(screen.getByText(/The uploaded image is not stored on the server/)).toBeTruthy();
    expect(screen.queryByLabelText(/Receipt image/)).toBeNull();
  });

  it('shows finance the image source on an approved claim', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/api/auth/token')) return reply({ access_token: 'finance-token' });
      if (url.endsWith('/api/claims/approved'))
        return reply([{ ...imageClaim, state: 'APPROVED', merchant: 'Synthetic Noodles', expenseDate: '2026-10-16' }]);
      if (url.endsWith('/api/claims/stats'))
        return reply({ claimCount: 1, totalCents: 1900, byCategoryCents: { meal: 1900 }, byStateCount: { APPROVED: 1 } });
      throw new Error(`Unexpected request ${url}`);
    }));
    await signIn('finance');
    expect(await screen.findByText('Image receipt')).toBeTruthy();
    expect(screen.queryByText(/not stored on the server/)).toBeNull();
  });
});
