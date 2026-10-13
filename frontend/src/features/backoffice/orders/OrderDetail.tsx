import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiResult } from '../../../api/client';
import { getOrder } from '../../../api/endpoints';
import type { Order, Problem, RefundResponse } from '../../../api/types';
import type { PendingPayload } from '../../../domain/idempotency';
import { isCancellable, isRefundable, REASON_MAX, validReason } from '../../../domain/refund';
import { formatDateTime, formatMoney, formatPercent, shortHash } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { isOffline, useReachability } from '../../../shared/reachability';
import { useSession } from '../../../shared/session';
import { Field, FetchError, Notice, Panel, ProblemView, Stat } from '../../../shared/ui';
import { go } from '../../common/nav';
import { PendingBanner } from '../../common/PendingBanner';
import { Receipt } from '../../common/Receipt';
import { usePendingAction } from '../../common/usePendingAction';
import { PaymentsList } from '../../common/PaymentsList';
import { OrderStatusBadge } from './OrderStatusBadge';
import { RefundForm } from './RefundForm';

export const BACKOFFICE_SLOT = 'backoffice';

export function OrderDetail({ id }: { id: string }) {
  const { me } = useSession();
  const offline = isOffline(useReachability().state);
  const [order, setOrder] = useState<Order | null>(null);
  const [loadProblem, setLoadProblem] = useState<Problem | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [mode, setMode] = useState<'none' | 'cancel' | 'refund' | 'receipt'>('none');
  const [reason, setReason] = useState('');
  const reasonRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    const r = await getOrder(id);
    if (r.ok) {
      setOrder(r.data);
      setLoadProblem(null);
    } else setLoadProblem(r.problem);
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (mode === 'cancel') reasonRef.current?.focus();
  }, [mode]);

  const onSettled = useCallback(
    (payload: PendingPayload, r: ApiResult<unknown>) => {
      if (!r.ok) {
        setProblem(r.problem);
        if (r.status === 409) void load();
        return;
      }
      setProblem(null);
      setMode('none');
      if (payload.kind === 'refund') {
        const rr = r.data as RefundResponse;
        if ('orderId' in payload && payload.orderId === id) setOrder(rr.order);
        setNotice(
          t('bo.refundIssued', {
            number: rr.refund.refundNumber,
            amount: formatMoney(rr.refund.total, rr.order.currency),
          }),
        );
      } else if (payload.kind === 'void') {
        const o = r.data as Order;
        if (o.id === id) setOrder(o);
        setNotice(t('payment.voidedNotice', { due: formatMoney(o.amountDue, o.currency) }));
      } else if (payload.kind === 'cancel') {
        const o = r.data as Order;
        if (o.id === id) setOrder(o);
        setNotice(t('pos.orderCancelled', { number: o.orderNumber }));
      }
    },
    [id, load],
  );
  const pending = usePendingAction(BACKOFFICE_SLOT, onSettled);
  const blocked = pending.pending !== null || pending.busy || offline;

  const start = (payload: PendingPayload) => {
    setProblem(null);
    setNotice(null);
    void pending.start(payload);
  };

  if (!order) {
    return (
      <div className="stack">
        <BackLink />
        {loadProblem ? (
          <FetchError problem={loadProblem} />
        ) : (
          <p className="muted">{t('common.loading')}</p>
        )}
      </div>
    );
  }

  const m = (v: number) => formatMoney(v, order.currency);
  const cancelReason = validReason(reason);

  return (
    <div className="stack">
      <BackLink />
      <PendingBanner ctl={pending} />
      {problem ? <ProblemView problem={problem} /> : null}
      {notice ? <Notice tone="ok">{notice}</Notice> : null}

      <Panel
        title={
          <>
            <span className="mono">{order.orderNumber}</span>{' '}
            <OrderStatusBadge status={order.status} />
          </>
        }
        actions={
          <>
            <button type="button" onClick={() => void load()}>
              {t('common.refresh')}
            </button>
            <button type="button" onClick={() => setMode(mode === 'receipt' ? 'none' : 'receipt')}>
              {t('bo.reprint')}
            </button>
            {isCancellable(order) ? (
              <button type="button" onClick={() => setMode('cancel')} disabled={blocked}>
                {t('pos.cancelOrder')}
              </button>
            ) : null}
            {isRefundable(order.status) ? (
              <button
                type="button"
                className="btn-primary"
                onClick={() => setMode('refund')}
                disabled={blocked}
              >
                {t('bo.refund')}
              </button>
            ) : null}
          </>
        }
      >
        <dl className="kv">
          <dt>{t('order.placedAt')}</dt>
          <dd>{formatDateTime(order.placedAt, me.branch.zone)}</dd>
          <dt>{t('order.terminal')}</dt>
          <dd>{order.terminalId}</dd>
          <dt>{t('order.id')}</dt>
          <dd className="mono small">{order.id}</dd>
          <dt>{t('order.fiscalHash')}</dt>
          <dd className="mono small" title={order.fiscalHash ?? undefined}>
            {shortHash(order.fiscalHash)}
          </dd>
        </dl>
        <div className="stats">
          <Stat label={t('order.subtotal')} value={m(order.subtotal)} />
          <Stat label={t('order.tax')} value={m(order.taxTotal)} />
          <Stat label={t('order.total')} value={m(order.total)} />
          <Stat label={t('order.paid')} value={m(order.amountPaid)} />
          <Stat label={t('order.amountDue')} value={m(order.amountDue)} />
          <Stat label={t('order.refunded')} value={m(order.amountRefunded)} />
        </div>
        <div className="table-wrap">
          <table className="table">
            <caption>{t('order.lines')}</caption>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">{t('order.item')}</th>
                <th scope="col" className="num">
                  {t('order.qty')}
                </th>
                <th scope="col" className="num">
                  {t('order.refundedQty')}
                </th>
                <th scope="col" className="num">
                  {t('order.unitPrice')}
                </th>
                <th scope="col" className="num">
                  {t('order.taxRate')}
                </th>
                <th scope="col" className="num">
                  {t('order.tax')}
                </th>
                <th scope="col" className="num">
                  {t('order.lineTotal')}
                </th>
              </tr>
            </thead>
            <tbody>
              {order.lines.map((l) => (
                <tr key={l.lineNo}>
                  <td>{l.lineNo}</td>
                  <td>
                    {l.name} <code className="muted small">{l.sku}</code>
                  </td>
                  <td className="num">{l.quantity}</td>
                  <td className="num">{l.refundedQuantity}</td>
                  <td className="num">{m(l.unitPrice)}</td>
                  <td className="num">{formatPercent(l.taxRatePercent)}</td>
                  <td className="num">{m(l.tax)}</td>
                  <td className="num">{m(l.total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      {mode === 'cancel' ? (
        <Panel title={t('pos.cancelOrder')}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (cancelReason) start({ kind: 'cancel', orderId: order.id, reason: cancelReason });
            }}
          >
            <Field label={t('pos.cancelReason')} htmlFor="bo-cancel-reason">
              <input
                id="bo-cancel-reason"
                ref={reasonRef}
                maxLength={REASON_MAX}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </Field>
            <div className="btn-row">
              <button type="submit" className="btn-danger" disabled={!cancelReason || blocked}>
                {t('pos.confirmCancel')}
              </button>
              <button type="button" onClick={() => setMode('none')}>
                {t('common.close')}
              </button>
            </div>
          </form>
        </Panel>
      ) : null}

      {mode === 'refund' ? (
        <RefundForm
          order={order}
          disabled={blocked}
          onSubmit={(request) => start({ kind: 'refund', orderId: order.id, request })}
          onClose={() => setMode('none')}
        />
      ) : null}

      {mode === 'receipt' ? (
        <Panel
          title={t('receipt.title')}
          actions={
            <button type="button" className="btn-primary" onClick={() => window.print()}>
              {t('receipt.print')}
            </button>
          }
        >
          <Receipt order={order} branchCode={me.branch.code} zone={me.branch.zone} reprint />
        </Panel>
      ) : null}

      <div className="grid-2">
        <Panel title={t('order.payments')}>
          <PaymentsList
            order={order}
            zone={me.branch.zone}
            disabled={blocked}
            onVoid={(paymentId, reason) =>
              start({ kind: 'void', orderId: order.id, paymentId, reason })
            }
          />
        </Panel>
        <Panel title={t('order.refunds')}>
          {order.refunds.length === 0 ? (
            <p className="muted">{t('order.noRefunds')}</p>
          ) : (
            <ul className="refund-list">
              {order.refunds.map((r) => (
                <li key={r.id} className="refund-item">
                  <div className="result-head">
                    <strong className="mono">{r.refundNumber}</strong>
                    <span>{m(r.total)}</span>
                    <span className="muted small">
                      {r.method === 'CASH' ? t('payment.cash') : t('payment.card')} ·{' '}
                      {r.restocked ? t('bo.restocked') : t('bo.notRestocked')} ·{' '}
                      {formatDateTime(r.issuedAt, me.branch.zone)}
                    </span>
                  </div>
                  <div className="small">{r.reason}</div>
                  <ul className="small">
                    {r.lines.map((l) => (
                      <li key={l.lineNo}>
                        #{l.lineNo} {l.quantity} × {l.name} — {m(l.total)}
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </div>
  );
}

function BackLink() {
  return (
    <div>
      <button type="button" onClick={() => go({ app: 'backoffice', section: 'orders', id: null })}>
        ← {t('bo.backToOrders')}
      </button>
    </div>
  );
}
