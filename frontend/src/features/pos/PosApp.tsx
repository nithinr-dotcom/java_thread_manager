import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ApiResult } from '../../api/client';
import { getCatalog, getOrder } from '../../api/endpoints';
import type { CatalogItem, Order, Payment, PaymentResponse, Problem } from '../../api/types';
import {
  addItem,
  emptyCart,
  removeItem,
  setQuantity,
  toCheckoutItems,
  type Cart,
} from '../../domain/cart';
import type { PendingPayload } from '../../domain/idempotency';
import { formatMoney } from '../../shared/formatting';
import { t } from '../../shared/i18n';
import { isOffline, useReachability } from '../../shared/reachability';
import { useSession } from '../../shared/session';
import { loadRaw, removeKey, saveString, STORAGE_KEYS } from '../../shared/storage';
import { FetchError, Notice, ProblemView } from '../../shared/ui';
import { usePolling } from '../../shared/usePolling';
import { PendingBanner } from '../common/PendingBanner';
import { Receipt } from '../common/Receipt';
import { usePendingAction } from '../common/usePendingAction';
import { CartPanel } from './CartPanel';
import { OrderSummary, PaymentPanel } from './PaymentPanel';
import { ProductGrid } from './ProductGrid';

type Screen =
  | { kind: 'sale' }
  | { kind: 'payment'; order: Order; lastPayment: Payment | null }
  | { kind: 'receipt'; order: Order; lastPayment: Payment | null };

export const POS_SLOT = 'pos';

