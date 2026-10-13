import { replayedHeader, type ApiResult } from '../../api/client';
import { checkout, getCatalog, listOrders, restock } from '../../api/endpoints';
import type { Order } from '../../api/types';
import type { StressSample } from '../../domain/stress';

export function toSample(r: ApiResult<Order>): StressSample {
  if (r.ok) {
    return {
      status: r.status,
      orderId: r.data.id,
      orderNumber: r.data.orderNumber,
      replayed: replayedHeader(r.headers),
      elapsedMs: r.elapsedMs,
    };
  }
  return { status: r.status, code: r.problem.code, elapsedMs: r.elapsedMs };
}

export async function readStock(sku: string): Promise<number | null> {
  const r = await getCatalog();
  if (!r.ok) return null;
  return r.data.find((i) => i.sku === sku)?.onHand ?? null;
}

export async function latestOrderNumber(): Promise<string | null> {
  const r = await listOrders({ limit: 1 });
  return r.ok ? (r.data[0]?.orderNumber ?? null) : null;
}

export interface Batch {
  samples: StressSample[];
  elapsedMs: number;
}

/** Fire all requests at once (Promise.all after building every request). */
export async function fireBatch(
  n: number,
  make: (i: number) => Promise<ApiResult<Order>>,
): Promise<Batch> {
  const started = performance.now();
  const results = await Promise.all(Array.from({ length: n }, (_, i) => make(i)));
  return { samples: results.map(toSample), elapsedMs: performance.now() - started };
}

export { checkout, restock };
