export type Role = 'employee' | 'manager' | 'finance';
export type ClaimState = 'DRAFT' | 'SUBMITTED' | 'NEEDS_HUMAN_CHECK' | 'EXTRACTED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'PAID';
export interface Claim {
  id: string;
  employeeId: string;
  category: string;
  amountCents: number;
  merchant: string | null;
  expenseDate: string | null;
  state: ClaimState;
}
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
