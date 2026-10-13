import { describe, expect, it } from 'vitest';
import { decodeSession, encodeSession, isMe, normaliseApiKey, type DeviceSession } from './session';

const me = {
  terminalId: 'pos-1',
  name: 'Front counter',
  role: 'POS' as const,
  branch: { code: 'HOSP01', currency: 'USD', zone: 'America/New_York' },
};

describe('session', () => {
  it('round-trips', () => {
    const s: DeviceSession = { apiKey: 'sf_x', me, verifiedAt: 5 };
    expect(decodeSession(encodeSession(s))).toEqual(s);
  });

  it('rejects malformed values', () => {
    expect(decodeSession(null)).toBeNull();
    expect(decodeSession('{')).toBeNull();
    expect(decodeSession(JSON.stringify({ apiKey: '', me }))).toBeNull();
    expect(decodeSession(JSON.stringify({ apiKey: 'k', me: { ...me, role: 'ADMIN' } }))).toBeNull();
    expect(decodeSession(JSON.stringify({ apiKey: 'k', me: { ...me, branch: null } }))).toBeNull();
    expect(decodeSession(JSON.stringify({ apiKey: 'k', me }))).toEqual({
      apiKey: 'k',
      me,
      verifiedAt: 0,
    });
  });

  it('validates /me bodies', () => {
    expect(isMe(me)).toBe(true);
    expect(isMe({ ...me, terminalId: '' })).toBe(false);
    expect(isMe('x')).toBe(false);
  });

  it('normalises pasted keys', () => {
    expect(normaliseApiKey('  sf_demo_pos \n')).toBe('sf_demo_pos');
    expect(normaliseApiKey('Bearer sf_demo_pos')).toBe('sf_demo_pos');
  });
});