export function PosApp() {
  const { me } = useSession();
  const { state: reach } = useReachability();
  const offline = isOffline(reach);
  const [cart, setCart] = useState<Cart>(emptyCart);
  const [screen, setScreen] = useState<Screen>({ kind: 'sale' });
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const catalog = usePolling(
    useCallback(() => getCatalog(), []),
    15_000,
  );
  const items = useMemo(() => catalog.data ?? [], [catalog.data]);
  const bySku = useMemo(() => new Map(items.map((i) => [i.sku, i])), [items]);

  const showOrder = useCallback((order: Order, lastPayment: Payment | null) => {
    if (order.status === 'PLACED') {
      saveString(STORAGE_KEYS.posActiveOrder, order.id);
      setScreen({ kind: 'payment', order, lastPayment });
    } else {
      removeKey(STORAGE_KEYS.posActiveOrder);
      if (order.status === 'CANCELLED') {
        setNotice(t('pos.orderCancelled', { number: order.orderNumber }));
        setScreen({ kind: 'sale' });
      } else {
        setScreen({ kind: 'receipt', order, lastPayment });
      }
    }
  }, []);

  const onSettled = useCallback(
    (payload: PendingPayload, r: ApiResult<unknown>) => {
      if (!r.ok) {
        setProblem(r.problem);
        // The order changed under us (paid / cancelled elsewhere): reload its current state.
        if (payload.kind !== 'checkout' && r.status === 409 && 'orderId' in payload) {
          void getOrder(payload.orderId).then((o) => {
            if (o.ok) showOrder(o.data, null);
          });
        }
        return;
      }
      setProblem(null);
      switch (payload.kind) {
        case 'checkout':
          setCart(emptyCart);
          showOrder(r.data as Order, null);
          break;
        case 'payment': {
          const pr = r.data as PaymentResponse;
          showOrder(pr.order, pr.payment);
          break;
        }
        case 'void': {
          const o = r.data as Order;
          setNotice(t('payment.voidedNotice', { due: formatMoney(o.amountDue, o.currency) }));
          showOrder(o, null);
          break;
        }
        case 'cancel':
          showOrder(r.data as Order, null);
          break;
        default:
          break;
      }
      catalog.refresh();
    },
    [catalog, showOrder],
  );

  const pending = usePendingAction(POS_SLOT, onSettled);

  // Resume an order that was being paid when the page was reloaded.
  useEffect(() => {
    const id = loadRaw(STORAGE_KEYS.posActiveOrder);
    if (!id) return;
    let cancelled = false;
    void getOrder(id).then((r) => {
      if (cancelled) return;
      if (r.ok && r.data.status === 'PLACED')
        setScreen({ kind: 'payment', order: r.data, lastPayment: null });
      else if (r.ok || r.status === 404) removeKey(STORAGE_KEYS.posActiveOrder);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Move focus to the screen heading when the POS screen changes (keyboard / screen readers).
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    headingRef.current?.focus();
  }, [screen.kind]);

  const start = (payload: PendingPayload) => {
    setProblem(null);
    setNotice(null);
    void pending.start(payload);
  };

  const add = (item: CatalogItem) => {
    setNotice(null);
    setCart((c) => addItem(c, item.sku));
  };

  const newSale = () => {
    removeKey(STORAGE_KEYS.posActiveOrder);
    setProblem(null);
    setNotice(null);
    setScreen({ kind: 'sale' });
  };

  const actionBlocked = pending.pending !== null || pending.busy;

  return (
    <div className="pos">
      <h1 className="sr-only" tabIndex={-1} ref={headingRef}>
        {screen.kind === 'sale'
          ? t('pos.title')
          : screen.kind === 'payment'
            ? t('payment.title')
            : t('receipt.title')}
      </h1>
      {offline ? (
        <div className="banner" role="alert">
          {t('pos.offline')}
        </div>
      ) : null}
      <PendingBanner ctl={pending} />
      {problem ? <ProblemView problem={problem} /> : null}
      {notice ? <Notice tone="ok">{notice}</Notice> : null}

      {screen.kind === 'sale' ? (
        <div className="pos-sale">
          <div className="stack">
            {catalog.result && !catalog.result.ok && !catalog.data ? (
              <FetchError problem={catalog.result.problem} />
            ) : null}
            <ProductGrid items={items} cart={cart} onAdd={add} />
          </div>
          <CartPanel
            cart={cart}
            bySku={bySku}
            onInc={(sku) => setCart((c) => addItem(c, sku))}
            onDec={(sku) =>
              setCart((c) => setQuantity(c, sku, (c.find((l) => l.sku === sku)?.quantity ?? 1) - 1))
            }
            onRemove={(sku) => setCart((c) => removeItem(c, sku))}
            onClear={() => setCart(emptyCart)}
            onCheckout={() => start({ kind: 'checkout', items: toCheckoutItems(cart) })}
            checkoutDisabled={offline || actionBlocked}
            busy={pending.busy && pending.pending?.payload.kind === 'checkout'}
          />
        </div>
      ) : null}

      {screen.kind === 'payment' ? (
        <div className="pos-pay">
          <section className="panel" aria-labelledby="pay-order-heading">
            <header className="panel-head">
              <h2 id="pay-order-heading">
                {t('payment.orderHeading', { number: screen.order.orderNumber })}
              </h2>
            </header>
            <div className="panel-body">
              <OrderSummary order={screen.order} />
            </div>
          </section>
          <section className="panel" aria-labelledby="pay-heading">
            <header className="panel-head">
              <h2 id="pay-heading">{t('payment.title')}</h2>
            </header>
            <div className="panel-body">
              <PaymentPanel
                order={screen.order}
                lastPayment={screen.lastPayment}
                busy={pending.busy}
                disabled={offline || actionBlocked}
                onPay={(request) => start({ kind: 'payment', orderId: screen.order.id, request })}
                onCancel={(reason) => start({ kind: 'cancel', orderId: screen.order.id, reason })}
                onVoid={(paymentId, reason) =>
                  start({ kind: 'void', orderId: screen.order.id, paymentId, reason })
                }
                zone={me.branch.zone}
              />
            </div>
          </section>
        </div>
      ) : null}

      {screen.kind === 'receipt' ? (
        <div className="pos-receipt">
          {screen.lastPayment?.change != null ? (
            <div className="change-box change-final" role="status" aria-live="polite">
              {t('payment.changeDue', {
                amount: formatMoney(screen.lastPayment.change, screen.order.currency),
              })}
            </div>
          ) : null}
          <Receipt order={screen.order} branchCode={me.branch.code} zone={me.branch.zone} />
          <div className="btn-row no-print">
            <button type="button" className="btn-lg" onClick={() => window.print()}>
              {t('receipt.print')}
            </button>
            <button type="button" className="btn-primary btn-xl" onClick={newSale}>
              {t('pos.newSale')}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
