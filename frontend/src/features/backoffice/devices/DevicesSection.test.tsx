import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Terminal } from '../../../api/types';
import { me } from '../../../test/fixtures';
import { fakeServer, json, problem } from '../../../test/fakeServer';
import { renderAs } from '../../../test/render';
import { DevicesSection } from './DevicesSection';

const terminal = (terminalId: string, role: Terminal['role']): Terminal => ({
  terminalId,
  name: terminalId,
  role,
  active: true,
  createdAt: '2026-10-13T10:00:00Z',
  revokedAt: null,
});

describe('DevicesSection', () => {
  it('registers a MONITOR device and shows its key once', async () => {
    const server = fakeServer([
      ['GET', '/api/v1/terminals', () => json(200, [terminal('demo-manager', 'MANAGER')])],
      [
        'POST',
        '/api/v1/terminals',
        () => json(201, { ...terminal('prom-1', 'MONITOR'), apiKey: 'sf_secret_once' }),
      ],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<DevicesSection />, me('MANAGER', 'demo-manager'));
    await user.type(screen.getByLabelText('Terminal id'), 'prom-1');
    await user.type(screen.getByLabelText('Name'), 'Prometheus');
    await user.selectOptions(screen.getByLabelText('Role'), 'MONITOR');
    await user.click(screen.getByRole('button', { name: 'Register and create key' }));
    expect(await screen.findByTestId('new-api-key')).toHaveTextContent('sf_secret_once');
    expect(server.calls('POST', '/api/v1/terminals')[0]?.body).toEqual({
      terminalId: 'prom-1',
      name: 'Prometheus',
      role: 'MONITOR',
    });
  });

  it('explains LAST_MANAGER when revoking the last manager device', async () => {
    const server = fakeServer([
      [
        'GET',
        '/api/v1/terminals',
        () => json(200, [terminal('demo-manager', 'MANAGER'), terminal('mgr-2', 'MANAGER')]),
      ],
      ['POST', '/api/v1/terminals/mgr-2/revoke', () => problem(409, 'LAST_MANAGER')],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<DevicesSection />, me('MANAGER', 'demo-manager'));
    await user.click(await screen.findByRole('button', { name: 'Revoke' }));
    await user.click(screen.getByRole('button', { name: 'Revoke key' }));
    expect(await screen.findByText(/last active manager device/)).toBeInTheDocument();
  });
});
