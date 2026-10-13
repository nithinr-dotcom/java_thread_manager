import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { Order, Payment, PaymentMethod, PaymentRequest } from '../../api/types';
import { amountToInput, parseAmount, type DecimalError } from '../../domain/decimal';
import { isCancellable, REASON_MAX, validReason } from '../../domain/refund';
import { currencyDigits, formatMoney } from '../../shared/formatting';
import { t, type MessageKey } from '../../shared/i18n';
import { Field } from '../../shared/ui';
import { PaymentsList } from '../common/PaymentsList';

const QUICK_NOTES = [5, 10, 20, 50] as const;

const DECIMAL_ERRORS: Record<DecimalError, MessageKey> = {
  EMPTY: 'input.required',
  FORMAT: 'input.amountFormat',
  SCALE: 'input.amountScale',
  NOT_POSITIVE: 'input.amountPositive',
  NEGATIVE: 'input.amountPositive',
};

export function OrderSummary({ order }: { order: Order }) {
  const m = (v: number) => formatMoney(v, order.currency);
  return (
    <div className="order-summary">
      <ul className="summary-lines" aria-label={t('order.lines')}>
        {order.lines.map((l) => (
          <li key={l.lineNo}>
            <span>
              {l.quantity} × {l.name}
            </span>
            <span className="num">{m(l.total)}</span>
          </li>
        ))}
      </ul>
      <dl className="summary-totals">
        <dt>{t('order.subtotal')}</dt>
        <dd>{m(order.subtotal)}</dd>
        <dt>{t('order.tax')}</dt>
        <dd>{m(order.taxTotal)}</dd>
        <dt className="summary-total">{t('order.total')}</dt>
        <dd className="summary-total" data-testid="order-total">
          {m(order.total)}
        </dd>
        {order.amountPaid > 0 ? (
          <>
            <dt>{t('order.paid')}</dt>
            <dd>{m(order.amountPaid)}</dd>
          </>
        ) : null}
        <dt className="summary-due">{t('order.amountDue')}</dt>
        <dd className="summary-due" data-testid="amount-due">
          {m(order.amountDue)}
        </dd>
      </dl>
    </div>
  );
}

