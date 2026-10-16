import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { answer, checkReceipt, claims, decide, login, markPaid, review, stats as loadStats, submit, type Answer, type Claim, type Review, type Role, type Stats } from './api';

function money(cents: number) { return `SGD ${(cents / 100).toFixed(2)}`; }

export default function App() {
  const [selectedRole, setSelectedRole] = useState<Role>('employee');
  const [role, setRole] = useState<Role | null>(null);
  const [password, setPassword] = useState('');
  const [token, setToken] = useState('');
  const [rows, setRows] = useState<Claim[]>([]);
  const [stats, setStats] = useState<Stats | null>(null);
  const [category, setCategory] = useState('meal');
  const [amount, setAmount] = useState('');
  const [receiptText, setReceiptText] = useState('');
  const [question, setQuestion] = useState('');
  const [reply, setReply] = useState<Answer | null>(null);
  const [reviews, setReviews] = useState<Record<string, Review>>({});
  const [corrections, setCorrections] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!role || !token) return;
    const path = role === 'employee' ? 'mine' : role === 'manager' ? 'queue' : 'approved';
    try {
      const [nextRows, nextStats, manualRows] = await Promise.all([
        claims(token, path), role === 'finance' ? loadStats(token) : Promise.resolve(null),
        role === 'manager' ? claims(token, 'receipt-check') : Promise.resolve([]),
      ]);
      setRows([...manualRows, ...nextRows]);
      setStats(nextStats);
    }
    catch (err) { setError((err as Error).message); }
  }, [role, token]);

  useEffect(() => {
    if (!role || !token) return;
    void refresh();
    const timer = window.setInterval(() => void refresh(), 10000);
    return () => window.clearInterval(timer);
  }, [role, token, refresh]);

  async function act(task: () => Promise<unknown>, success: string) {
    setBusy(true); setError(''); setNotice('');
    try { await task(); setNotice(success); await refresh(); }
    catch (err) { setError((err as Error).message); }
    finally { setBusy(false); }
  }

  async function signIn(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try { const next = await login(selectedRole, password); setToken(next); setRole(selectedRole); setPassword(''); }
    catch (err) { setError((err as Error).message); }
    finally { setBusy(false); }
  }

  async function sendClaim(event: FormEvent) {
    event.preventDefault();
    const cents = Math.round(Number(amount) * 100);
    if (!Number.isFinite(cents) || cents <= 0) { setError('Enter a positive amount.'); return; }
    await act(() => submit(token, category, cents, receiptText),
      receiptText.trim() ? 'Claim queued for receipt validation.' : 'Claim submitted for review.');
  }

  async function askPolicy(event: FormEvent) {
    event.preventDefault(); setError('');
    try { setReply(await answer(token, question)); }
    catch (err) { setError((err as Error).message); }
  }

  return <div className="shell">
    <header className="masthead"><div><span className="eyebrow">EXPENSE WORKFLOW</span><h1>ClaimFlow</h1><p>Traceable claims, grounded policy checks, clear decisions.</p></div>{role && <button className="quiet" onClick={() => { setRole(null); setToken(''); setRows([]); setReply(null); }}>Sign out</button>}</header>
    {error && <p role="alert" className="message error">{error}</p>}
    {notice && <p role="status" className="message success">{notice}</p>}
    {!role ? <main className="login-card"><h2>Open local demo</h2><p>Choose a role and enter the local demo password.</p><form onSubmit={signIn} className="stack"><label>Role<select value={selectedRole} onChange={e => setSelectedRole(e.target.value as Role)}><option value="employee">Employee</option><option value="manager">Manager</option><option value="finance">Finance</option></select></label><label>Password<input type="password" value={password} onChange={e => setPassword(e.target.value)} required /></label><button disabled={busy}>Sign in</button></form></main> :
      <main className="layout"><section className="main-column">
        <div className="section-heading"><div><span className="eyebrow">{role.toUpperCase()} VIEW</span><h2>{role === 'employee' ? 'My claims' : role === 'manager' ? 'Approval queue' : 'Ready to pay'}</h2></div><button className="quiet" onClick={() => void refresh()}>Refresh</button></div>
        {role === 'finance' && stats && <div className="card"><h3>Claim summary</h3><p><strong>{stats.claimCount}</strong> claims · <strong>{money(stats.totalCents)}</strong> submitted</p><div className="claim-meta">{Object.entries(stats.byCategoryCents).map(([name, cents]) => <span key={name}>{name}: {money(cents)}</span>)}</div></div>}
        {role === 'employee' && <form className="card stack" onSubmit={sendClaim}><h3>New claim</h3><div className="two"><label>Category<select value={category} onChange={e => setCategory(e.target.value)}><option value="meal">Meal</option><option value="taxi">Taxi</option><option value="lodging">Lodging</option><option value="other">Other</option></select></label><label>Amount (SGD)<input type="number" min="0.01" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} required /></label></div><label>Receipt transcription<textarea rows={5} value={receiptText} onChange={e => setReceiptText(e.target.value)} placeholder={'Merchant: Example Cafe\nDate: 2026-10-01\nTotal: SGD 12.50'} /></label><label className="upload">Load a .txt receipt<input type="file" accept=".txt,text/plain" onChange={async e => { const file = e.target.files?.[0]; if (file) setReceiptText(await file.text()); }} /></label><p className="hint">Text baseline only. A scanned image needs the later OCR worker.</p><button disabled={busy}>Submit claim</button></form>}
        <div className="claims">{rows.length === 0 ? <div className="card empty">No claims in this view.</div> : rows.map(claim => <article className="card claim" key={claim.id}><div className="claim-top"><div><strong>{claim.merchant || claim.category}</strong><span className="muted">{claim.id.slice(0, 8)} · {claim.employeeId}</span></div><strong>{money(claim.amountCents)}</strong></div><div className="claim-meta"><span className={`badge ${claim.state.toLowerCase()}`}>{claim.state.replace('_', ' ')}</span><span>{claim.category}</span><span>{claim.expenseDate || 'No receipt date'}</span></div>{role === 'manager' && claim.state === 'NEEDS_HUMAN_CHECK' && <div className="stack"><label>Corrected receipt transcription<textarea rows={4} value={corrections[claim.id] || ''} onChange={e => setCorrections({ ...corrections, [claim.id]: e.target.value })} /></label><button disabled={busy || !corrections[claim.id]?.trim()} onClick={() => void act(() => checkReceipt(token, claim.id, corrections[claim.id]), 'Receipt checked and claim sent to review.')}>Complete receipt check</button></div>}{role === 'manager' && claim.state === 'IN_REVIEW' && <div className="actions"><button className="quiet" onClick={async () => { try { setReviews({ ...reviews, [claim.id]: await review(token, claim.id) }); } catch (err) { setError((err as Error).message); } }}>Review policy</button><button disabled={busy} onClick={() => void act(() => decide(token, claim.id, true), 'Claim approved.')}>Approve</button><button className="danger" disabled={busy} onClick={() => void act(() => decide(token, claim.id, false), 'Claim rejected.')}>Reject</button></div>}{role === 'finance' && <div className="actions"><button disabled={busy} onClick={() => void act(() => markPaid(token, claim.id), 'Claim marked paid.')}>Mark paid</button></div>}{reviews[claim.id] && <div className="review"><strong>{reviews[claim.id].eligible ? 'Eligible under hard rules' : 'Policy block'}</strong><p>{reviews[claim.id].violations.join('; ') || 'No hard-rule violation.'}</p><small>Clauses: {reviews[claim.id].citations.join(', ') || 'none'}</small></div>}</article>)}</div>
      </section><aside className="side-column"><div className="card stack"><span className="eyebrow">POLICY DESK</span><h3>Ask a policy question</h3><form className="stack" onSubmit={askPolicy}><label>Question<input value={question} onChange={e => setQuestion(e.target.value)} placeholder="Can I claim a taxi after 10 pm?" required /></label><button className="secondary">Ask</button></form>{reply && <div className="answer"><p>{reply.answer}</p><small>{reply.citations.length ? `Cited: ${reply.citations.join(', ')}` : 'No supporting clause'}</small></div>}<p className="hint">Current answers use a four-clause keyword baseline.</p></div></aside></main>}
    <footer>Local development demo · No real expense data</footer>
  </div>;
}
