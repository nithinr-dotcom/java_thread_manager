import type { Problem, ProblemCode } from '../api/types';
import { t, type MessageKey } from './i18n';

const CODE_MESSAGES: Partial<Record<ProblemCode, MessageKey>> = {
  INVALID_REQUEST: 'problem.INVALID_REQUEST',
  UNAUTHENTICATED: 'problem.UNAUTHENTICATED',
  FORBIDDEN: 'problem.FORBIDDEN',
  TERMINAL_MISMATCH: 'problem.TERMINAL_MISMATCH',
  ORDER_NOT_FOUND: 'problem.ORDER_NOT_FOUND',
  PAYMENT_NOT_FOUND: 'problem.PAYMENT_NOT_FOUND',
  TICKET_NOT_FOUND: 'problem.TICKET_NOT_FOUND',
  TERMINAL_NOT_FOUND: 'problem.TERMINAL_NOT_FOUND',
  DEAD_LETTER_NOT_FOUND: 'problem.DEAD_LETTER_NOT_FOUND',
  OUT_OF_STOCK: 'problem.OUT_OF_STOCK',
  ORDER_NOT_PAYABLE: 'problem.ORDER_NOT_PAYABLE',
  ORDER_NOT_CANCELLABLE: 'problem.ORDER_NOT_CANCELLABLE',
  ORDER_NOT_REFUNDABLE: 'problem.ORDER_NOT_REFUNDABLE',
  TICKET_STATE_CONFLICT: 'problem.TICKET_STATE_CONFLICT',
  VERSION_CONFLICT: 'problem.VERSION_CONFLICT',
  PRODUCT_EXISTS: 'problem.PRODUCT_EXISTS',
  TERMINAL_EXISTS: 'problem.TERMINAL_EXISTS',
  CANNOT_REVOKE_SELF: 'problem.CANNOT_REVOKE_SELF',
  LAST_MANAGER: 'problem.LAST_MANAGER',
  PAYMENT_ALREADY_VOIDED: 'problem.PAYMENT_ALREADY_VOIDED',
  UNKNOWN_PRODUCT: 'problem.UNKNOWN_PRODUCT',
  PRODUCT_INACTIVE: 'problem.PRODUCT_INACTIVE',
  IDEMPOTENCY_KEY_REUSED: 'problem.IDEMPOTENCY_KEY_REUSED',
  CURRENCY_MISMATCH: 'problem.CURRENCY_MISMATCH',
  PAYMENT_EXCEEDS_DUE: 'problem.PAYMENT_EXCEEDS_DUE',
  INSUFFICIENT_TENDER: 'problem.INSUFFICIENT_TENDER',
  REFUND_EXCEEDS_SOLD: 'problem.REFUND_EXCEEDS_SOLD',
  UNKNOWN_ORDER_LINE: 'problem.UNKNOWN_ORDER_LINE',
  INTERNAL_ERROR: 'problem.INTERNAL_ERROR',
  BUSY_RETRY: 'problem.BUSY_RETRY',
  NETWORK_ERROR: 'problem.NETWORK_ERROR',
  TIMEOUT: 'problem.TIMEOUT',
};

/** A user-facing sentence for a problem (falls back to the server's title/detail). */
export function problemMessage(p: Problem): string {
  const key = p.code ? CODE_MESSAGES[p.code as ProblemCode] : undefined;
  if (key) return t(key, { sku: p.sku ?? '', requested: p.requested ?? '' });
  return p.detail ?? p.title ?? t('problem.unknown', { status: p.status });
}

/** True for outcomes where the server may or may not have committed (retry with the same key). */
export function isAmbiguous(status: number): boolean {
  return status === 0 || status === 408 || status === 429 || status >= 500;
}
