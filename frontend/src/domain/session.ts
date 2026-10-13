// Paired-device session (API key + identity from GET /me), pure (de)serialisation.

import { ROLES, type Me, type Role } from '../api/types';

export interface DeviceSession {
  apiKey: string;
  me: Me;
  /** Epoch ms of the last successful GET /me. */
  verifiedAt: number;
}

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === 'string' && v.length > 0;

export function isRole(v: unknown): v is Role {
  return typeof v === 'string' && (ROLES as readonly string[]).includes(v);
}

/** Runtime check of a GET /me body (the identity decides what the UI shows). */
export function isMe(v: unknown): v is Me {
  if (!isObj(v) || !isStr(v.terminalId) || typeof v.name !== 'string' || !isRole(v.role))
    return false;
  const b = v.branch;
  return isObj(b) && isStr(b.code) && isStr(b.currency) && isStr(b.zone);
}

export function encodeSession(s: DeviceSession): string {
  return JSON.stringify(s);
}

export function decodeSession(raw: string | null): DeviceSession | null {
  if (!raw) return null;
  try {
    const v: unknown = JSON.parse(raw);
    if (!isObj(v) || !isStr(v.apiKey) || !isMe(v.me)) return null;
    return {
      apiKey: v.apiKey,
      me: v.me,
      verifiedAt: typeof v.verifiedAt === 'number' ? v.verifiedAt : 0,
    };
  } catch {
    return null;
  }
}

/** Normalise a typed/pasted key: trims whitespace and an optional "Bearer " prefix. */
export function normaliseApiKey(input: string): string {
  return input
    .trim()
    .replace(/^bearer\s+/i, '')
    .trim();
}
