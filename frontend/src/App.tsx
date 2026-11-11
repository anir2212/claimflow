import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { answer, ApiError, checkReceipt, claims, decide, DEFAULT_IMAGE_LIMITS, formatBytes, imageLimits, login, markPaid, review, stats as loadStats, submit, submitImage, UploadError, validateReceiptImage, type Answer, type Claim, type ImageLimits, type ReceiptSource, type Review, type Role, type Stats } from './api';

function money(cents: number) { return `SGD ${(cents / 100).toFixed(2)}`; }

const SOURCE_LABEL: Record<ReceiptSource, string> = { NONE: 'No receipt', TEXT: 'Typed receipt', IMAGE: 'Image receipt' };

const VIEW_TITLE: Record<Role, string> = { employee: 'My claims', manager: 'Approval queue', finance: 'Ready to pay' };

/** Turns an API failure into a sentence that says what happened and what to do next. */
function apiMessage(err: unknown) {
  if (err instanceof ApiError) {
    if (err.status === 401) return 'Your session has ended. Sign out and sign in again.';
    if (err.status === 409 && /illegal transition|changed by another/i.test(err.message)) {
      return 'This claim was already changed in another session. The list has been refreshed.';
    }
  }
  return (err as Error).message;
}

function uploadMessage(err: unknown) {
  if (!(err instanceof UploadError)) return (err as Error).message;
  if (err.status === 503) return `${err.message}. No claim was created; submit a typed transcription instead.`;
  if (err.status === 413) return 'The server refused the image as too large. No claim was created.';
  if (err.status === 0) return `${err.message} Check My claims before trying again.`;
  return `${err.message}. No claim was created.`;
}

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
  // The control an error belongs to: 'password', 'amount', 'receipt', 'image' or a correction's claim ID.
  const [errorField, setErrorField] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [limits, setLimits] = useState<ImageLimits>(DEFAULT_IMAGE_LIMITS);
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [fileInputKey, setFileInputKey] = useState(0);
  const [uploadPercent, setUploadPercent] = useState<number | null>(null);

  const headingRef = useRef<HTMLHeadingElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const amountRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const previousRole = useRef<Role | null>(null);
  const focusWasInList = useRef(false);
  const refocusImageInput = useRef(false);

  function clearMessages() { setError(''); setErrorField(null); setNotice(''); }
  function showError(message: string, field: string | null = null) { setNotice(''); setError(message); setErrorField(field); }
  const describedBy = (field: string, ...others: string[]) =>
    [...(errorField === field ? ['console-error'] : []), ...others].join(' ') || undefined;

  const refresh = useCallback(async (): Promise<number | null> => {
    if (!role || !token) return null;
    const path = role === 'employee' ? 'mine' : role === 'manager' ? 'queue' : 'approved';
    try {
      const [nextRows, nextStats, manualRows] = await Promise.all([
        claims(token, path), role === 'finance' ? loadStats(token) : Promise.resolve(null),
        role === 'manager' ? claims(token, 'receipt-check') : Promise.resolve([]),
      ]);
      focusWasInList.current = focusWasInList.current || !!listRef.current?.contains(document.activeElement);
      setRows([...manualRows, ...nextRows]);
      setStats(nextStats);
      return manualRows.length + nextRows.length;
    }
    catch (err) { setNotice(''); setError(apiMessage(err)); setErrorField(null); return null; }
  }, [role, token]);

  useEffect(() => {
    if (!role || !token) return;
    void refresh();
    const timer = window.setInterval(() => void refresh(), 10000);
    return () => window.clearInterval(timer);
  }, [role, token, refresh]);

  useEffect(() => {
    if (role !== 'employee' || !token) return;
    let current = true;
    imageLimits(token).then(next => { if (current) setLimits(next); })
      .catch(() => { if (current) setLimits(DEFAULT_IMAGE_LIMITS); });
    return () => { current = false; };
  }, [role, token]);

  // Signing in or out replaces the page, so move focus to the new heading and name the view.
  useEffect(() => {
    document.title = role ? `${VIEW_TITLE[role]} · ClaimFlow` : 'Sign in · ClaimFlow';
    if (role !== previousRole.current) { previousRole.current = role; headingRef.current?.focus(); }
  }, [role]);

  // A decided or paid claim leaves the list. Its button goes too, so give focus somewhere to land.
  useEffect(() => {
    if (focusWasInList.current && document.activeElement === document.body) headingRef.current?.focus();
    focusWasInList.current = false;
  }, [rows]);

  // Clearing the image remounts the file input, which would otherwise drop keyboard focus.
  useEffect(() => {
    if (refocusImageInput.current) { refocusImageInput.current = false; imageInputRef.current?.focus(); }
  }, [fileInputKey]);

  function clearImage(keepFocus = false) {
    refocusImageInput.current = keepFocus;
    setImageFile(null); setFileInputKey(key => key + 1);
  }

  async function chooseImage(file: File | undefined) {
    clearMessages();
    if (!file) { clearImage(true); return; }
    const problem = await validateReceiptImage(file, limits);
    if (problem) { clearImage(true); showError(problem, 'image'); return; }
    setImageFile(file);
    setNotice('Image attached. It will be sent instead of the transcription.');
  }

  async function act(task: () => Promise<unknown>, success: string, onFailure?: { field: string; focus?: () => void }) {
    setBusy(true); clearMessages();
    try { await task(); setNotice(success); await refresh(); }
    catch (err) {
      showError(apiMessage(err), onFailure?.field ?? null);
      onFailure?.focus?.();
      if (err instanceof ApiError && err.status === 409) await refresh();
    }
    finally { setBusy(false); }
  }

  async function signIn(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); clearMessages();
    try { const next = await login(selectedRole, password); setToken(next); setRole(selectedRole); setPassword(''); }
    catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        showError('That password is not right for this role. Check it and try again.', 'password');
        passwordRef.current?.focus();
      } else showError(apiMessage(err));
    }
    finally { setBusy(false); }
  }

  function signOut() {
    setRole(null); setToken(''); setRows([]); setReply(null); setReviews({}); setCorrections({});
    clearImage(); setLimits(DEFAULT_IMAGE_LIMITS); clearMessages();
  }

  async function sendClaim(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const cents = Math.round(Number(amount) * 100);
    if (!Number.isFinite(cents) || cents <= 0) { showError('Enter a positive amount.', 'amount'); amountRef.current?.focus(); return; }
    if (imageFile) {
      setBusy(true); clearMessages(); setUploadPercent(0); setNotice('Uploading the receipt image.');
      try {
        await submitImage(token, category, cents, imageFile, setUploadPercent);
        clearImage();
        setNotice('Receipt image uploaded. Claim queued for receipt validation.');
        await refresh();
      }
      catch (err) { showError(uploadMessage(err), 'image'); }
      finally { setBusy(false); setUploadPercent(null); }
      return;
    }
    await act(() => submit(token, category, cents, receiptText),
      receiptText.trim() ? 'Claim queued for receipt validation.' : 'Claim submitted for review.',
      receiptText.trim() ? { field: 'receipt' } : undefined);
  }

  async function askPolicy(event: FormEvent) {
    event.preventDefault(); clearMessages();
    try { setReply(await answer(token, question)); }
    catch (err) { showError(apiMessage(err)); }
  }

  async function refreshList() {
    clearMessages();
    const count = await refresh();
    if (count !== null) setNotice(`List refreshed: ${count} ${count === 1 ? 'claim' : 'claims'} in this view.`);
  }

  async function reviewClaim(id: string) {
    try { const result = await review(token, id); setReviews(current => ({ ...current, [id]: result })); }
    catch (err) { showError(apiMessage(err)); }
  }

  /** Keeps a busy button focusable: `disabled` would throw keyboard focus back to the page. */
  const whenIdle = (task: () => void) => () => { if (!busy) task(); };

  return <div className="shell">
    <header className="masthead">
      <div><span className="eyebrow">EXPENSE WORKFLOW</span><h1>ClaimFlow</h1><p>Traceable claims, grounded policy checks, clear decisions.</p></div>
      {role && <button className="quiet" onClick={signOut}>Sign out</button>}
    </header>
    {/* Always in the page: a live region inserted together with its message is often not announced. */}
    <div className="messages">
      <p id="console-error" role="alert" className="message error">{error}</p>
      <p role="status" className="message success">{notice}</p>
    </div>
    {!role ? <main className="login-card">
      <h2 ref={headingRef} tabIndex={-1}>Open local demo</h2>
      <p>Choose a role and enter the local demo password.</p>
      <form onSubmit={signIn} className="stack">
        <label>Role<select value={selectedRole} onChange={e => setSelectedRole(e.target.value as Role)}><option value="employee">Employee</option><option value="manager">Manager</option><option value="finance">Finance</option></select></label>
        <label>Password<input ref={passwordRef} type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} aria-invalid={errorField === 'password' || undefined} aria-describedby={describedBy('password')} required /></label>
        <button aria-disabled={busy || undefined}>Sign in</button>
      </form>
    </main> :
      <div className="layout"><main className="main-column">
        <div className="section-heading">
          <div><span className="eyebrow">{role.toUpperCase()} VIEW</span><h2 ref={headingRef} tabIndex={-1}>{VIEW_TITLE[role]}</h2></div>
          <button className="quiet" onClick={() => void refreshList()}>Refresh</button>
        </div>
        {role === 'finance' && stats && <div className="card"><h3>Claim summary</h3><p><strong>{stats.claimCount}</strong> claims · <strong>{money(stats.totalCents)}</strong> submitted</p><div className="claim-meta">{Object.entries(stats.byCategoryCents).map(([name, cents]) => <span key={name}>{name}: {money(cents)}</span>)}</div></div>}
        {role === 'employee' && <form className="card stack" onSubmit={sendClaim}>
          <h3>New claim</h3>
          <p className="hint">Amount is required. A receipt is optional: type it, load a .txt file or attach an image.</p>
          <div className="two">
            <label>Category<select value={category} onChange={e => setCategory(e.target.value)}><option value="meal">Meal</option><option value="taxi">Taxi</option><option value="lodging">Lodging</option><option value="other">Other</option></select></label>
            <label>Amount (SGD)<input ref={amountRef} type="number" min="0.01" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} aria-invalid={errorField === 'amount' || undefined} aria-describedby={describedBy('amount')} required /></label>
          </div>
          <label>Receipt transcription<textarea rows={5} value={receiptText} disabled={imageFile !== null} onChange={e => setReceiptText(e.target.value)} placeholder={'Merchant: Example Cafe\nDate: 2026-10-01\nTotal: SGD 12.50'} aria-invalid={errorField === 'receipt' || undefined} aria-describedby={describedBy('receipt')} /></label>
          <label className="upload">Load a .txt receipt<input type="file" accept=".txt,text/plain" disabled={imageFile !== null} onChange={async e => { const file = e.target.files?.[0]; if (file) { setReceiptText(await file.text()); clearMessages(); setNotice(`Loaded ${file.name} into the transcription.`); } }} /></label>
          <label className="upload">Receipt image (PNG or JPEG, up to {formatBytes(limits.maxBytes)})<input key={fileInputKey} ref={imageInputRef} type="file" accept={limits.mediaTypes.join(',')} disabled={busy} onChange={e => void chooseImage(e.target.files?.[0])} aria-invalid={errorField === 'image' || undefined} aria-describedby={describedBy('image', 'image-hint')} /></label>
          {imageFile && <p className="attachment">Attached: {imageFile.name} · {formatBytes(imageFile.size)} <button type="button" className="quiet" aria-disabled={busy || undefined} onClick={whenIdle(() => { clearImage(true); clearMessages(); })}>Remove image</button></p>}
          {uploadPercent !== null && <div className="progress"><progress aria-label="Upload progress" max={100} value={uploadPercent} /><span>Uploading {uploadPercent}%</span></div>}
          {limits.ocrConfigured ? <p id="image-hint" className="hint">An attached image is sent instead of the transcription; its text goes through the same receipt checks.</p> : <p id="image-hint" className="hint warning">This server has no OCR engine configured. An image upload will be refused and no claim created; use a typed transcription.</p>}
          <button aria-disabled={busy || undefined}>Submit claim</button>
        </form>}
        <div className="claims" ref={listRef}>{rows.length === 0 ? <div className="card empty">No claims in this view.</div> : rows.map(claim => {
          const titleId = `claim-title-${claim.id}`;
          const fieldId = `claim-${claim.id}`;
          const correctionId = `correction-${claim.id}`;
          const context = <span className="sr-only"> claim: {claim.merchant || claim.category}, {money(claim.amountCents)}</span>;
          return <article className="card claim" key={claim.id} aria-labelledby={titleId}>
            <div className="claim-top">
              <div><strong id={titleId}>{claim.merchant || claim.category}</strong><span className="muted">{claim.id.slice(0, 8)} · {claim.employeeId}</span></div>
              <strong>{money(claim.amountCents)}</strong>
            </div>
            <div className="claim-meta">
              <span className={`badge ${claim.state.toLowerCase()}`}>{claim.state.replaceAll('_', ' ')}</span>
              <span>{claim.category}</span>
              <span>{claim.expenseDate || 'No receipt date'}</span>
              {claim.receiptSource && <span className="receipt-source">{SOURCE_LABEL[claim.receiptSource]}</span>}
            </div>
            {role === 'manager' && claim.state === 'NEEDS_HUMAN_CHECK' && <div className="stack">
              {claim.receiptSource === 'IMAGE' && <p className="hint">The uploaded image is not stored on the server. Ask the employee for the receipt before typing a correction.</p>}
              <label>Corrected receipt transcription<textarea id={correctionId} rows={4} value={corrections[claim.id] || ''} onChange={e => setCorrections({ ...corrections, [claim.id]: e.target.value })} aria-invalid={errorField === fieldId || undefined} aria-describedby={describedBy(fieldId, titleId)} /></label>
              <button aria-disabled={busy || !corrections[claim.id]?.trim() || undefined} onClick={whenIdle(() => { if (corrections[claim.id]?.trim()) void act(() => checkReceipt(token, claim.id, corrections[claim.id]), 'Receipt checked and claim sent to review.', { field: fieldId, focus: () => document.getElementById(correctionId)?.focus() }); })}>Complete receipt check{context}</button>
            </div>}
            {role === 'manager' && claim.state === 'IN_REVIEW' && <div className="actions">
              <button className="quiet" onClick={() => void reviewClaim(claim.id)}>Review policy{context}</button>
              <button aria-disabled={busy || undefined} onClick={whenIdle(() => void act(() => decide(token, claim.id, true), 'Claim approved.'))}>Approve{context}</button>
              <button className="danger" aria-disabled={busy || undefined} onClick={whenIdle(() => void act(() => decide(token, claim.id, false), 'Claim rejected.'))}>Reject{context}</button>
            </div>}
            {role === 'finance' && <div className="actions"><button aria-disabled={busy || undefined} onClick={whenIdle(() => void act(() => markPaid(token, claim.id), 'Claim marked paid.'))}>Mark paid{context}</button></div>}
            <div aria-live="polite">{reviews[claim.id] && <div className="review"><strong>{reviews[claim.id].eligible ? 'Eligible under hard rules' : 'Policy block'}</strong><p>{reviews[claim.id].violations.join('; ') || 'No hard-rule violation.'}</p><small>Clauses: {reviews[claim.id].citations.join(', ') || 'none'}</small></div>}</div>
          </article>;
        })}</div>
      </main><aside className="side-column" aria-label="Policy desk"><div className="card stack">
        <span className="eyebrow">POLICY DESK</span>
        <h3>Ask a policy question</h3>
        <form className="stack" onSubmit={askPolicy}>
          <label>Question<input value={question} onChange={e => setQuestion(e.target.value)} placeholder="Can I claim a taxi after 10 pm?" required /></label>
          <button className="secondary">Ask</button>
        </form>
        <div aria-live="polite">{reply && <div className="answer"><p>{reply.answer}</p><small>{reply.citations.length ? `Cited: ${reply.citations.join(', ')}` : 'No supporting clause'}</small></div>}</div>
        <p className="hint">Current answers use a four-clause keyword baseline.</p>
      </div></aside></div>}
    <footer>Local development demo · No real expense data</footer>
  </div>;
}
