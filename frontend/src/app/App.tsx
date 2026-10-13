import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { onUnauthorized, setApiKey } from '../api/client';
import { getMe } from '../api/endpoints';
import { appsForRole, formatRoute, resolveRoute, type AppId, type Route } from '../domain/routes';
import { decodeSession, encodeSession, isMe, type DeviceSession } from '../domain/session';
import { SignIn } from '../features/auth/SignIn';
import { BackOffice } from '../features/backoffice/BackOffice';
import { go } from '../features/common/nav';
import { DevTools } from '../features/devtools/DevTools';
import { KitchenDisplay } from '../features/kitchen/KitchenDisplay';
import { PosApp } from '../features/pos/PosApp';
import { t, type MessageKey } from '../shared/i18n';
import {
  ReachabilityContext,
  useReachabilityPoller,
  type Reachability,
} from '../shared/reachability';
import { SessionContext } from '../shared/session';
import { loadRaw, removeKey, saveString, STORAGE_KEYS } from '../shared/storage';
import type { Tone } from '../shared/tone';
import { Badge } from '../shared/ui';
import { ErrorBoundary } from './ErrorBoundary';

const APP_LABEL: Record<AppId, MessageKey> = {
  pos: 'app.pos',
  kitchen: 'app.kitchen',
  backoffice: 'app.backoffice',
  devtools: 'app.devtools',
};

const REACH: Record<Reachability, { tone: Tone; label: MessageKey }> = {
  up: { tone: 'ok', label: 'reach.up' },
  down: { tone: 'bad', label: 'reach.down' },
  unreachable: { tone: 'bad', label: 'reach.unreachable' },
  unknown: { tone: 'muted', label: 'reach.unknown' },
};

function loadSession(): DeviceSession | null {
  const s = decodeSession(loadRaw(STORAGE_KEYS.session));
  // Set synchronously so that the first requests of child components are authenticated.
  setApiKey(s?.apiKey ?? null);
  return s;
}

export function App() {
  return (
    <ErrorBoundary>
      <Root />
    </ErrorBoundary>
  );
}

function Root() {
  const [session, setSession] = useState<DeviceSession | null>(loadSession);
  const [signedOutReason, setSignedOutReason] = useState<string | null>(null);
  const sessionRef = useRef(session);
  sessionRef.current = session;

  const signIn = useCallback((s: DeviceSession) => {
    setApiKey(s.apiKey);
    saveString(STORAGE_KEYS.session, encodeSession(s));
    setSignedOutReason(null);
    setSession(s);
  }, []);

  const signOut = useCallback((reason: string | null = null) => {
    setApiKey(null);
    removeKey(STORAGE_KEYS.session);
    setSignedOutReason(reason);
    setSession(null);
  }, []);

  // A 401 anywhere means the key is unknown or was revoked: back to sign-in.
  useEffect(
    () =>
      onUnauthorized(() => {
        if (sessionRef.current) signOut(t('signin.expired'));
      }),
    [signOut],
  );

  // Re-validate the stored identity in the background (role may have changed). Offline-first:
  // when the node is unreachable the stored identity keeps working.
  const apiKey = session?.apiKey ?? null;
  useEffect(() => {
    if (!apiKey) return;
    let cancelled = false;
    void getMe().then((r) => {
      if (cancelled || !r.ok || !isMe(r.data)) return;
      const next: DeviceSession = { apiKey, me: r.data, verifiedAt: Date.now() };
      saveString(STORAGE_KEYS.session, encodeSession(next));
      setSession(next);
    });
    return () => {
      cancelled = true;
    };
  }, [apiKey]);

  if (!session) return <SignIn onSignedIn={signIn} reason={signedOutReason} />;
  return <Shell session={session} onSignOut={() => signOut(null)} />;
}

function useHash(): string {
  const [hash, setHash] = useState(() => globalThis.location?.hash ?? '');
  useEffect(() => {
    const onHash = () => setHash(globalThis.location.hash);
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  return hash;
}

function Shell({ session, onSignOut }: { session: DeviceSession; onSignOut: () => void }) {
  const { me } = session;
  const reach = useReachabilityPoller();
  const hash = useHash();
  const route = resolveRoute(hash, me.role);
  const apps = appsForRole(me.role);
  const mainRef = useRef<HTMLElement>(null);
  const sessionValue = useMemo(() => ({ me, signOut: onSignOut }), [me, onSignOut]);

  // Keep the URL in sync with the route actually shown (e.g. a POS device opening #/backoffice).
  const canonical = route ? formatRoute(route) : null;
  useEffect(() => {
    if (canonical && globalThis.location.hash !== canonical) {
      globalThis.history?.replaceState(null, '', canonical);
    }
  }, [canonical]);

  // Focus management: move focus to the main region when the screen changes.
  const screenKey = route ? `${route.app}/${route.app === 'backoffice' ? route.section : ''}` : '';
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    mainRef.current?.focus();
  }, [screenKey]);

  const r = REACH[reach.state];

  return (
    <SessionContext.Provider value={sessionValue}>
      <ReachabilityContext.Provider value={reach}>
        <a className="skip-link" href="#main">
          {t('app.skip')}
        </a>
        <div className={`app app-${route?.app ?? 'none'}`}>
          <header className="app-header no-print">
            <div className="brand">
              <strong>{t('app.brand')}</strong>
              <span className="muted small">
                {me.name} · <code>{me.terminalId}</code> · {me.branch.code}
              </span>
            </div>
            {apps.length > 1 ? (
              <nav aria-label={t('app.nav')} className="tabs">
                {apps.map((a) => (
                  <a
                    key={a}
                    href={`#/${a}`}
                    className={`tab ${route?.app === a ? 'is-active' : ''}`}
                    aria-current={route?.app === a ? 'page' : undefined}
                    onClick={(e) => {
                      e.preventDefault();
                      go(a === 'backoffice' ? { app: a, section: 'orders', id: null } : { app: a });
                    }}
                  >
                    {t(APP_LABEL[a])}
                  </a>
                ))}
              </nav>
            ) : (
              <div className="tabs" />
            )}
            <div className="header-status">
              <Badge tone="info">{t(`role.${me.role}`)}</Badge>
              <span aria-live="polite" title={t('reach.title')}>
                <Badge tone={r.tone}>{t(r.label)}</Badge>
              </span>
              <button type="button" onClick={onSignOut}>
                {t('app.signOut')}
              </button>
            </div>
          </header>
          {reach.state === 'unreachable' || reach.state === 'down' ? (
            <div className="banner no-print" role="alert">
              {reach.state === 'down' ? t('app.nodeDown') : t('app.nodeUnreachable')}
            </div>
          ) : null}
          <main id="main" ref={mainRef} tabIndex={-1} className="app-main">
            {route ? <Screen route={route} /> : <NoUi />}
          </main>
        </div>
      </ReachabilityContext.Provider>
    </SessionContext.Provider>
  );
}

function Screen({ route }: { route: Route }) {
  switch (route.app) {
    case 'pos':
      return <PosApp />;
    case 'kitchen':
      return <KitchenDisplay />;
    case 'backoffice':
      return <BackOffice section={route.section} id={route.id} />;
    case 'devtools':
      return <DevTools />;
  }
}

function NoUi() {
  return (
    <div className="panel panel-body no-ui" role="alert">
      <h1>{t('app.noUiTitle')}</h1>
      <p>{t('app.noUiBody')}</p>
    </div>
  );
}
