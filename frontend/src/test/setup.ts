import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { setApiKey } from '../api/client';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  setApiKey(null);
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
  if (globalThis.location) globalThis.history.replaceState(null, '', '/');
});
