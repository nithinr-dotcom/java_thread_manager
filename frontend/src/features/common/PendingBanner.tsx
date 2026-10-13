import type { PendingKind } from '../../domain/idempotency';
import { t, type MessageKey } from '../../shared/i18n';
import { problemMessage } from '../../shared/problems';
import { ConfirmButton } from '../../shared/ui';
import type { PendingController } from './usePendingAction';

const KIND_LABEL: Record<PendingKind, MessageKey> = {
  checkout: 'pending.kind.checkout',
  payment: 'pending.kind.payment',
  void: 'pending.kind.void',
  cancel: 'pending.kind.cancel',
  refund: 'pending.kind.refund',
  restock: 'pending.kind.restock',
  adjust: 'pending.kind.adjust',
};

/**
 * Shows an action whose outcome is unknown: recovered after a reload, or after an ambiguous
 * failure. Retry re-sends with the SAME Idempotency-Key, so it can never be applied twice.
 */
export function PendingBanner({ ctl }: { ctl: PendingController }) {
  const p = ctl.pending;
  if (!p) return null;
  const what = t(KIND_LABEL[p.payload.kind]);
  let message: string;
  if (ctl.busy) message = t('pending.sending', { what });
  else if (ctl.recovered) message = t('pending.recovered', { what });
  else if (ctl.lastFailure)
    message = t('pending.unknown', { what, reason: problemMessage(ctl.lastFailure.problem) });
  else message = t('pending.notSent', { what });
  return (
    <div className="banner banner-warn pending-banner" role="alert" data-testid="pending-banner">
      <div className="pending-text">
        <strong>{t('pending.title', { what })}</strong> {message}
        {!ctl.busy && ctl.retryInMs !== null ? (
          <> {t('pending.autoRetry', { seconds: Math.ceil(ctl.retryInMs / 1000) })}</>
        ) : null}
        <div className="muted small">
          {t('pending.keyInfo', { key: p.key.slice(0, 8), sends: p.sends })}
        </div>
      </div>
      <div className="btn-row">
        <button
          type="button"
          className="btn-primary btn-lg"
          onClick={() => void ctl.retry()}
          disabled={ctl.busy}
        >
          {t('pending.retry')}
        </button>
        <ConfirmButton
          label={t('pending.discard')}
          confirmLabel={t('pending.discardConfirm')}
          question={t('pending.discardQuestion')}
          onConfirm={ctl.discard}
          disabled={ctl.busy}
          danger
        />
      </div>
    </div>
  );
}
