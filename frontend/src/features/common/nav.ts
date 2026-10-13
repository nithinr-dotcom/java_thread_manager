import { formatRoute, type Route } from '../../domain/routes';

/** Navigate by hash (the app shell listens to `hashchange`). */
export function go(route: Route): void {
  const h = formatRoute(route);
  if (globalThis.location && globalThis.location.hash !== h) globalThis.location.hash = h;
}
