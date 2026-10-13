/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Demo mode only: API key the UI signs in with automatically (see shared/demo.ts). */
  readonly VITE_DEMO_API_KEY?: string;
}
