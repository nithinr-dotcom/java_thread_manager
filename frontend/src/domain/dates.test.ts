import { describe, expect, it } from 'vitest';
import { businessDate, isBusinessDate } from './dates';

describe('businessDate', () => {
  const t = Date.parse('2026-10-14T02:30:00Z');
  it('uses the branch time zone', () => {
    expect(businessDate('UTC', t)).toBe('2026-10-14');
    expect(businessDate('America/New_York', t)).toBe('2026-10-13');
    expect(businessDate('Asia/Tokyo', t)).toBe('2026-10-14');
  });
  it('falls back to UTC for unknown zones', () => {
    expect(businessDate('Not/AZone', t)).toBe('2026-10-14');
  });
});

describe('isBusinessDate', () => {
  it('validates YYYY-MM-DD calendar dates', () => {
    expect(isBusinessDate('2026-10-13')).toBe(true);
    expect(isBusinessDate('2026-02-30')).toBe(false);
    expect(isBusinessDate('2026-1-3')).toBe(false);
    expect(isBusinessDate('')).toBe(false);
  });
});
