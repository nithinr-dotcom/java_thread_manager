// Role → apps mapping and hash routes, pure.
//
// Routes: #/pos, #/kitchen, #/backoffice/<section>[/<id>], #/devtools

import type { Role } from '../api/types';

export type AppId = 'pos' | 'kitchen' | 'backoffice' | 'devtools';
export const APP_IDS: readonly AppId[] = ['pos', 'kitchen', 'backoffice', 'devtools'];

export type BackOfficeSection =
  | 'orders'
  | 'catalog'
  | 'inventory'
  | 'devices'
  | 'operations'
  | 'reports';
export const BACK_OFFICE_SECTIONS: readonly BackOfficeSection[] = [
  'orders',
  'catalog',
  'inventory',
  'devices',
  'operations',
  'reports',
];

export type Route =
  | { app: 'pos' }
  | { app: 'kitchen' }
  | { app: 'devtools' }
  | { app: 'backoffice'; section: BackOfficeSection; id: string | null };

/** Which apps a device role may open. HQ and MONITOR are machine-to-machine roles: no UI. */
export function appsForRole(role: Role): readonly AppId[] {
  switch (role) {
    case 'POS':
      return ['pos'];
    case 'KITCHEN':
      return ['kitchen'];
    case 'MANAGER':
      return APP_IDS;
    default:
      return [];
  }
}

export function parseRoute(hash: string): Route | null {
  const parts = hash
    .replace(/^#\/?/, '')
    .split('/')
    .filter((p) => p !== '')
    .map((p) => {
      try {
        return decodeURIComponent(p);
      } catch {
        return p;
      }
    });
  const [app, section, id] = parts;
  switch (app) {
    case 'pos':
    case 'kitchen':
    case 'devtools':
      return { app };
    case 'backoffice': {
      const s = BACK_OFFICE_SECTIONS.find((x) => x === section) ?? 'orders';
      return { app: 'backoffice', section: s, id: s === section && id ? id : null };
    }
    default:
      return null;
  }
}

export function formatRoute(r: Route): string {
  if (r.app !== 'backoffice') return `#/${r.app}`;
  return `#/backoffice/${r.section}${r.id ? `/${encodeURIComponent(r.id)}` : ''}`;
}

/** The route to show for `hash` given the role: the requested one if allowed, else the default. */
export function resolveRoute(hash: string, role: Role): Route | null {
  const apps = appsForRole(role);
  const wanted = parseRoute(hash);
  if (wanted && apps.includes(wanted.app)) return wanted;
  const first = apps[0];
  if (!first) return null;
  return first === 'backoffice' ? { app: first, section: 'orders', id: null } : { app: first };
}
