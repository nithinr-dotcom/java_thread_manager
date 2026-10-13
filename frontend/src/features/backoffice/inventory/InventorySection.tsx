import { useCallback, useEffect, useState, type FormEvent } from 'react';
import type { ApiResult } from '../../../api/client';
import { getCatalog, listMovements } from '../../../api/endpoints';
import {
  ADJUSTMENT_REASONS,
  type AdjustmentReason,
  type Problem,
  type StockLevel,
  type StockMovement,
} from '../../../api/types';
import { parseIntInRange } from '../../../domain/decimal';
import type { PendingPayload } from '../../../domain/idempotency';
import { formatDateTime } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { Badge, Field, FetchError, Notice, Panel, ProblemView } from '../../../shared/ui';
import { usePolling } from '../../../shared/usePolling';
import { PendingBanner } from '../../common/PendingBanner';
import { usePendingAction } from '../../common/usePendingAction';

export const INVENTORY_SLOT = 'inventory';

const NOTE_MAX = 200;

export function InventorySection() {
  const catalog = usePolling(
    useCallback(() => getCatalog({ includeInactive: true }), []),
    15_000,
  );
  const items = catalog.data ?? [];
  const [selected, setSelected] = useState<string | null>(null);
  const sku = selected ?? items[0]?.sku ?? null;
  const item = items.find((i) => i.sku === sku) ?? null;

  const [movements, setMovements] = useState<StockMovement[] | null>(null);
  const [movProblem, setMovProblem] = useState<Problem | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [restockQty, setRestockQty] = useState('10');
  const [restockNote, setRestockNote] = useState('');
  const [delta, setDelta] = useState('-1');
  const [reason, setReason] = useState<AdjustmentReason>('WASTE');
  const [adjNote, setAdjNote] = useState('');

  const loadMovements = useCallback(async (s: string) => {
    const r = await listMovements(s, 50);
    if (r.ok) {
      setMovements(r.data);
      setMovProblem(null);
    } else setMovProblem(r.problem);
  }, []);

  useEffect(() => {
    setMovements(null);
    if (sku) void loadMovements(sku);
  }, [sku, loadMovements]);

  const qty = parseIntInRange(restockQty, 1, 100_000);
  const d = parseIntInRange(delta, -100_000, 100_000);

  const { refresh } = catalog;
  // Restock / adjustment are idempotent (durable key, same rules as orders).
  const onSettled = useCallback(
    (payload: PendingPayload, r: ApiResult<unknown>) => {
      if (payload.kind !== 'restock' && payload.kind !== 'adjust') return;
      if (r.ok) {
        const level = r.data as StockLevel;
        setNotice(t('inventory.onHandNow', { sku: level.sku, onHand: level.onHand }));
        setProblem(null);
        if (payload.kind === 'restock') setRestockNote('');
        else setAdjNote('');
        refresh();
        void loadMovements(payload.sku);
      } else {
        setProblem(r.problem);
        setNotice(null);
      }
    },
    [loadMovements, refresh],
  );
  const pending = usePendingAction(INVENTORY_SLOT, onSettled);
  const busy = pending.busy || pending.pending !== null;

  const start = (payload: PendingPayload) => {
    setProblem(null);
    setNotice(null);
    void pending.start(payload);
  };

  const doRestock = (e: FormEvent) => {
    e.preventDefault();
    if (!sku || qty === null) return;
    const note = restockNote.trim();
    start({ kind: 'restock', sku, request: { quantity: qty, ...(note ? { note } : {}) } });
  };

  const doAdjust = (e: FormEvent) => {
    e.preventDefault();
    if (!sku || d === null || d === 0) return;
    const note = adjNote.trim();
    start({ kind: 'adjust', sku, request: { delta: d, reason, ...(note ? { note } : {}) } });
  };

  return (
    <div className="stack">
      <PendingBanner ctl={pending} />
      {catalog.result && !catalog.result.ok ? (
        <FetchError problem={catalog.result.problem} />
      ) : null}
      <div className="grid-inv">
        <Panel title={t('inventory.products')}>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t('catalog.sku')}</th>
                  <th scope="col">{t('catalog.name')}</th>
                  <th scope="col" className="num">
                    {t('catalog.onHand')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((i) => (
                  <tr key={i.sku} className={i.sku === sku ? 'row-selected' : undefined}>
                    <td>
                      <button
                        type="button"
                        className="btn-link mono"
                        aria-pressed={i.sku === sku}
                        onClick={() => {
                          setSelected(i.sku);
                          setNotice(null);
                          setProblem(null);
                        }}
                      >
                        {i.sku}
                      </button>
                    </td>
                    <td>{i.name}</td>
                    <td className="num">
                      <Badge tone={i.onHand <= 0 ? 'bad' : i.onHand < 10 ? 'warn' : 'ok'}>
                        {i.onHand}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>

        {item ? (
          <div className="stack">
            <h2 className="section-title">
              {item.name} <code>{item.sku}</code> — {t('inventory.onHand', { onHand: item.onHand })}
            </h2>
            {notice ? <Notice tone="ok">{notice}</Notice> : null}
            {problem ? <ProblemView problem={problem} /> : null}
            <div className="grid-2">
              <Panel title={t('inventory.restock')} headingLevel={3}>
                <form onSubmit={doRestock}>
                  <Field
                    label={t('inventory.quantity')}
                    htmlFor="inv-restock-qty"
                    error={qty === null ? t('inventory.err.qty') : null}
                  >
                    <input
                      id="inv-restock-qty"
                      inputMode="numeric"
                      value={restockQty}
                      onChange={(e) => setRestockQty(e.target.value)}
                    />
                  </Field>
                  <Field label={t('inventory.note')} htmlFor="inv-restock-note">
                    <input
                      id="inv-restock-note"
                      maxLength={NOTE_MAX}
                      value={restockNote}
                      onChange={(e) => setRestockNote(e.target.value)}
                    />
                  </Field>
                  <button type="submit" className="btn-primary" disabled={busy || qty === null}>
                    {t('inventory.restock')}
                  </button>
                </form>
              </Panel>
              <Panel title={t('inventory.adjust')} headingLevel={3}>
                <form onSubmit={doAdjust}>
                  <Field
                    label={t('inventory.delta')}
                    htmlFor="inv-delta"
                    hint={t('inventory.deltaHint')}
                    error={d === null || d === 0 ? t('inventory.err.delta') : null}
                  >
                    <input
                      id="inv-delta"
                      inputMode="numeric"
                      value={delta}
                      onChange={(e) => setDelta(e.target.value)}
                    />
                  </Field>
                  <Field label={t('inventory.reason')} htmlFor="inv-reason">
                    <select
                      id="inv-reason"
                      value={reason}
                      onChange={(e) => setReason(e.target.value as AdjustmentReason)}
                    >
                      {ADJUSTMENT_REASONS.map((r) => (
                        <option key={r} value={r}>
                          {t(`movement.${r}`)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label={t('inventory.note')} htmlFor="inv-adj-note">
                    <input
                      id="inv-adj-note"
                      maxLength={NOTE_MAX}
                      value={adjNote}
                      onChange={(e) => setAdjNote(e.target.value)}
                    />
                  </Field>
                  <button type="submit" disabled={busy || d === null || d === 0}>
                    {t('inventory.applyAdjustment')}
                  </button>
                </form>
              </Panel>
            </div>
            <Panel
              title={t('inventory.movements')}
              headingLevel={3}
              actions={
                <button type="button" onClick={() => void loadMovements(item.sku)}>
                  {t('common.refresh')}
                </button>
              }
            >
              {movProblem ? <FetchError problem={movProblem} /> : null}
              {movements && movements.length === 0 ? (
                <p className="muted">{t('inventory.noMovements')}</p>
              ) : null}
              {movements && movements.length > 0 ? (
                <div className="table-wrap">
                  <table className="table">
                    <thead>
                      <tr>
                        <th scope="col">{t('inventory.when')}</th>
                        <th scope="col">{t('inventory.reason')}</th>
                        <th scope="col" className="num">
                          {t('inventory.delta')}
                        </th>
                        <th scope="col" className="num">
                          {t('inventory.after')}
                        </th>
                        <th scope="col">{t('order.terminal')}</th>
                        <th scope="col">{t('inventory.note')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {movements.map((mv) => (
                        <tr key={mv.id}>
                          <td>{formatDateTime(mv.createdAt)}</td>
                          <td>{t(`movement.${mv.reason}`)}</td>
                          <td className="num">{mv.delta > 0 ? `+${mv.delta}` : mv.delta}</td>
                          <td className="num">{mv.onHandAfter}</td>
                          <td>{mv.terminalId}</td>
                          <td className="wrap">{mv.note ?? ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : null}
            </Panel>
          </div>
        ) : null}
      </div>
    </div>
  );
}
