// Kitchen display (KDS) rules, pure: allowed transitions, columns, age thresholds and the
// client-side ticket board fed by the SSE stream / polling.

import type { KitchenTicket, TicketStatus } from '../api/types';

export type BumpAction = 'start' | 'ready' | 'served' | 'recall';

export interface Transition {
  action: BumpAction;
  to: TicketStatus;
}

/** Allowed manual transitions per docs/API.md, in the order buttons are shown (primary first). */
const TRANSITIONS: Record<TicketStatus, readonly Transition[]> = {
  QUEUED: [
    { action: 'start', to: 'IN_PROGRESS' },
    { action: 'ready', to: 'READY' },
  ],
  IN_PROGRESS: [{ action: 'ready', to: 'READY' }],
  READY: [
    { action: 'served', to: 'SERVED' },
    { action: 'recall', to: 'IN_PROGRESS' },
  ],
  SERVED: [],
  CANCELLED: [],
};

export function allowedTransitions(from: TicketStatus): readonly Transition[] {
  return TRANSITIONS[from] ?? [];
}

export function canTransition(from: TicketStatus, to: TicketStatus): boolean {
  return allowedTransitions(from).some((t) => t.to === to);
}

export type Column = 'QUEUED' | 'IN_PROGRESS' | 'READY';
export const COLUMNS: readonly Column[] = ['QUEUED', 'IN_PROGRESS', 'READY'];

export function isActiveStatus(s: TicketStatus): boolean {
  return s === 'QUEUED' || s === 'IN_PROGRESS' || s === 'READY';
}

// ---------------------------------------------------------------- age

export const AGE_WARN_MS = 5 * 60_000;
export const AGE_BAD_MS = 10 * 60_000;

export type AgeLevel = 'ok' | 'warn' | 'bad';

/** Colour level for a ticket's age (> 5 min warn, > 10 min bad). Invalid dates count as ok. */
export function ageLevel(
  receivedAt: string,
  nowMs: number,
  warnMs = AGE_WARN_MS,
  badMs = AGE_BAD_MS,
): AgeLevel {
  const t = Date.parse(receivedAt);
  if (Number.isNaN(t)) return 'ok';
  const age = nowMs - t;
  if (age > badMs) return 'bad';
  if (age > warnMs) return 'warn';
  return 'ok';
}

// ---------------------------------------------------------------- board

/** How long a cancelled ticket stays visible (struck through) before it is removed. */
export const CANCELLED_LINGER_MS = 8000;

export interface BoardEntry {
  ticket: KitchenTicket;
  /** Set for CANCELLED tickets: when to drop them from the board. */
  removeAt: number | null;
  /** Column the ticket is shown in (a cancelled ticket stays where it was, struck through). */
  column: Column;
}

/** Tickets by id. Treat as immutable. */
export type Board = ReadonlyMap<string, BoardEntry>;

export const emptyBoard: Board = new Map();

const ts = (iso: string): number => {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? 0 : t;
};

export interface ApplyResult {
  board: Board;
  /** True when a ticket that was not on the board appeared in an active state. */
  isNew: boolean;
}

/**
 * Apply one ticket update (from the stream, a bump response or polling). Older updates (by
 * `updatedAt`) never overwrite newer ones. SERVED tickets leave the board; CANCELLED ones linger.
 */
export function applyTicket(board: Board, t: KitchenTicket, nowMs: number): ApplyResult {
  const prev = board.get(t.id);
  if (prev && ts(prev.ticket.updatedAt) > ts(t.updatedAt)) return { board, isNew: false };
  const next = new Map(board);
  if (t.status === 'SERVED') {
    next.delete(t.id);
    return { board: next, isNew: false };
  }
  if (t.status === 'CANCELLED') {
    if (!prev) return { board, isNew: false }; // never shown: nothing to strike through
    next.set(t.id, {
      ticket: t,
      removeAt: prev.removeAt ?? nowMs + CANCELLED_LINGER_MS,
      column: prev.column,
    });
    return { board: next, isNew: false };
  }
  next.set(t.id, { ticket: t, removeAt: null, column: t.status as Column });
  return { board: next, isNew: !prev };
}

/**
 * Reconcile with an authoritative list of *active* tickets (GET ?active=true): tickets missing
 * from the list are gone (served while we were disconnected) unless they are lingering
 * cancellations; listed tickets are applied with the usual "newer wins" rule.
 */
export function reconcile(board: Board, active: readonly KitchenTicket[], nowMs: number): Board {
  const ids = new Set(active.map((t) => t.id));
  let next: Board = new Map(
    [...board].filter(([id, e]) => ids.has(id) || e.ticket.status === 'CANCELLED'),
  );
  for (const t of active) next = applyTicket(next, t, nowMs).board;
  return next;
}

/** Drop lingering cancelled tickets whose time is up. Returns the same board if nothing changed. */
export function prune(board: Board, nowMs: number): Board {
  let changed = false;
  const next = new Map<string, BoardEntry>();
  for (const [id, e] of board) {
    if (e.removeAt !== null && e.removeAt <= nowMs) changed = true;
    else next.set(id, e);
  }
  return changed ? next : board;
}

/** Tickets of one column, oldest first (FIFO for the cooks). Cancelled ones stay in place. */
export function columnTickets(board: Board, column: Column): BoardEntry[] {
  return [...board.values()]
    .filter((e) => e.column === column)
    .sort((a, b) => ts(a.ticket.receivedAt) - ts(b.ticket.receivedAt));
}

/** Distinct preparation stations from the catalog (sorted), for the station filter. */
export function stationsOf(
  items: readonly { station: string; requiresPreparation: boolean }[],
): string[] {
  return [...new Set(items.filter((i) => i.requiresPreparation).map((i) => i.station))].sort();
}
