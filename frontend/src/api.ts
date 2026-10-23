export type Role = 'employee' | 'manager' | 'finance';
export type ReceiptSource = 'NONE' | 'TEXT' | 'IMAGE';
export type ClaimState = 'DRAFT' | 'SUBMITTED' | 'NEEDS_HUMAN_CHECK' | 'EXTRACTED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'PAID';
export interface Claim {
  id: string;
  employeeId: string;
  category: string;
  amountCents: number;
  merchant: string | null;
  expenseDate: string | null;
  state: ClaimState;
  receiptSource?: ReceiptSource;
}
export interface ImageLimits { maxBytes: number; mediaTypes: string[]; ocrConfigured: boolean }
export interface Review { eligible: boolean; violations: string[]; citations: string[] }
export interface Answer { answer: string; citations: string[] }
export interface Stats { claimCount: number; totalCents: number; byCategoryCents: Record<string, number>; byStateCount: Record<string, number> }

const base = import.meta.env.VITE_API_URL || 'http://127.0.0.1:8080';

async function request<T>(path: string, token: string | null, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (options.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${base}${path}`, { ...options, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || data.detail || `Request failed (${response.status})`);
  return data as T;
}

export async function login(role: Role, password: string): Promise<string> {
  const result = await request<{ access_token: string }>('/api/auth/token', null, {
    method: 'POST', body: JSON.stringify({ username: role, password }),
  });
  return result.access_token;
}

export const claims = (token: string, path: string) => request<Claim[]>(`/api/claims/${path}`, token);
export const stats = (token: string) => request<Stats>('/api/claims/stats', token);
export const review = (token: string, id: string) => request<Review>(`/api/claims/${id}/review`, token);
export const submit = (token: string, category: string, amountCents: number, receiptText: string) =>
  request<Claim>(receiptText.trim() ? '/api/claims/async' : '/api/claims', token, {
    method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ category, amountCents, receiptText }),
  });
export const checkReceipt = (token: string, id: string, receiptText: string) =>
  request<Claim>(`/api/claims/${id}/receipt-check`, token, {
    method: 'POST', body: JSON.stringify({ receiptText }),
  });
export const decide = (token: string, id: string, approve: boolean) =>
  request<Claim>(`/api/claims/${id}/decision`, token, {
    method: 'POST', body: JSON.stringify({ approve }),
  });
export const markPaid = (token: string, id: string) =>
  request<Claim>(`/api/claims/${id}/paid`, token, { method: 'POST' });
export const answer = (token: string, question: string) =>
  request<Answer>(`/api/policy/answer?question=${encodeURIComponent(question)}`, token);

/** Used until the server's own limits arrive; matches the backend's 5 MiB default. */
export const DEFAULT_IMAGE_LIMITS: ImageLimits = {
  maxBytes: 5 * 1024 * 1024, mediaTypes: ['image/png', 'image/jpeg'], ocrConfigured: false,
};
export const imageLimits = (token: string) => request<ImageLimits>('/api/claims/image/limits', token);

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG = [0xff, 0xd8, 0xff];

function startsWith(bytes: Uint8Array, prefix: number[]) {
  return bytes.length >= prefix.length && prefix.every((value, index) => bytes[index] === value);
}

async function leadingBytes(file: Blob, count: number): Promise<Uint8Array> {
  const part = file.slice(0, count);
  if (typeof part.arrayBuffer === 'function') return new Uint8Array(await part.arrayBuffer());
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(part);
  });
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Mirrors the server's checks before any bytes are sent: non-empty, within the size limit, and
 * PNG or JPEG by both declared type and leading bytes. The server repeats every check.
 */
export async function validateReceiptImage(file: File, limits: ImageLimits): Promise<string | null> {
  if (file.size === 0) return 'The receipt image is empty.';
  if (file.size > limits.maxBytes) {
    const exact = formatBytes(file.size) === formatBytes(limits.maxBytes);
    const size = exact ? `${file.size} bytes` : formatBytes(file.size);
    const limit = exact ? `${limits.maxBytes} bytes` : formatBytes(limits.maxBytes);
    return `The receipt image is ${size}; the limit is ${limit}.`;
  }
  const declared = file.type.toLowerCase() === 'image/jpg' ? 'image/jpeg' : file.type.toLowerCase();
  if (declared && !limits.mediaTypes.includes(declared)) return 'Choose a PNG or JPEG image.';
  const bytes = await leadingBytes(file, PNG.length);
  const detected = startsWith(bytes, PNG) ? 'image/png' : startsWith(bytes, JPEG) ? 'image/jpeg' : null;
  if (!detected) return 'This file is not a PNG or JPEG image.';
  if (declared && declared !== detected) return 'The file type does not match its contents.';
  return null;
}

export class UploadError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

/**
 * Sends a receipt image to the existing OCR seam. XMLHttpRequest is used because fetch does not
 * report upload progress. The server creates no claim when OCR is unavailable (HTTP 503).
 */
export function submitImage(token: string, category: string, amountCents: number, file: File,
                            onProgress: (percent: number) => void): Promise<Claim> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${base}/api/claims/image`);
    xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    xhr.setRequestHeader('Idempotency-Key', crypto.randomUUID());
    xhr.upload.onprogress = event => {
      if (event.lengthComputable && event.total > 0) onProgress(Math.round((event.loaded * 100) / event.total));
    };
    xhr.onload = () => {
      let data: { error?: string } = {};
      try { data = JSON.parse(xhr.responseText); } catch { /* non-JSON error body */ }
      if (xhr.status >= 200 && xhr.status < 300) { onProgress(100); resolve(data as Claim); }
      else reject(new UploadError(data.error || `Upload failed (${xhr.status})`, xhr.status));
    };
    xhr.onerror = () => reject(new UploadError('The upload could not reach the server.', 0));
    xhr.onabort = () => reject(new UploadError('The upload was cancelled.', 0));
    const form = new FormData();
    form.append('category', category);
    form.append('amountCents', String(amountCents));
    form.append('receipt', file, file.name);
    xhr.send(form);
  });
}
