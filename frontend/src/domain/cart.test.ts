import { describe, expect, it } from 'vitest';
import {
  addItem,
  clampQty,
  emptyCart,
  fingerprint,
  removeItem,
  setQuantity,
  toCheckoutItems,
  unitCount,
} from './cart';

describe('cart', () => {
  it('adds and merges by sku, preserving insertion order', () => {
    let c = addItem(emptyCart, 'A');
    c = addItem(c, 'B', 2);
    c = addItem(c, 'A', 3);
    expect(c).toEqual([
      { sku: 'A', quantity: 4 },
      { sku: 'B', quantity: 2 },
    ]);
    expect(unitCount(c)).toBe(6);
  });

  it('does not mutate the input', () => {
    const c = addItem(emptyCart, 'A');
    addItem(c, 'A');
    expect(c).toEqual([{ sku: 'A', quantity: 1 }]);
  });

  it('clamps quantities to 1..999', () => {
    expect(clampQty(0)).toBe(1);
    expect(clampQty(-5)).toBe(1);
    expect(clampQty(1000)).toBe(999);
    expect(clampQty(2.7)).toBe(2);
    expect(clampQty(Number.NaN)).toBe(1);
    expect(addItem(addItem(emptyCart, 'A', 998), 'A', 5)).toEqual([{ sku: 'A', quantity: 999 }]);
    expect(addItem(emptyCart, 'A', 0)).toEqual([{ sku: 'A', quantity: 1 }]);
  });

  it('sets quantity (clamped) and ignores unknown skus', () => {
    const c = addItem(emptyCart, 'A');
    expect(setQuantity(c, 'A', 7)).toEqual([{ sku: 'A', quantity: 7 }]);
    expect(setQuantity(c, 'A', 0)).toEqual([{ sku: 'A', quantity: 1 }]);
    expect(setQuantity(c, 'Z', 7)).toEqual(c);
  });

  it('removes items', () => {
    const c = addItem(addItem(emptyCart, 'A'), 'B');
    expect(removeItem(c, 'A')).toEqual([{ sku: 'B', quantity: 1 }]);
    expect(removeItem(c, 'Z')).toEqual(c);
  });

  it('maps to checkout items and fingerprints order-insensitively', () => {
    const c1 = addItem(addItem(emptyCart, 'B', 2), 'A');
    const c2 = addItem(addItem(emptyCart, 'A'), 'B', 2);
    expect(toCheckoutItems(c1)).toEqual([
      { sku: 'B', quantity: 2 },
      { sku: 'A', quantity: 1 },
    ]);
    expect(fingerprint(c1)).toBe(fingerprint(c2));
    expect(fingerprint(c1)).not.toBe(fingerprint(setQuantity(c2, 'A', 2)));
  });
});
