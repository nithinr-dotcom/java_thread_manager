/**
 * Demo mode (set by `scripts/dev.sh`): the UI signs in with this key by itself instead of asking
 * for one, so a reviewer lands straight on the scenario tests. Never set in production builds.
 */
export function demoApiKey(): string | null {
  const k: unknown = import.meta.env.VITE_DEMO_API_KEY;
  return typeof k === 'string' && k.trim() !== '' ? k.trim() : null;
}
