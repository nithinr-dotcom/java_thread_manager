// localStorage access that never throws (private mode, blocked storage, SSR/tests).
// All persistent client state goes through here; keys are listed in STORAGE_KEYS.

export const STORAGE_KEYS = {
  /** Paired device: API key + identity (domain/session.ts). */
  session: 'edge.session',
  /** Durable pending idempotent action per slot (domain/idempotency.ts). */
  pending: (slot: string) => `edge.pending.${slot}`,
  /** POS: id of the order being paid (resume after reload). */
  posActiveOrder: 'edge.pos.activeOrder',
  /** KDS: selected station filter. */
  kdsStation: 'edge.kds.station',
  /** KDS: new-ticket sound on/off. */
  kdsSound: 'edge.kds.sound',
} as const;

export function loadString(key: string, fallback: string): string {
  try {
    return globalThis.localStorage?.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

/** Raw value or null when absent / unavailable. */
export function loadRaw(key: string): string | null {
  try {
    return globalThis.localStorage?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

/** Returns false when the value could not be persisted (quota, blocked storage). */
export function saveString(key: string, value: string): boolean {
  try {
    if (!globalThis.localStorage) return false;
    globalThis.localStorage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

export function removeKey(key: string): void {
  try {
    globalThis.localStorage?.removeItem(key);
  } catch {
    /* ignore */
  }
}
