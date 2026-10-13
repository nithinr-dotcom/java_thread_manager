// Pure cart model. Quantities only — the client never computes money totals.

export const MIN_QTY = 1;
export const MAX_QTY = 999;

export interface CartLine {
  readonly sku: string;
  readonly quantity: number;
}

export type Cart = readonly CartLine[];

export const emptyCart: Cart = [];

/** Clamp to an integer in [MIN_QTY, MAX_QTY]; NaN/non-finite becomes MIN_QTY. */
export function clampQty(qty: number): number {
  if (!Number.isFinite(qty)) return MIN_QTY;
  return Math.min(MAX_QTY, Math.max(MIN_QTY, Math.trunc(qty)));
}

/** Add `qty` of `sku`, merging with an existing line (insertion order preserved). */
export function addItem(cart: Cart, sku: string, qty = 1): Cart {
  const existing = cart.find((l) => l.sku === sku);
  if (!existing) return [...cart, { sku, quantity: clampQty(qty) }];
  return cart.map((l) => (l.sku === sku ? { sku, quantity: clampQty(l.quantity + qty) } : l));
}

/** Set an absolute quantity (clamped). No-op if the sku is not in the cart. */
export function setQuantity(cart: Cart, sku: string, qty: number): Cart {
  return cart.map((l) => (l.sku === sku ? { sku, quantity: clampQty(qty) } : l));
}

export function removeItem(cart: Cart, sku: string): Cart {
  return cart.filter((l) => l.sku !== sku);
}

/** Total number of units (not money). */
export function unitCount(cart: Cart): number {
  return cart.reduce((n, l) => n + l.quantity, 0);
}

export function toCheckoutItems(cart: Cart): { sku: string; quantity: number }[] {
  return cart.map((l) => ({ sku: l.sku, quantity: l.quantity }));
}

/** Order-insensitive basket fingerprint, used to tell whether a retry carries the same basket. */
export function fingerprint(cart: Cart): string {
  return [...cart]
    .sort((a, b) => (a.sku < b.sku ? -1 : a.sku > b.sku ? 1 : 0))
    .map((l) => `${l.sku}x${l.quantity}`)
    .join(',');
}
