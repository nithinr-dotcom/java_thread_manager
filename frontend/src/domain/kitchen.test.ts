import { describe, expect, it } from 'vitest';
import type { KitchenTicket, TicketStatus } from '../api/types';
import {
  ageLevel,
  allowedTransitions,
  applyTicket,
  canTransition,
  CANCELLED_LINGER_MS,
  columnTickets,
  emptyBoard,
  prune,
  reconcile,
  stationsOf,
} from './kitchen';

const ticket = (
  id: string,
  status: TicketStatus,
  updatedAt = '2026-10-13T12:00:00Z',
  receivedAt = '2026-10-13T12:00:00Z',
): KitchenTicket => ({
  id,
  orderId: `o-${id}`,
  orderNumber: `ORD-${id}`,
  terminalId: 'pos-1',
  status,
  receivedAt,
  updatedAt,
  items: [{ sku: 'BURGER', name: 'Burger', quantity: 1, station: 'GRILL' }],
});

describe('transitions', () => {
  it('allows exactly the documented transitions', () => {
    const all: TicketStatus[] = ['QUEUED', 'IN_PROGRESS', 'READY', 'SERVED', 'CANCELLED'];
    const allowed = all.flatMap((from) =>
      all.filter((to) => canTransition(from, to)).map((to) => `${from}->${to}`),
    );
    expect(allowed.sort()).toEqual(
      [
        'QUEUED->IN_PROGRESS',
        'QUEUED->READY',
        'IN_PROGRESS->READY',
        'READY->SERVED',
        'READY->IN_PROGRESS',
      ].sort(),
    );
  });

  it('names the bump actions with the primary one first', () => {
    expect(allowedTransitions('QUEUED').map((t) => t.action)).toEqual(['start', 'ready']);
    expect(allowedTransitions('IN_PROGRESS').map((t) => t.action)).toEqual(['ready']);
    expect(allowedTransitions('READY').map((t) => t.action)).toEqual(['served', 'recall']);
    expect(allowedTransitions('SERVED')).toEqual([]);
  });
});

describe('ageLevel', () => {
  const now = Date.parse('2026-10-13T12:00:00Z');
  it('uses 5 / 10 minute thresholds', () => {
    expect(ageLevel('2026-10-13T11:56:00Z', now)).toBe('ok');
    expect(ageLevel('2026-10-13T11:55:00Z', now)).toBe('ok'); // exactly 5 min
    expect(ageLevel('2026-10-13T11:54:59Z', now)).toBe('warn');
    expect(ageLevel('2026-10-13T11:49:59Z', now)).toBe('bad');
    expect(ageLevel('garbage', now)).toBe('ok');
  });
});

describe('board', () => {
  const now = 1_000_000;

  it('adds new active tickets and flags them as new', () => {
    const r = applyTicket(emptyBoard, ticket('a', 'QUEUED'), now);
    expect(r.isNew).toBe(true);
    expect(r.board.get('a')?.ticket.status).toBe('QUEUED');
    expect(
      applyTicket(r.board, ticket('a', 'IN_PROGRESS', '2026-10-13T12:01:00Z'), now).isNew,
    ).toBe(false);
  });

  it('ignores stale updates', () => {
    let b = applyTicket(emptyBoard, ticket('a', 'READY', '2026-10-13T12:05:00Z'), now).board;
    b = applyTicket(b, ticket('a', 'QUEUED', '2026-10-13T12:00:00Z'), now).board;
    expect(b.get('a')?.ticket.status).toBe('READY');
  });

  it('removes served tickets and lets cancelled ones linger, then prunes them', () => {
    let b = applyTicket(emptyBoard, ticket('a', 'QUEUED'), now).board;
    b = applyTicket(b, ticket('b', 'QUEUED'), now).board;
    b = applyTicket(b, ticket('a', 'SERVED', '2026-10-13T12:02:00Z'), now).board;
    expect(b.has('a')).toBe(false);
    b = applyTicket(b, ticket('b', 'CANCELLED', '2026-10-13T12:02:00Z'), now).board;
    expect(b.get('b')?.removeAt).toBe(now + CANCELLED_LINGER_MS);
    expect(b.get('b')?.column).toBe('QUEUED');
    expect(prune(b, now + 1)).toBe(b);
    expect(prune(b, now + CANCELLED_LINGER_MS).has('b')).toBe(false);
  });

  it('does not add tickets that arrive already cancelled or served', () => {
    expect(applyTicket(emptyBoard, ticket('x', 'CANCELLED'), now).board.size).toBe(0);
    expect(applyTicket(emptyBoard, ticket('x', 'SERVED'), now).board.size).toBe(0);
  });

  it('reconciles with the active list, dropping vanished tickets but keeping lingering cancellations', () => {
    let b = applyTicket(emptyBoard, ticket('gone', 'READY'), now).board;
    b = applyTicket(b, ticket('c', 'QUEUED'), now).board;
    b = applyTicket(b, ticket('c', 'CANCELLED', '2026-10-13T12:03:00Z'), now).board;
    b = reconcile(b, [ticket('new', 'QUEUED')], now);
    expect([...b.keys()].sort()).toEqual(['c', 'new']);
  });

  it('groups tickets into columns, oldest first', () => {
    let b = emptyBoard;
    b = applyTicket(b, ticket('late', 'QUEUED', 'x', '2026-10-13T12:05:00Z'), now).board;
    b = applyTicket(b, ticket('early', 'QUEUED', 'x', '2026-10-13T12:01:00Z'), now).board;
    b = applyTicket(b, ticket('cooking', 'IN_PROGRESS'), now).board;
    expect(columnTickets(b, 'QUEUED').map((e) => e.ticket.id)).toEqual(['early', 'late']);
    expect(columnTickets(b, 'IN_PROGRESS').map((e) => e.ticket.id)).toEqual(['cooking']);
    expect(columnTickets(b, 'READY')).toEqual([]);
  });
});

describe('stationsOf', () => {
  it('lists distinct stations of prepared items', () => {
    expect(
      stationsOf([
        { station: 'GRILL', requiresPreparation: true },
        { station: 'BAR', requiresPreparation: true },
        { station: 'GRILL', requiresPreparation: true },
        { station: 'SHELF', requiresPreparation: false },
      ]),
    ).toEqual(['BAR', 'GRILL']);
  });
});
