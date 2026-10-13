import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Me } from '../api/types';
import { encodeSession } from '../domain/session';
import { catalog, me } from '../test/fixtures';
import { fakeServer, json, problem } from '../test/fakeServer';
import { App } from './App';

const store = (identity: Me, apiKey = 'sf_stored') =>
  localStorage.setItem('edge.session', encodeSession({ apiKey, me: identity, verifiedAt: 1 }));

const baseRoutes = (identity: Me | null) =>
  fakeServer([
    [
      'GET',
      '/api/v1/me',
      (req) =>
        identity && req.headers.Authorization?.startsWith('Bearer sf_')
          ? json(200, identity)
          : problem(401, 'UNAUTHENTICATED'),
    ],
    ['GET', '/api/v1/catalog', () => json(200, catalog)],
    ['GET', '/api/v1/kitchen/tickets', () => json(200, [])],
    ['GET', '/api/v1/orders', () => json(200, [])],
  ]);

describe('App', () => {
  it('pairs a device with its API key and shows the apps of its role', async () => {
    const server = baseRoutes(me('POS'));
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    render(<App />);

    expect(screen.getByRole('heading', { name: 'Pair this device' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Device API key'), '  sf_demo_pos ');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('button', { name: 'Add Classic burger' })).toBeInTheDocument();
    const [meReq] = server.calls('GET', '/api/v1/me');
    expect(meReq?.headers.Authorization).toBe('Bearer sf_demo_pos');
    expect(JSON.parse(localStorage.getItem('edge.session') ?? '{}')).toMatchObject({
      apiKey: 'sf_demo_pos',
      me: { role: 'POS' },
    });
    // POS devices see only the POS: no app navigation.
    expect(screen.queryByRole('navigation', { name: 'Apps' })).not.toBeInTheDocument();

    // Later requests carry the key.
    const catalogReq = server.calls('GET', '/api/v1/catalog').at(-1);
    expect(catalogReq?.headers.Authorization).toBe('Bearer sf_demo_pos');

    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(screen.getByRole('heading', { name: 'Pair this device' })).toBeInTheDocument();
    expect(localStorage.getItem('edge.session')).toBeNull();
  });

  it('rejects an unknown key', async () => {
    vi.stubGlobal('fetch', baseRoutes(null).fetch);
    const user = userEvent.setup();
    render(<App />);
    await user.type(screen.getByLabelText('Device API key'), 'sf_wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText(/was not accepted/)).toBeInTheDocument();
    expect(localStorage.getItem('edge.session')).toBeNull();
  });

  it('gives MANAGER devices every app', async () => {
    store(me('MANAGER'));
    vi.stubGlobal('fetch', baseRoutes(me('MANAGER')).fetch);
    render(<App />);
    expect(await screen.findByText('Online')).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Apps' });
    for (const name of ['POS', 'Kitchen', 'Back office', 'Developer tools']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument();
    }
    expect(nav).toBeInTheDocument();
  });

  it('keeps KITCHEN devices on the kitchen display even when another route is requested', async () => {
    store(me('KITCHEN'));
    window.location.hash = '#/backoffice/devices';
    vi.stubGlobal('fetch', baseRoutes(me('KITCHEN')).fetch);
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Kitchen display' })).toBeInTheDocument();
    await waitFor(() => expect(window.location.hash).toBe('#/kitchen'));
  });

  it('tells MONITOR keys they are not a UI role', async () => {
    store(me('MONITOR'));
    vi.stubGlobal('fetch', baseRoutes(me('MONITOR')).fetch);
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Not a UI role' })).toBeInTheDocument();
    expect(await screen.findByText('Online')).toBeInTheDocument();
  });

  it('tells HQ keys they are not a UI role', async () => {
    store(me('HQ'));
    vi.stubGlobal('fetch', baseRoutes(me('HQ')).fetch);
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Not a UI role' })).toBeInTheDocument();
    expect(await screen.findByText('Online')).toBeInTheDocument();
  });

  it('goes back to sign-in when the stored key is revoked (401)', async () => {
    store(me('POS'), 'revoked');
    vi.stubGlobal('fetch', baseRoutes(me('POS')).fetch);
    render(<App />);
    expect(await screen.findByText(/no longer valid/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Pair this device' })).toBeInTheDocument();
    expect(localStorage.getItem('edge.session')).toBeNull();
  });

  it('works offline with the stored identity and shows the unreachable banner', async () => {
    store(me('POS'));
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    );
    render(<App />);
    expect(
      await screen.findByText(/Store server unreachable\. Sales are paused/),
    ).toBeInTheDocument();
    expect(screen.getByText('Offline')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Pair this device' })).not.toBeInTheDocument();
  });
});
