import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiResult } from '../api/client';

export interface Polling<T> {
  result: ApiResult<T> | null;
  /** Last successful data (kept while a later poll fails). */
  data: T | null;
  lastUpdated: number | null;
  loading: boolean;
  refresh: () => void;
}

/**
 * Polls `fetcher` every `intervalMs` while not paused. The next poll is scheduled only after the
 * previous one finishes, so slow backends never cause overlapping requests.
 */
export function usePolling<T>(
  fetcher: () => Promise<ApiResult<T>>,
  intervalMs: number,
  paused = false,
): Polling<T> {
  const [result, setResult] = useState<ApiResult<T> | null>(null);
  const [data, setData] = useState<T | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const mounted = useRef(true);
  const [tick, setTick] = useState(0);

  const run = useCallback(async () => {
    setLoading(true);
    const r = await fetcherRef.current();
    if (!mounted.current) return;
    setResult(r);
    if (r.ok) {
      setData(r.data);
      setLastUpdated(Date.now());
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const loop = async () => {
      await run();
      if (!cancelled && !paused) timer = setTimeout(() => void loop(), intervalMs);
    };
    void loop();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [run, intervalMs, paused, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { result, data, lastUpdated, loading, refresh };
}
