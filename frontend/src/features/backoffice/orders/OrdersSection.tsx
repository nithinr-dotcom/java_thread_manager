import { useCallback, useState } from 'react';
import { listOrders } from '../../../api/endpoints';
import { ORDER_STATUSES, type OrderStatus } from '../../../api/types';
import { formatDateTime, formatMoney } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { FetchError, Panel } from '../../../shared/ui';
import { usePolling } from '../../../shared/usePolling';
import { go } from '../../common/nav';
import { OrderStatusBadge } from './OrderStatusBadge';

export function OrdersSection() {
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const orders = usePolling(
    useCallback(() => listOrders({ limit: 50, status: status || null }), [status]),
    10_000,
  );
  const rows = orders.data ?? [];
  return (
    <Panel
      title={t('bo.recentOrders')}
      actions={
        <>
          <label htmlFor="order-status-filter">{t('bo.statusFilter')}</label>
          <select
            id="order-status-filter"
            value={status}
            onChange={(e) => setStatus(e.target.value as OrderStatus | '')}
          >
            <option value="">{t('bo.allStatuses')}</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`status.${s}`)}
              </option>
            ))}
          </select>
          <button type="button" onClick={orders.refresh}>
            {t('common.refresh')}
          </button>
        </>
      }
    >
      {orders.result && !orders.result.ok ? <FetchError problem={orders.result.problem} /> : null}
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th scope="col">{t('order.number')}</th>
              <th scope="col">{t('order.placedAt')}</th>
              <th scope="col">{t('order.terminal')}</th>
              <th scope="col">{t('order.status')}</th>
              <th scope="col" className="num">
                {t('order.total')}
              </th>
              <th scope="col" className="num">
                {t('order.amountDue')}
              </th>
              <th scope="col">
                <span className="sr-only">{t('common.actions')}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((o) => (
              <tr key={o.id}>
                <td className="mono">{o.orderNumber}</td>
                <td>{formatDateTime(o.placedAt)}</td>
                <td>{o.terminalId}</td>
                <td>
                  <OrderStatusBadge status={o.status} />
                </td>
                <td className="num">{formatMoney(o.total, o.currency)}</td>
                <td className="num">{formatMoney(o.amountDue, o.currency)}</td>
                <td>
                  <button
                    type="button"
                    onClick={() => go({ app: 'backoffice', section: 'orders', id: o.id })}
                    aria-label={t('bo.openOrder', { number: o.orderNumber })}
                  >
                    {t('common.open')}
                  </button>
                </td>
              </tr>
            ))}
            {orders.data && rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="muted">
                  {t('bo.noOrders')}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
