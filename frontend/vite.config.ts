import type { ServerResponse } from 'node:http';
import type { ProxyOptions } from 'vite';
import { configDefaults, defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

/**
 * When the backend is down, Vite's proxy would answer a bare 500 — indistinguishable from a real
 * server error. Answer 502 with the `X-Proxy-Error` marker instead so the client reports
 * "backend unreachable" (see src/api/client.ts).
 *
 * The kitchen SSE stream (`/api/v1/kitchen/stream`) must not be buffered or compressed: we ask
 * the backend for an uncompressed body and disable proxy timeouts so the long-lived response is
 * piped through chunk by chunk.
 */
const proxyTo = (target: string): ProxyOptions => ({
  target,
  changeOrigin: true,
  timeout: 0,
  proxyTimeout: 0,
  configure: (proxy) => {
    proxy.on('proxyReq', (proxyReq, req) => {
      if (req.url?.startsWith('/api/v1/kitchen/stream')) {
        proxyReq.setHeader('Accept-Encoding', 'identity');
      }
    });
    proxy.on('proxyRes', (proxyRes, req, res) => {
      if (req.url?.startsWith('/api/v1/kitchen/stream')) {
        proxyRes.headers['cache-control'] = 'no-cache';
        proxyRes.headers['x-accel-buffering'] = 'no';
        (res as ServerResponse).flushHeaders?.();
      }
    });
    proxy.on('error', (err, _req, res) => {
      const r = res as ServerResponse;
      if (!('writeHead' in r) || r.headersSent || r.writableEnded) return;
      r.writeHead(502, { 'Content-Type': 'application/problem+json', 'X-Proxy-Error': '1' });
      r.end(
        JSON.stringify({
          title: 'Backend unreachable',
          status: 502,
          code: 'NETWORK_ERROR',
          detail: `${target}: ${err.message}`,
        }),
      );
    });
  },
});

const proxy = { '/api': proxyTo(backend), '/actuator': proxyTo(backend) };

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 4173, proxy },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    // Playwright specs live in e2e/ and run with `npm run e2e`, not with Vitest.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
