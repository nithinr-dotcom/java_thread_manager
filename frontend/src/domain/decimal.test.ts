import { describe, expect, it } from 'vitest';
import { amountToInput, parseAmount, parseIntInRange } from './decimal';

describe('parseAmount', () => {
  it('parses dot and comma decimals within the currency scale', () => {
    expect(parseAmount('27.39', 2)).toEqual({ ok: true, value: 27.39 });
    expect(parseAmount(' 30 ', 2)).toEqual({ ok: true, value: 30 });
    expect(parseAmount('5,5', 2)).toEqual({ ok: true, value: 5.5 });
    expect(parseAmount('100', 0)).toEqual({ ok: true, value: 100 });
  });

  it('rejects bad input', () => {
    expect(parseAmount('', 2)).toEqual({ ok: false, error: 'EMPTY' });
    expect(parseAmount('abc', 2)).toEqual({ ok: false, error: 'FORMAT' });
    expect(parseAmount('1.2.3', 2)).toEqual({ ok: false, error: 'FORMAT' });
    expect(parseAmount('1e3', 2)).toEqual({ ok: false, error: 'FORMAT' });
    expect(parseAmount('-1', 2)).toEqual({ ok: false, error: 'NEGATIVE' });
    expect(parseAmount('1.234', 2)).toEqual({ ok: false, error: 'SCALE' });
    expect(parseAmount('1.5', 0)).toEqual({ ok: false, error: 'SCALE' });
    expect(parseAmount('0', 2, { positive: true })).toEqual({ ok: false, error: 'NOT_POSITIVE' });
    expect(parseAmount('0', 2)).toEqual({ ok: true, value: 0 });
  });
});

describe('amountToInput', () => {
  it('formats at the currency scale', () => {
    expect(amountToInput(27.39, 2)).toBe('27.39');
    expect(amountToInput(5, 2)).toBe('5.00');
    expect(amountToInput(100, 0)).toBe('100');
  });
});

describe('parseIntInRange', () => {
  it('accepts integers in range only', () => {
    expect(parseIntInRange('10', 1, 100)).toBe(10);
    expect(parseIntInRange('-3', -10, 10)).toBe(-3);
    expect(parseIntInRange('+3', -10, 10)).toBe(3);
    expect(parseIntInRange('0', 1, 10)).toBeNull();
    expect(parseIntInRange('1.5', 1, 10)).toBeNull();
    expect(parseIntInRange('', 1, 10)).toBeNull();
    expect(parseIntInRange('11', 1, 10)).toBeNull();
  });
});
