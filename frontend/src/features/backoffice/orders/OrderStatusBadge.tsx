import type { OrderStatus } from '../../../api/types';
import { t } from '../../../shared/i18n';
import type { Tone } from '../../../shared/tone';
import { Badge } from '../../../shared/ui';

const TONE: Record<OrderStatus, Tone> = {
  PLACED: 'warn',
  PAID: 'ok',
  PARTIALLY_REFUNDED: 'info',
  REFUNDED: 'muted',
  CANCELLED: 'bad',
};

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return <Badge tone={TONE[status] ?? 'muted'}>{t(`status.${status}`)}</Badge>;
}
