import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { me } from '../../../test/fixtures';
import { fakeServer, json } from '../../../test/fakeServer';
import { renderAs } from '../../../test/render';
import { OperationsSection } from './OperationsSection';

describe('OperationsSection', () => {
  it('re-queues all dead letters of a destination after confirmation', async () => {
    const server = fakeServer([
      [
        'GET',
        '/api/v1/ops/outbox',
        () =>
          json(200, {
            destinations: [
              {
                destination: 'HQ',
                pending: 3,
                published: 10,
                failing: 0,
                dead: 37,
                oldestPendingAt: null,
                lastError: 'HTTP 401',
              },
            ],
          }),
      ],
      ['GET', '/api/v1/ops/outbox/dead', () => json(200, [])],
      ['POST', '/api/v1/ops/outbox/dead/retry', () => json(200, { requeued: 37 })],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<OperationsSection />, me('MANAGER'));

    await user.click(await screen.findByRole('button', { name: 'Retry all HQ dead letters' }));
    expect(screen.getByText('Re-queue all 37 HQ dead letters?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry all' }));
    expect(await screen.findByText('37 HQ dead letters re-queued.')).toBeInTheDocument();
    expect(server.calls('POST', '/api/v1/ops/outbox/dead/retry')[0]?.url).toBe(
      '/api/v1/ops/outbox/dead/retry?destination=HQ',
    );
  });
});
