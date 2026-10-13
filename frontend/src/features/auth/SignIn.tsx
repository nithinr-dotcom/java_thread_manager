import { useEffect, useRef, useState, type FormEvent } from 'react';
import { getMe } from '../../api/endpoints';
import type { Problem } from '../../api/types';
import { isMe, normaliseApiKey, type DeviceSession } from '../../domain/session';
import { t } from '../../shared/i18n';
import { Notice, ProblemView } from '../../shared/ui';

/** Device pairing: the device's API key is validated with GET /me, then stored on the device. */
export function SignIn({
  onSignedIn,
  reason,
}: {
  onSignedIn: (s: DeviceSession) => void;
  /** Why we are here (e.g. the key was revoked), shown as a notice. */
  reason: string | null;
}) {
  const [key, setKey] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const apiKey = normaliseApiKey(key);
    if (!apiKey) return;
    setBusy(true);
    setProblem(null);
    const r = await getMe(apiKey);
    setBusy(false);
    if (!r.ok) {
      setProblem(r.problem);
      inputRef.current?.focus();
      return;
    }
    if (!isMe(r.data)) {
      setProblem({ status: r.status, title: t('signin.badResponse') });
      return;
    }
    onSignedIn({ apiKey, me: r.data, verifiedAt: Date.now() });
  };

  return (
    <main className="signin" id="main">
      <form
        className="signin-card panel"
        onSubmit={(e) => void submit(e)}
        aria-labelledby="signin-title"
      >
        <h1 id="signin-title">{t('signin.title')}</h1>
        <p className="muted">{t('signin.intro')}</p>
        {reason ? <Notice tone="warn">{reason}</Notice> : null}
        <div className="field">
          <label htmlFor="api-key">{t('signin.apiKey')}</label>
          <div className="inline signin-key">
            <input
              id="api-key"
              ref={inputRef}
              className="input-lg"
              type={show ? 'text' : 'password'}
              autoComplete="off"
              spellCheck={false}
              value={key}
              onChange={(e) => setKey(e.target.value)}
              aria-describedby="api-key-hint"
            />
            <button
              type="button"
              className="btn-lg"
              onClick={() => setShow((s) => !s)}
              aria-pressed={show}
            >
              {show ? t('signin.hide') : t('signin.show')}
            </button>
          </div>
          <span id="api-key-hint" className="muted small">
            {t('signin.hint')}
          </span>
        </div>
        {problem ? (
          problem.status === 401 ? (
            <div className="problem" role="alert">
              <strong>{t('signin.rejected')}</strong>
            </div>
          ) : (
            <ProblemView problem={problem} />
          )
        ) : null}
        <button
          type="submit"
          className="btn-primary btn-xl"
          disabled={busy || normaliseApiKey(key) === ''}
        >
          {busy ? t('signin.checking') : t('signin.submit')}
        </button>
        {import.meta.env.DEV ? <p className="hint">{t('signin.demoKeys')}</p> : null}
      </form>
    </main>
  );
}
