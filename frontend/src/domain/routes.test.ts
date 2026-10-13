import { describe, expect, it } from 'vitest';
import { appsForRole, formatRoute, parseRoute, resolveRoute } from './routes';

describe('appsForRole', () => {
  it('maps roles to apps', () => {
    expect(appsForRole('POS')).toEqual(['pos']);
    expect(appsForRole('KITCHEN')).toEqual(['kitchen']);
    expect(appsForRole('MANAGER')).toEqual(['pos', 'kitchen', 'backoffice', 'devtools']);
    expect(appsForRole('HQ')).toEqual([]);
    expect(appsForRole('MONITOR')).toEqual([]);
  });
});

describe('parseRoute / formatRoute', () => {
  it('round-trips', () => {
    for (const h of [
      '#/pos',
      '#/kitchen',
      '#/devtools',
      '#/backoffice/orders',
      '#/backoffice/orders/0192-abc',
      '#/backoffice/reports',
    ]) {
      const r = parseRoute(h);
      expect(r).not.toBeNull();
      expect(formatRoute(r!)).toBe(h);
    }
  });

  it('accepts legacy and partial hashes', () => {
    expect(parseRoute('#pos')).toEqual({ app: 'pos' });
    expect(parseRoute('#/backoffice')).toEqual({ app: 'backoffice', section: 'orders', id: null });
    expect(parseRoute('#/backoffice/nope/1')).toEqual({
      app: 'backoffice',
      section: 'orders',
      id: null,
    });
    expect(parseRoute('')).toBeNull();
    expect(parseRoute('#/stress')).toBeNull();
  });

  it('encodes ids', () => {
    expect(formatRoute({ app: 'backoffice', section: 'orders', id: 'a/b' })).toBe(
      '#/backoffice/orders/a%2Fb',
    );
    expect(parseRoute('#/backoffice/orders/a%2Fb')).toMatchObject({ id: 'a/b' });
  });
});

describe('resolveRoute', () => {
  it('keeps allowed routes and falls back to the first allowed app', () => {
    expect(resolveRoute('#/kitchen', 'MANAGER')).toEqual({ app: 'kitchen' });
    expect(resolveRoute('#/backoffice/catalog', 'POS')).toEqual({ app: 'pos' });
    expect(resolveRoute('', 'KITCHEN')).toEqual({ app: 'kitchen' });
    expect(resolveRoute('#/pos', 'HQ')).toBeNull();
  });
});
