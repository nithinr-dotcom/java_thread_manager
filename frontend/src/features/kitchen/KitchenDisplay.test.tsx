import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { KitchenTicket } from '../../api/types';
import { catalog, me, ticket } from '../../test/fixtures';
import { fakeServer, json, problem, sseOpen } from '../../test/fakeServer';
import { renderAs } from '../../test/render';
import { KitchenDisplay } from './KitchenDisplay';

const frame = (t: KitchenTicket) => `event: ticket\ndata: ${JSON.stringify(t)}\n\n`;

describe('KitchenDisplay', () => {
  it('shows streamed tickets and bumps them through the allowed transitions', async () => {
    const queued = ticket();
    let current = queued;
    const server = fakeServer([
      ['GET', '/api/v1/catalog', () => json(200, catalog)],
      [
        'GET',
        '/api/v1/kitchen/stream',
        (req) => sseOpen([': ping\n\n', frame(current)], req.signal),
      ],
      ['GET', '/api/v1/kitchen/tickets', () => json(200, [current])],
      [
        'POST',
        '/api/v1/kitchen/tickets/ticket-1/status',
        (req) => {
          const { status } = req.body as { status: KitchenTicket['status'] };
          current = { ...current, status, updatedAt: new Date(Date.now() + 1000).toISOString() };
          return json(200, current);
        },
      ],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<KitchenDisplay />, me('KITCHEN'));

    const queuedCol = await screen.findByRole('list', { name: 'Queued' });
    const card = await within(queuedCol).findByTestId(`ticket-${queued.orderNumber}`);
    expect(card).toHaveTextContent('2× Classic burger');
    expect(await screen.findByText('Live')).toBeInTheDocument();
    const [streamReq] = server.calls('GET', '/api/v1/kitchen/stream');
    expect(streamReq?.headers.Authorization).toBe('Bearer sf_test_kitchen');

    await user.click(within(card).getByRole('button', { name: `Start ${queued.orderNumber}` }));
    const inProgress = screen.getByRole('list', { name: 'In progress' });
    expect(
      await within(inProgress).findByTestId(`ticket-${queued.orderNumber}`),
    ).toBeInTheDocument();
    expect(server.calls('POST', '/api/v1/kitchen/tickets/ticket-1/status')[0]?.body).toEqual({
      status: 'IN_PROGRESS',
    });

    await user.click(screen.getByRole('button', { name: `Ready ${queued.orderNumber}` }));
    const ready = screen.getByRole('list', { name: 'Ready' });
    const readyCard = await within(ready).findByTestId(`ticket-${queued.orderNumber}`);
    expect(
      within(readyCard).getByRole('button', { name: `Recall ${queued.orderNumber}` }),
    ).toBeInTheDocument();

    await user.click(
      within(readyCard).getByRole('button', { name: `Served ${queued.orderNumber}` }),
    );
    await waitFor(() =>
      expect(screen.queryByTestId(`ticket-${queued.orderNumber}`)).not.toBeInTheDocument(),
    );
  });

  it('refreshes on 409 TICKET_STATE_CONFLICT', async () => {
    const t1 = ticket();
    let listed: KitchenTicket[] = [t1];
    const server = fakeServer([
      ['GET', '/api/v1/catalog', () => json(200, catalog)],
      ['GET', '/api/v1/kitchen/stream', (req) => sseOpen([frame(t1)], req.signal)],
      ['GET', '/api/v1/kitchen/tickets', () => json(200, listed)],
      [
        'POST',
        '/api/v1/kitchen/tickets/ticket-1/status',
        () => {
          listed = []; // served on another screen
          return problem(409, 'TICKET_STATE_CONFLICT');
        },
      ],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<KitchenDisplay />, me('KITCHEN'));
    await user.click(await screen.findByRole('button', { name: `Start ${t1.orderNumber}` }));
    expect(await screen.findByText(/changed on another screen/)).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByTestId(`ticket-${t1.orderNumber}`)).not.toBeInTheDocument(),
    );
  });

  it('persists the station filter and streams only that station', async () => {
    const server = fakeServer([
      ['GET', '/api/v1/catalog', () => json(200, catalog)],
      ['GET', '/api/v1/kitchen/stream', (req) => sseOpen([], req.signal)],
      ['GET', '/api/v1/kitchen/tickets', () => json(200, [])],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<KitchenDisplay />, me('KITCHEN'));
    const select = screen.getByLabelText('Station');
    await screen.findByRole('option', { name: 'GRILL' });
    await user.selectOptions(select, 'GRILL');
    expect(localStorage.getItem('edge.kds.station')).toBe('GRILL');
    await waitFor(() =>
      expect(server.requests.some((r) => r.url === '/api/v1/kitchen/stream?station=GRILL')).toBe(
        true,
      ),
    );
  });
});
