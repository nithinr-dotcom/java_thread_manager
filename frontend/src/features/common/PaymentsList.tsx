import { useEffect, useRef, useState } from 'react';
import type { Order, Payment } from '../../api/types';
import { isVoidable, REASON_MAX, validReason } from '../../domain/refund';
import { formatDateTime, formatMoney } from '../../shared/formatting';
import { t } from '../../shared/i18n';
import { Field } from '../../shared/ui';

/**
 * Payments of an order. Voided payments are struck through (they no longer count; amountPaid /
 * amountDue always come from the server). While the order is PLACED each unvoided payment can be
 * voided after an in-page confirmation with a reason.
 */
export function PaymentsList({
  order,
  zone,
  disabled,
  onVoid,
  large = false,
}: {
  order: Order;
  zone?: string;
  disabled: boolean;
  onVoid: (paymentId: string, reason: string) => void;
  large?: boolean;
}) {
  const [voiding, setVoiding] = useState<string | null>(null);
  if (order.payments.length === 0) return <p className="muted">{t('order.noPayments')}</p>;
  const m = (v: number) => formatMoney(v, order.currency);
  return (
    <ul className="payment-list" aria-label={t('payment.list')}>
      {order.payments.map((p) => (
        <li
          key={p.id}
          className={`payment-item ${p.voidedAt ? 'is-voided' : ''}`}
          data-testid={`payment-${p.id}`}
        >
          <div className="payment-row">
            <span className="payment-desc">
              <strong>{p.method === 'CASH' ? t('payment.cash') : t('payment.card')}</strong>{' '}
              <span className="payment-amount">{m(p.amount)}</span>
              <PaymentDetail p={p} currency={order.currency} />
              <span className="muted small"> · {formatDateTime(p.receivedAt, zone)}</span>
            </span>
            {p.voidedAt ? (
              <span className="badge badge-muted">{t('payment.voided')}</span>
            ) : isVoidable(order, p) && voiding !== p.id ? (
              <button
                type="button"
                className={large ? 'btn-lg btn-danger-outline' : 'btn-danger-outline'}
                onClick={() => setVoiding(p.id)}
                disabled={disabled}
                aria-label={t('payment.voidItem', { amount: m(p.amount) })}
              >
                {t('payment.void')}
              </button>
            ) : null}
          </div>
          {p.voidedAt && p.voidReason ? (
            <div className="muted small">{t('payment.voidedReason', { reason: p.voidReason })}</div>
          ) : null}
          {voiding === p.id && !p.voidedAt ? (
            <VoidForm
              paymentId={p.id}
              large={large}
              disabled={disabled}
              onSubmit={(reason) => {
                setVoiding(null);
                onVoid(p.id, reason);
              }}
              onCancel={() => setVoiding(null)}
            />
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function PaymentDetail({ p, currency }: { p: Payment; currency: string }) {
  const m = (v: number) => formatMoney(v, currency);
  if (p.method === 'CARD')
    return p.reference ? <span className="small"> ({p.reference})</span> : null;
  return (
    <span className="small">
      {p.tendered !== null ? ` · ${t('payment.tendered')} ${m(p.tendered)}` : ''}
      {p.change !== null ? ` · ${t('payment.change')} ${m(p.change)}` : ''}
    </span>
  );
}

function VoidForm({
  paymentId,
  large,
  disabled,
  onSubmit,
  onCancel,
}: {
  paymentId: string;
  large: boolean;
  disabled: boolean;
  onSubmit: (reason: string) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => ref.current?.focus(), []);
  const valid = validReason(reason);
  const id = `void-reason-${paymentId}`;
  return (
    <form
      className="inline-form void-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) onSubmit(valid);
      }}
    >
      <p className="hint warn">{t('payment.voidHint')}</p>
      <Field label={t('payment.voidReason')} htmlFor={id}>
        <input
          id={id}
          ref={ref}
          className={large ? 'input-lg' : undefined}
          maxLength={REASON_MAX}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>
      <div className="btn-row">
        <button
          type="submit"
          className={large ? 'btn-danger btn-lg' : 'btn-danger'}
          disabled={!valid || disabled}
        >
          {t('payment.voidConfirm')}
        </button>
        <button type="button" className={large ? 'btn-lg' : undefined} onClick={onCancel}>
          {t('common.cancel')}
        </button>
      </div>
    </form>
  );
}
