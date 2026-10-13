import { useCallback, useEffect, useRef, useState } from 'react';
import { listKitchenTickets } from '../../api/endpoints';
import { streamKitchen, type StreamStatus } from '../../api/stream';
import type { KitchenTicket } from '../../api/types';
import { applyTicket, emptyBoard, prune, reconcile, type Board } from '../../domain/kitchen';

/** After this many consecutive failed stream connections, fall back to polling. */
export const FALLBACK_AFTER_FAILURES = 3;
export const POLL_MS = 3000;

export type FeedMode = 'connecting' | 'live' | 'polling';

export interface KitchenFeed {
  board: Board;
  mode: FeedMode;
  stream: StreamStatus | null;
  /** Re-read the active tickets (after a 409, or manually). */
  resync: () => Promise<void>;
  /** Apply a ticket returned by a bump. */
  apply: (t: KitchenTicket) => void;
}

/**
 * Live kitchen board: SSE stream (fetch-based, with backoff) + a REST resync on every (re)connect;
 * falls back to polling `GET /kitchen/tickets?active=true` while the stream keeps failing.
 * `onNewTicket` fires for tickets that appear after the initial load.
 */
export function useKitchenFeed(station: string | null, onNewTicket: () => void): KitchenFeed {
  const [board, setBoard] = useState<Board>(emptyBoard);
  const [stream, setStream] = useState<StreamStatus | null>(null);
  const boardRef = useRef<Board>(emptyBoard);
  const loaded = useRef(false);
  const onNewRef = useRef(onNewTicket);
  onNewRef.current = onNewTicket;

  const commit = (b: Board) => {
    boardRef.current = b;
    setBoard(b);
  };

  const apply = useCallback((t: KitchenTicket) => {
    const r = applyTicket(boardRef.current, t, Date.now());
    if (r.board === boardRef.current) return;
    commit(r.board);
    if (r.isNew && loaded.current) onNewRef.current();
  }, []);

  const resync = useCallback(async () => {
    const r = await listKitchenTickets({ active: true, station, limit: 200 });
    if (!r.ok) return;
    const before = boardRef.current;
    const next = reconcile(before, r.data, Date.now());
    commit(next);
    if (loaded.current && [...next.keys()].some((id) => !before.has(id))) onNewRef.current();
    loaded.current = true;
  }, [station]);

  // Stream (re)connects whenever the station filter changes.
  useEffect(() => {
    const ac = new AbortController();
    loaded.current = false;
    commit(emptyBoard);
    setStream(null);
    void streamKitchen(station, apply, ac.signal, {
      onStatus: (s) => {
        if (ac.signal.aborted) return;
        setStream(s);
        if (s.state === 'open') void resync();
      },
    });
    return () => ac.abort();
  }, [station, apply, resync]);

  const degraded =
    stream !== null && stream.state !== 'open' && stream.failures >= FALLBACK_AFTER_FAILURES;

  // Polling fallback while the stream is degraded.
  useEffect(() => {
    if (!degraded) return;
    void resync();
    const id = setInterval(() => void resync(), POLL_MS);
    return () => clearInterval(id);
  }, [degraded, resync]);

  // Drop lingering cancelled tickets.
  useEffect(() => {
    const id = setInterval(() => {
      const next = prune(boardRef.current, Date.now());
      if (next !== boardRef.current) commit(next);
    }, 1000);
    return () => clearInterval(id);
  }, []);

  const mode: FeedMode = stream?.state === 'open' ? 'live' : degraded ? 'polling' : 'connecting';
  return { board, mode, stream, resync, apply };
}
