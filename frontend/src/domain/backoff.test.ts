import { describe, expect, it } from 'vitest';
import { backoffDelay, RETRY_BACKOFF, STREAM_BACKOFF } from './backoff';

describe('backoffDelay', () => {
  it('doubles from the base and caps at max', () => {
    const p = { baseMs: 100, maxMs: 1000 };
    expect([1, 2, 3, 4, 5, 10].map((a) => backoffDelay(a, p))).toEqual([
      100, 200, 400, 800, 1000, 1000,
    ]);
  });

  it('treats attempts < 1 as the first attempt', () => {
    expect(backoffDelay(0, RETRY_BACKOFF)).toBe(1000);
    expect(backoffDelay(-3, RETRY_BACKOFF)).toBe(1000);
  });

  it('applies jitter within [d·(1−j), d]', () => {
    expect(backoffDelay(1, STREAM_BACKOFF, () => 0)).toBe(700);
    expect(backoffDelay(1, STREAM_BACKOFF, () => 1)).toBe(1000);
    expect(backoffDelay(10, STREAM_BACKOFF, () => 0.5)).toBe(25500);
  });

  it('supports a custom factor', () => {
    expect(backoffDelay(3, { baseMs: 10, maxMs: 1000, factor: 3 })).toBe(90);
  });
});
