import type { CatalogItem } from '../../api/types';
import { MAX_QTY, unitCount, type Cart } from '../../domain/cart';
import { formatMoney } from '../../shared/formatting';
import { t } from '../../shared/i18n';

export function CartPanel({
  cart,
  bySku,
  onInc,
  onDec,
  onRemove,
  onClear,
  onCheckout,
  checkoutDisabled,
  busy,
}: {
  cart: Cart;
  bySku: ReadonlyMap<string, CatalogItem>;
  onInc: (sku: string) => void;
  onDec: (sku: string) => void;
  onRemove: (sku: string) => void;
  onClear: () => void;
  onCheckout: () => void;
  checkoutDisabled: boolean;
  busy: boolean;
}) {
  const units = unitCount(cart);
  return (
    <section className="pos-cart panel" aria-labelledby="cart-heading">
      <header className="panel-head">
        <h2 id="cart-heading">{t('pos.cart')}</h2>
        <span className="muted small" aria-live="polite">
          {t('pos.units', { count: units })}
        </span>
      </header>
      <div className="panel-body">
        {cart.length === 0 ? (
          <p className="muted">{t('pos.cartEmpty')}</p>
        ) : (
          <ul className="cart-lines" aria-label={t('pos.cart')}>
            {cart.map((l) => {
              const item = bySku.get(l.sku);
              const name = item?.name ?? l.sku;
              const short = item && l.quantity > item.onHand;
              return (
                <li key={l.sku} className="cart-line">
                  <div className="cart-line-info">
                    <span className="cart-line-name">{name}</span>
                    <span className="muted small">
                      {item
                        ? t('pos.unitPrice', { price: formatMoney(item.unitPrice, item.currency) })
                        : l.sku}
                    </span>
                    {short ? (
                      <span className="hint warn">
                        {t('pos.onlyInStock', { count: Math.max(0, item.onHand) })}
                      </span>
                    ) : null}
                  </div>
                  <div className="stepper" role="group" aria-label={t('pos.quantityOf', { name })}>
                    <button
                      type="button"
                      className="step-btn"
                      onClick={() => (l.quantity <= 1 ? onRemove(l.sku) : onDec(l.sku))}
                      aria-label={
                        l.quantity <= 1
                          ? t('pos.removeItem', { name })
                          : t('pos.decrease', { name })
                      }
                    >
                      −
                    </button>
                    <output className="step-qty" aria-live="polite">
                      {l.quantity}
                    </output>
                    <button
                      type="button"
                      className="step-btn"
                      onClick={() => onInc(l.sku)}
                      disabled={l.quantity >= MAX_QTY}
                      aria-label={t('pos.increase', { name })}
                    >
                      +
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
        <p className="cart-total-note">{t('pos.totalAtCheckout')}</p>
        <div className="btn-row">
          <button
            type="button"
            className="btn-primary btn-xl"
            onClick={onCheckout}
            disabled={checkoutDisabled || cart.length === 0}
          >
            {busy ? t('pos.checkingOut') : t('pos.checkout')}
          </button>
          <button
            type="button"
            className="btn-lg"
            onClick={onClear}
            disabled={cart.length === 0 || busy}
          >
            {t('pos.clearCart')}
          </button>
        </div>
      </div>
    </section>
  );
}
