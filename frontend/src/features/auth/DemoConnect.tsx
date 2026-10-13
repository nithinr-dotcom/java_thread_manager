import { useEffect, useState } from 'react';
import { getMe } from '../../api/endpoints';
import { isMe, type DeviceSession } from '../../domain/session';
import { t } from '../../shared/i18n';
import { Notice } from '../../shared/ui';

const RETRY_MS = 2000;

/**
 * Demo mode: signs in with the configured key, retrying while the backend is still starting
 * (the dev script starts both at once and the backend takes longer).
 */
export function DemoConnect({
  apiKey,
  onSignedIn,
}: {
  apiKey: string;
  onSignedIn: (s: DeviceSession) => void;
}) {
  const [attempts, setAttempts] = useState(0);
  const [rejected, setRejected] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const attempt = async () => {
      const r = await getMe(apiKey);
      if (cancelled) return;
      if (r.ok && isMe(r.data)) {
        onSignedIn({ apiKey, me: r.data, verifiedAt: Date.now() });
        return;
      }
      if (r.status === 401) {
        setRejected(true);
        return;
      }
      setAttempts((n) => n + 1);
      timer = setTimeout(() => void attempt(), RETRY_MS);
    };
    void attempt();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [apiKey, onSignedIn]);

  return (
    <main className="signin" id="main">
      <div className="signin-card panel">
        <h1>{t('demo.title')}</h1>
        {rejected ? (
          <Notice tone="bad">{t('demo.rejected')}</Notice>
        ) : (
          <Notice tone="info">
            {attempts === 0 ? t('demo.connecting') : t('demo.waiting', { n: attempts })}
          </Notice>
        )}
      </div>
    </main>
  );
}
