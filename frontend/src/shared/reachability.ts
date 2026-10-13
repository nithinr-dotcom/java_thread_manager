import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { isNetworkError } from '../api/client';
import { getHealth } from '../api/endpoints';
import { usePolling } from './usePolling';

/**
 * - `up`          — /actuator/health answered UP
 * - `down`        — the node answered but is not healthy (e.g. database down)
 * - `unreachable` — no answer at all (browser offline, node down, timeout)
 * - `unknown`     — not checked yet, or an unexpected answer
 */
export type Reachability = 'unknown' | 'up' | 'down' | 'unreachable';

export interface ReachabilityValue {
  state: Reachability;
  refresh: () => void;
}

export const ReachabilityContext = createContext<ReachabilityValue>({
  state: 'unknown',
  refresh: () => undefined,
});

export function useReachability(): ReachabilityValue {
  return useContext(ReachabilityContext);
}

/** True when state-changing actions should be disabled. */
export function isOffline(state: Reachability): boolean {
  return state === 'unreachable' || state === 'down';
}

/** App-wide poller of the public health endpoint + browser online/offline events. */
export function useReachabilityPoller(intervalMs = 5000): ReachabilityValue {
  const health = usePolling(
    useCallback(() => getHealth(), []),
    intervalMs,
  );
  const [online, setOnline] = useState(() => globalThis.navigator?.onLine ?? true);
  const { refresh } = health;
  useEffect(() => {
    const on = () => {
      setOnline(true);
      refresh();
    };
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, [refresh]);

  let state: Reachability = 'unknown';
  const r = health.result;
  if (!online) state = 'unreachable';
  else if (r) {
    if (r.ok) state = r.data?.status === 'UP' ? 'up' : 'down';
    else if (isNetworkError(r)) state = 'unreachable';
    else if (r.status === 503) state = 'down';
  }
  return { state, refresh };
}
