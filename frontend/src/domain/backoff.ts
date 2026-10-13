// Exponential backoff with optional jitter, pure (randomness is injected).

export interface BackoffPolicy {
  /** Delay before the first retry. */
  baseMs: number;
  /** Upper bound for any delay. */
  maxMs: number;
  /** Growth factor per attempt (default 2). */
  factor?: number;
  /** 0..1 — fraction of the delay that is randomised ("equal jitter"). Default 0. */
  jitter?: number;
}

export const STREAM_BACKOFF: BackoffPolicy = { baseMs: 1000, maxMs: 30_000, jitter: 0.3 };
export const RETRY_BACKOFF: BackoffPolicy = { baseMs: 1000, maxMs: 8000, jitter: 0 };

/**
 * Delay before retry number `attempt` (1-based: attempt 1 → baseMs, 2 → base·factor, …),
 * capped at maxMs. With jitter j the result is uniformly in [d·(1−j), d].
 */
export function backoffDelay(
  attempt: number,
  policy: BackoffPolicy,
  random: () => number = Math.random,
): number {
  const n = Math.max(1, Math.floor(attempt));
  const factor = policy.factor ?? 2;
  const raw = Math.min(policy.maxMs, policy.baseMs * factor ** (n - 1));
  const j = Math.min(1, Math.max(0, policy.jitter ?? 0));
  if (j === 0) return raw;
  const r = Math.min(1, Math.max(0, random()));
  return Math.round(raw * (1 - j) + raw * j * r);
}