export function PaymentPanel({
  order,
  lastPayment,
  busy,
  disabled,
  onPay,
  onCancel,
  onVoid,
  zone,
}: {
  order: Order;
  lastPayment: Payment | null;
  busy: boolean;
  disabled: boolean;
  onPay: (req: PaymentRequest) => void;
  onCancel: (reason: string) => void;
  onVoid: (paymentId: string, reason: string) => void;
  zone?: string;
}) {
  const digits = currencyDigits(order.currency);
  const due = amountToInput(order.amountDue, digits);
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [amount, setAmount] = useState(due);
  const [tendered, setTendered] = useState('');
  const [reference, setReference] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const reasonRef = useRef<HTMLInputElement>(null);

  // A new amountDue from the server (after a split payment) resets the form.
  useEffect(() => {
    setAmount(due);
    setTendered('');
    setReference('');
    setSubmitted(false);
  }, [due]);

  useEffect(() => {
    if (cancelOpen) reasonRef.current?.focus();
  }, [cancelOpen]);

  const amountRes = parseAmount(amount, digits, { positive: true });
  const tenderedRes = parseAmount(tendered, digits, { positive: true });
  const refTrim = reference.trim();
  const refValid = refTrim.length >= 1 && refTrim.length <= 64;
  const valid = amountRes.ok && (method === 'CASH' ? tenderedRes.ok : refValid);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (!amountRes.ok) return;
    if (method === 'CASH') {
      if (!tenderedRes.ok) return;
      onPay({ method: 'CASH', amount: amountRes.value, tendered: tenderedRes.value });
    } else {
      if (!refValid) return;
      onPay({ method: 'CARD', amount: amountRes.value, reference: refTrim });
    }
  };

  const cancelReason = validReason(reason);
  const m = (v: number) => formatMoney(v, order.currency);

  return (
    <div className="payment">
      {lastPayment ? (
        <div className="change-box" role="status" aria-live="polite">
          {t('payment.received', { amount: m(lastPayment.amount) })}
          {lastPayment.change !== null ? (
            <strong className="change-amount">
              {' '}
              {t('payment.changeDue', { amount: m(lastPayment.change) })}
            </strong>
          ) : null}
        </div>
      ) : null}

      <form onSubmit={submit} noValidate aria-label={t('payment.title')}>
        <div className="method-toggle" role="radiogroup" aria-label={t('payment.method')}>
          {(['CASH', 'CARD'] as const).map((mth) => (
            <button
              key={mth}
              type="button"
              role="radio"
              aria-checked={method === mth}
              className={`method-btn ${method === mth ? 'is-active' : ''}`}
              onClick={() => setMethod(mth)}
            >
              {mth === 'CASH' ? t('payment.cash') : t('payment.card')}
            </button>
          ))}
        </div>

        <Field
          label={t('payment.amount')}
          htmlFor="pay-amount"
          hint={t('payment.amountHint')}
          error={submitted && !amountRes.ok ? t(DECIMAL_ERRORS[amountRes.error]) : null}
        >
          <input
            id="pay-amount"
            className="input-lg"
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            aria-invalid={submitted && !amountRes.ok}
          />
        </Field>

        {method === 'CASH' ? (
          <>
            <Field
              label={t('payment.tendered')}
              htmlFor="pay-tendered"
              error={submitted && !tenderedRes.ok ? t(DECIMAL_ERRORS[tenderedRes.error]) : null}
            >
              <input
                id="pay-tendered"
                className="input-lg"
                inputMode="decimal"
                value={tendered}
                onChange={(e) => setTendered(e.target.value)}
                aria-invalid={submitted && !tenderedRes.ok}
              />
            </Field>
            <div className="quick-cash" role="group" aria-label={t('payment.quickCash')}>
              <button type="button" className="btn-lg" onClick={() => setTendered(due)}>
                {t('payment.exact')}
              </button>
              {QUICK_NOTES.map((n) => (
                <button
                  key={n}
                  type="button"
                  className="btn-lg"
                  onClick={() => setTendered(amountToInput(n, digits))}
                >
                  {formatMoney(n, order.currency)}
                </button>
              ))}
            </div>
            <p className="hint">{t('payment.changeByServer')}</p>
          </>
        ) : (
          <>
            <p className="hint">{t('payment.cardHint')}</p>
            <Field
              label={t('payment.reference')}
              htmlFor="pay-reference"
              error={submitted && !refValid ? t('payment.referenceInvalid') : null}
            >
              <input
                id="pay-reference"
                className="input-lg"
                maxLength={64}
                autoComplete="off"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
                aria-invalid={submitted && !refValid}
              />
            </Field>
          </>
        )}

        <div className="btn-row">
          <button
            type="submit"
            className="btn-primary btn-xl"
            disabled={busy || disabled || (submitted && !valid)}
          >
            {busy
              ? t('payment.processing')
              : method === 'CASH'
                ? t('payment.payCash')
                : t('payment.payCard')}
          </button>
        </div>
      </form>

      {order.payments.length > 0 ? (
        <section className="payments-taken" aria-labelledby="payments-taken-heading">
          <h3 id="payments-taken-heading">{t('payment.list')}</h3>
          <PaymentsList
            order={order}
            zone={zone}
            disabled={busy || disabled}
            onVoid={onVoid}
            large
          />
          {!isCancellable(order) ? <p className="hint">{t('payment.voidFirst')}</p> : null}
        </section>
      ) : null}

      {isCancellable(order) ? (
        <div className="cancel-order">
          {!cancelOpen ? (
            <button
              type="button"
              className="btn-lg btn-danger-outline"
              onClick={() => setCancelOpen(true)}
              disabled={busy}
            >
              {t('pos.cancelOrder')}
            </button>
          ) : (
            <form
              className="inline-form"
              onSubmit={(e) => {
                e.preventDefault();
                if (cancelReason) onCancel(cancelReason);
              }}
            >
              <Field label={t('pos.cancelReason')} htmlFor="cancel-reason">
                <input
                  id="cancel-reason"
                  ref={reasonRef}
                  className="input-lg"
                  maxLength={REASON_MAX}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </Field>
              <div className="btn-row">
                <button
                  type="submit"
                  className="btn-danger btn-lg"
                  disabled={!cancelReason || busy || disabled}
                >
                  {t('pos.confirmCancel')}
                </button>
                <button type="button" className="btn-lg" onClick={() => setCancelOpen(false)}>
                  {t('pos.keepOrder')}
                </button>
              </div>
            </form>
          )}
        </div>
      ) : null}
    </div>
  );
}
