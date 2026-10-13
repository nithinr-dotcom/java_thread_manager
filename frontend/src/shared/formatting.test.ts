import { describe, expect, it } from 'vitest';
import { formatAge, formatMoney } from './formatting';

describe('formatMoney', () => {
  it('uses the API currency', () => {
    expect(formatMoney(27.39, 'USD', 'en-US')).toBe('$27.39');
    expect(formatMoney(5, 'EUR', 'en-US')).toBe('€5.00');
    expect(formatMoney(100, 'JPY', 'en-US')).toBe('¥100');
  });
  it('falls back for invalid currency codes', () => {
    expect(formatMoney(1.5, 'NOPE!', 'en-US')).toBe('1.5 NOPE!');
  });
});

describe('formatAge', () => {
  const base = Date.parse('2026-10-13T12:00:00Z');
  it('formats seconds, minutes and hours', () => {
    expect(formatAge('2026-10-13T11:59:55Z', base)).toBe('5s');
    expect(formatAge('2026-10-13T11:57:48Z', base)).toBe('2m 12s');
    expect(formatAge('2026-10-13T09:55:00Z', base)).toBe('2h 5m');
  });
  it('handles null, invalid and future timestamps', () => {
    expect(formatAge(null, base)).toBe('—');
    expect(formatAge('nope', base)).toBe('—');
    expect(formatAge('2026-10-13T12:00:10Z', base)).toBe('0s');
  });
});
