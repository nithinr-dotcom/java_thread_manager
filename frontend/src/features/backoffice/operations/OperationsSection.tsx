import { useCallback, useState } from 'react';
import {
  getOutboxStatus,
  listDeadLetters,
  retryAllDeadLetters,
  retryDeadLetter,
} from '../../../api/endpoints';
import type { Problem } from '../../../api/types';
import { formatAge, formatDateTime } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { Badge, ConfirmButton, FetchError, Notice, Panel, ProblemView } from '../../../shared/ui';
import { useNow } from '../../../shared/useNow';
import { usePolling } from '../../../shared/usePolling';

export function OperationsSection() {
  const outbox = usePolling(
    useCallback(() => getOutboxStatus(), []),
    5000,
  );
  const dead = usePolling(
    useCallback(() => listDeadLetters(50), []),
    15_000,
  );
  const now = useNow(1000);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const retry = async (id: string) => {
    setBusyId(id);
    setProblem(null);
    setNotice(null);
    const r = await retryDeadLetter(id);
    setBusyId(null);
    if (r.ok) setNotice(t('ops.requeued'));
    else setProblem(r.problem);
    dead.refresh();
    outbox.refresh();
  };

  const retryAll = async (destination: string) => {
    setProblem(null);
    setNotice(null);
    const r = await retryAllDeadLetters(destination);
    if (r.ok) setNotice(t('ops.requeuedAll', { count: r.data.requeued, destination }));
    else setProblem(r.problem);
    dead.refresh();
    outbox.refresh();
  };

  const rows = outbox.data?.destinations ?? [];
  const letters = dead.data ?? [];

  return (
    <div className="stack">
      <Panel
        title={t('ops.outbox')}
        actions={<span className="muted small">{t('ops.polling')}</span>}
      >
        {outbox.result && !outbox.result.ok ? <FetchError problem={outbox.result.problem} /> : null}
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t('ops.destination')}</th>
                <th scope="col" className="num">
                  {t('ops.pending')}
                </th>
                <th scope="col" className="num">
                  {t('ops.published')}
                </th>
                <th scope="col" className="num">
                  {t('ops.failing')}
                </th>
                <th scope="col" className="num">
                  {t('ops.dead')}
                </th>
                <th scope="col">{t('ops.oldestPending')}</th>
                <th scope="col">{t('ops.lastError')}</th>
                <th scope="col">
                  <span className="sr-only">{t('common.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.destination}>
                  <th scope="row">{d.destination}</th>
                  <td className="num">
                    <Badge tone={d.pending === 0 ? 'ok' : d.destination === 'HQ' ? 'info' : 'warn'}>
                      {d.pending}
                    </Badge>
                  </td>
                  <td className="num">{d.published}</td>
                  <td className="num">
                    <Badge tone={d.failing === 0 ? 'ok' : 'bad'}>{d.failing}</Badge>
                  </td>
                  <td className="num">
                    <Badge tone={d.dead === 0 ? 'ok' : 'bad'}>{d.dead}</Badge>
                  </td>
                  <td>{formatAge(d.oldestPendingAt, now)}</td>
                  <td className="wrap small">{d.lastError ?? '—'}</td>
                  <td>
                    {d.dead > 0 ? (
                      <ConfirmButton
                        label={t('ops.retryAll')}
                        ariaLabel={t('ops.retryAllItem', { destination: d.destination })}
                        confirmLabel={t('ops.retryAllConfirm')}
                        question={t('ops.retryAllQuestion', {
                          count: d.dead,
                          destination: d.destination,
                        })}
                        onConfirm={() => void retryAll(d.destination)}
                      />
                    ) : null}
                  </td>
                </tr>
              ))}
              {outbox.data && rows.length === 0 ? (
                <tr>
                  <td colSpan={8} className="muted">
                    {t('ops.noEvents')}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
        <p className="hint">{t('ops.hqHint')}</p>
      </Panel>

      <Panel
        title={t('ops.deadLetters', { count: letters.length })}
        actions={
          <button type="button" onClick={dead.refresh}>
            {t('common.refresh')}
          </button>
        }
      >
        {notice ? <Notice tone="ok">{notice}</Notice> : null}
        {problem ? <ProblemView problem={problem} /> : null}
        {dead.result && !dead.result.ok ? <FetchError problem={dead.result.problem} /> : null}
        {dead.data && letters.length === 0 ? <p className="muted">{t('ops.noDead')}</p> : null}
        {letters.length > 0 ? (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t('ops.destination')}</th>
                  <th scope="col">{t('ops.eventType')}</th>
                  <th scope="col">{t('ops.aggregate')}</th>
                  <th scope="col" className="num">
                    {t('ops.attempts')}
                  </th>
                  <th scope="col">{t('ops.lastError')}</th>
                  <th scope="col">{t('ops.deadAt')}</th>
                  <th scope="col">
                    <span className="sr-only">{t('common.actions')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {letters.map((l) => (
                  <tr key={l.id}>
                    <td>{l.destination}</td>
                    <td>{l.eventType}</td>
                    <td className="mono small">{l.aggregateId}</td>
                    <td className="num">{l.attempts}</td>
                    <td className="wrap small">{l.lastError ?? '—'}</td>
                    <td>{formatDateTime(l.deadAt)}</td>
                    <td>
                      <button
                        type="button"
                        onClick={() => void retry(l.id)}
                        disabled={busyId === l.id}
                        aria-label={t('ops.retryItem', { type: l.eventType, id: l.id })}
                      >
                        {t('ops.retry')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Panel>
    </div>
  );
}
