import type { Order, Payment } from '../../api/types';
import { formatDateTime, formatMoney, formatPercent, shortHash } from '../../shared/formatting';
import { t } from '../../shared/i18n';

/**
 * Printable receipt. Every amount is a server value (order totals, line totals, payment change).
 * The `.receipt` element is the only thing printed (see @media print in styles.css).
 */
export function Receipt({
  order,
  branchCode,
  zone,
  reprint = false,
}: {
  order: Order;
  branchCode: string;
  zone?: string;
  reprint?: boolean;
}) {
  const m = (v: number) => formatMoney(v, order.currency);
  return (
    <article className="receipt" aria-label={t('receipt.title')} data-testid="receipt">
      <header className="receipt-head">
        <strong>{t('receipt.branch', { code: branchCode })}</strong>
        <div>{t('receipt.orderNumber', { number: order.orderNumber })}</div>
        <div className="small">{formatDateTime(order.placedAt, zone)}</div>
        <div className="small">{t('receipt.terminal', { terminal: order.terminalId })}</div>
        {reprint ? <div className="receipt-copy">{t('receipt.copy')}</div> : null}
        {order.status === 'CANCELLED' ? (
          <div className="receipt-copy">{t('status.CANCELLED')}</div>
        ) : null}
      </header>
      <table className="receipt-lines">
        <thead>
          <tr>
            <th scope="col">{t('receipt.item')}</th>
            <th scope="col" className="num">
              {t('receipt.amount')}
            </th>
          </tr>
        </thead>
        <tbody>
          {order.lines.map((l) => (
            <tr key={l.lineNo}>
              <td>
                {l.quantity} × {l.name}
                <div className="small muted">
                  {m(l.unitPrice)} ·{' '}
                  {t('receipt.taxRate', { rate: formatPercent(l.taxRatePercent) })}
                </div>
              </td>
              <td className="num">{m(l.total)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="receipt-totals">
        <dt>{t('order.subtotal')}</dt>
        <dd>{m(order.subtotal)}</dd>
        <dt>{t('order.tax')}</dt>
        <dd>{m(order.taxTotal)}</dd>
        <dt className="receipt-total">{t('order.total')}</dt>
        <dd className="receipt-total" data-testid="receipt-total">
          {m(order.total)}
        </dd>
      </dl>
      {order.payments.length > 0 ? (
        <dl className="receipt-totals">
          {order.payments.map((p) => (
            <PaymentLines key={p.id} p={p} currency={order.currency} />
          ))}
        </dl>
      ) : null}
      {order.amountRefunded > 0 ? (
        <dl className="receipt-totals">
          <dt>{t('order.refunded')}</dt>
          <dd>{m(order.amountRefunded)}</dd>
        </dl>
      ) : null}
      <footer className="receipt-foot small">
        {t('receipt.fiscal', { hash: shortHash(order.fiscalHash) })}
        <div>{t('receipt.thanks')}</div>
      </footer>
    </article>
  );
}

function PaymentLines({ p, currency }: { p: Payment; currency: string }) {
  const m = (v: number) => formatMoney(v, currency);
  if (p.voidedAt) {
    // Handed back before the order was settled: listed for traceability, never counted.
    return (
      <>
        <dt className="is-voided">
          {p.method === 'CASH' ? t('payment.cash') : t('payment.card')} ({t('payment.voided')})
        </dt>
        <dd className="is-voided">{m(p.amount)}</dd>
      </>
    );
  }
  return (
    <>
      <dt>
        {p.method === 'CASH' ? t('payment.cash') : t('payment.card')}
        {p.reference ? <span className="small muted"> ({p.reference})</span> : null}
      </dt>
      <dd>{m(p.amount)}</dd>
      {p.tendered !== null ? (
        <>
          <dt className="small">{t('payment.tendered')}</dt>
          <dd className="small">{m(p.tendered)}</dd>
        </>
      ) : null}
      {p.change !== null ? (
        <>
          <dt>{t('payment.change')}</dt>
          <dd data-testid="receipt-change">{m(p.change)}</dd>
        </>
      ) : null}
    </>
  );
}
