import { useState, type FormEvent } from 'react';
import type { Order, PaymentMethod, RefundRequest } from '../../../api/types';
import {
  remainingQty,
  REASON_MAX,
  selectAll,
  setRefundQty,
  validateRefund,
  type RefundError,
  type RefundSelection,
} from '../../../domain/refund';
import { t } from '../../../shared/i18n';
import { Field, Panel } from '../../../shared/ui';

function errorText(e: RefundError): string {
  switch (e.code) {
    case 'NOT_REFUNDABLE':
      return t('refund.err.notRefundable');
    case 'NOTHING_SELECTED':
      return t('refund.err.nothingSelected');
    case 'UNKNOWN_LINE':
      return t('refund.err.unknownLine', { line: e.lineNo });
    case 'EXCEEDS_REMAINING':
      return t('refund.err.exceeds', { line: e.lineNo, remaining: e.remaining });
    case 'REASON_REQUIRED':
      return t('refund.err.reasonRequired');
    case 'REASON_TOO_LONG':
      return t('refund.err.reasonTooLong', { max: REASON_MAX });
  }
}

/** Select line quantities to refund. Amounts are computed by the server, never here. */
export function RefundForm({
  order,
  disabled,
  onSubmit,
  onClose,
}: {
  order: Order;
  disabled: boolean;
  onSubmit: (r: RefundRequest) => void;
  onClose: () => void;
}) {
  const [selection, setSelection] = useState<RefundSelection>({});
  const [restock, setRestock] = useState(true);
  const [method, setMethod] = useState<PaymentMethod>(
    order.payments[0]?.method === 'CARD' ? 'CARD' : 'CASH',
  );
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<RefundError[]>([]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const v = validateRefund({
      status: order.status,
      lines: order.lines,
      selection,
      restock,
      method,
      reason,
    });
    if (!v.ok) {
      setErrors(v.errors);
      return;
    }
    setErrors([]);
    onSubmit(v.request);
  };

  return (
    <Panel title={t('bo.refund')}>
      <form onSubmit={submit} noValidate aria-label={t('bo.refund')}>
        <p className="hint">{t('refund.serverComputes')}</p>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t('order.item')}</th>
                <th scope="col" className="num">
                  {t('order.qty')}
                </th>
                <th scope="col" className="num">
                  {t('order.refundedQty')}
                </th>
                <th scope="col">{t('refund.qtyToRefund')}</th>
              </tr>
            </thead>
            <tbody>
              {order.lines.map((l) => {
                const rem = remainingQty(l);
                const id = `refund-qty-${l.lineNo}`;
                return (
                  <tr key={l.lineNo}>
                    <th scope="row">
                      <label htmlFor={id}>
                        #{l.lineNo} {l.name}
                      </label>
                    </th>
                    <td className="num">{l.quantity}</td>
                    <td className="num">{l.refundedQuantity}</td>
                    <td>
                      <input
                        id={id}
                        type="number"
                        min={0}
                        max={rem}
                        step={1}
                        className="qty"
                        disabled={rem === 0}
                        value={selection[l.lineNo] ?? 0}
                        onChange={(e) =>
                          setSelection((s) => setRefundQty(s, l, Number(e.target.value)))
                        }
                      />
                      <span className="muted small"> / {rem}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="btn-row">
          <button type="button" onClick={() => setSelection(selectAll(order.lines))}>
            {t('refund.selectAll')}
          </button>
          <button type="button" onClick={() => setSelection({})}>
            {t('refund.clear')}
          </button>
        </div>
        <div className="form-grid">
          <Field label={t('refund.method')} htmlFor="refund-method">
            <select
              id="refund-method"
              value={method}
              onChange={(e) => setMethod(e.target.value as PaymentMethod)}
            >
              <option value="CASH">{t('payment.cash')}</option>
              <option value="CARD">{t('payment.card')}</option>
            </select>
          </Field>
          <Field label={t('refund.reason')} htmlFor="refund-reason">
            <input
              id="refund-reason"
              maxLength={REASON_MAX}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
        </div>
        <label className="toggle">
          <input type="checkbox" checked={restock} onChange={(e) => setRestock(e.target.checked)} />
          {t('refund.restock')}
        </label>
        {errors.length > 0 ? (
          <ul className="form-errors" role="alert">
            {errors.map((e, i) => (
              <li key={i}>{errorText(e)}</li>
            ))}
          </ul>
        ) : null}
        <div className="btn-row">
          <button type="submit" className="btn-primary" disabled={disabled}>
            {t('refund.submit')}
          </button>
          <button type="button" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </form>
    </Panel>
  );
}
