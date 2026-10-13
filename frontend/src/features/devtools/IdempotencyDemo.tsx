import { useCallback, useState } from 'react';
import { replayedHeader, type ApiResult } from '../../api/client';
import { checkout, getCatalog } from '../../api/endpoints';
import type { CheckoutItem, Order } from '../../api/types';
import { MAX_QTY } from '../../domain/cart';
import {
  attemptForCheckout,
  newAttempt,
  recordOutcome,
  type CheckoutAttempt,
} from '../../domain/idempotency';
import { formatMoney, formatMs } from '../../shared/formatting';
import { t } from '../../shared/i18n';
import { Badge, FetchError, Panel, ProblemView, StatusBadge } from '../../shared/ui';
import { usePolling } from '../../shared/usePolling';

interface Sent {
  label: string;
  key: string;
  items: CheckoutItem[];
  result: ApiResult<Order>;
}

/**
 * Test tool: shows the raw idempotency semantics of POST /orders. Every successful click creates a
 * real (unpaid) order on the node.
 */
export function IdempotencyDemo() {
  const catalog = usePolling(
    useCallback(() => getCatalog(), []),
    30_000,
  );
  const skus = (catalog.data ?? []).map((i) => i.sku);
  const [picked, setPicked] = useState('');
  const sku = picked || skus[0] || '';
  const [qty, setQty] = useState(1);
  const [attempt, setAttempt] = useState<CheckoutAttempt>(() => newAttempt());
  const [last, setLast] = useState<Sent | null>(null);
  const [log, setLog] = useState<Sent[]>([]);
  const [busy, setBusy] = useState(false);

  const send = async (label: string, key: string, items: CheckoutItem[], a: CheckoutAttempt) => {
    setBusy(true);
    const result = await checkout(key, { items });
    const sent: Sent = { label, key, items, result };
    setAttempt(recordOutcome(a, result.status));
    setLast(sent);
    setLog((l) => [sent, ...l].slice(0, 20));
    setBusy(false);
  };

  const onCheckout = () => {
    const a = attemptForCheckout(attempt);
    void send(t('dev.checkout'), a.key, [{ sku, quantity: qty }], a);
  };
  const onRetrySame = () => {
    if (last) void send(t('dev.retrySame'), last.key, last.items, attempt);
  };
  const onDifferentBasket = () => {
    if (!last) return;
    const [first, ...rest] = last.items;
    if (!first) return;
    const q = first.quantity < MAX_QTY ? first.quantity + 1 : first.quantity - 1;
    void send(
      t('dev.differentBasket'),
      last.key,
      [{ sku: first.sku, quantity: q }, ...rest],
      attempt,
    );
  };
  const onNew = () => {
    setAttempt(newAttempt());
    setLast(null);
  };

  return (
    <div className="stack">
      <Panel title={t('dev.idempotencyTitle')}>
        <p className="muted small">{t('dev.idempotencyIntro')}</p>
        {catalog.result && !catalog.result.ok ? (
          <FetchError problem={catalog.result.problem} />
        ) : null}
        <div className="form-grid">
          <div className="field">
            <label htmlFor="demo-sku">SKU</label>
            <select id="demo-sku" value={sku} onChange={(e) => setPicked(e.target.value)}>
              {skus.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="demo-qty">{t('order.qty')}</label>
            <input
              id="demo-qty"
              type="number"
              min={1}
              max={MAX_QTY}
              value={qty}
              onChange={(e) =>
                setQty(Math.min(MAX_QTY, Math.max(1, Math.trunc(Number(e.target.value) || 1))))
              }
            />
          </div>
        </div>
        <dl className="kv">
          <dt>Idempotency-Key</dt>
          <dd>
            <code data-testid="idem-key">{attempt.key}</code>
          </dd>
          <dt>{t('dev.sends')}</dt>
          <dd>{attempt.sends}</dd>
        </dl>
        <div className="btn-row">
          <button
            type="button"
            className="btn-primary"
            onClick={onCheckout}
            disabled={busy || !sku}
          >
            {t('dev.checkout')}
          </button>
          <button type="button" onClick={onRetrySame} disabled={busy || !last}>
            {t('dev.retrySame')}
          </button>
          <button type="button" onClick={onDifferentBasket} disabled={busy || !last}>
            {t('dev.differentBasket')}
          </button>
          <button type="button" onClick={onNew} disabled={busy}>
            {t('dev.newKey')}
          </button>
        </div>
        <p className="hint">{t('dev.idempotencyHint')}</p>
      </Panel>

      <Panel title={t('dev.lastResponse')}>
        {last ? <ResultView sent={last} /> : <p className="muted">{t('dev.noRequest')}</p>}
      </Panel>

      {log.length > 0 ? (
        <Panel title={t('dev.requestLog')}>
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t('dev.action')}</th>
                <th scope="col">Key</th>
                <th scope="col">{t('dev.status')}</th>
                <th scope="col">{t('reports.result')}</th>
                <th scope="col">{t('dev.latency')}</th>
              </tr>
            </thead>
            <tbody>
              {log.map((s, i) => (
                <tr key={i}>
                  <td>{s.label}</td>
                  <td>
                    <code title={s.key}>{s.key.slice(0, 8)}…</code>
                  </td>
                  <td>
                    <StatusBadge status={s.result.status} />
                  </td>
                  <td>
                    {s.result.ok
                      ? s.result.data.orderNumber
                      : (s.result.problem.code ?? s.result.problem.title)}
                  </td>
                  <td className="num">{formatMs(s.result.elapsedMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      ) : null}
    </div>
  );
}

function ResultView({ sent }: { sent: Sent }) {
  const { result } = sent;
  const replayed = replayedHeader(result.headers);
  return (
    <div className="result" data-testid="result">
      <div className="result-head">
        <StatusBadge status={result.status} />
        {replayed === true ? <Badge tone="info">Idempotent-Replayed: true</Badge> : null}
        {replayed === false ? <Badge tone="muted">Idempotent-Replayed: false</Badge> : null}
        <span className="muted small">
          {sent.label} · key <code>{sent.key}</code> · {formatMs(result.elapsedMs)}
        </span>
      </div>
      {result.ok ? (
        <dl className="kv">
          <dt>{t('order.number')}</dt>
          <dd data-testid="order-number">{result.data.orderNumber}</dd>
          <dt>{t('order.status')}</dt>
          <dd>{result.data.status}</dd>
          <dt>{t('order.total')}</dt>
          <dd>
            <strong>{formatMoney(result.data.total, result.data.currency)}</strong>
          </dd>
        </dl>
      ) : (
        <ProblemView problem={result.problem} />
      )}
    </div>
  );
}
