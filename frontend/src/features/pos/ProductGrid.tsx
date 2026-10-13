import { useMemo, useState, type KeyboardEvent } from 'react';
import type { CatalogItem } from '../../api/types';
import type { Cart } from '../../domain/cart';
import { formatMoney } from '../../shared/formatting';
import { t } from '../../shared/i18n';

const ALL = '__all__';
export const LOW_STOCK = 10;

/** Distinct categories, sorted, for the tab bar. */
function categoriesOf(items: readonly CatalogItem[]): string[] {
  return [...new Set(items.map((i) => i.category))].sort((a, b) => a.localeCompare(b));
}

export function ProductGrid({
  items,
  cart,
  onAdd,
}: {
  items: readonly CatalogItem[];
  cart: Cart;
  onAdd: (item: CatalogItem) => void;
}) {
  const [category, setCategory] = useState(ALL);
  const [search, setSearch] = useState('');
  const categories = useMemo(() => categoriesOf(items), [items]);
  const tabs = [ALL, ...categories];
  const q = search.trim().toLowerCase();
  const visible = items.filter(
    (i) =>
      (category === ALL || i.category === category) &&
      (q === '' || i.name.toLowerCase().includes(q) || i.sku.toLowerCase().includes(q)),
  );
  const inCart = new Map(cart.map((l) => [l.sku, l.quantity]));

  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    const d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    const next = tabs[(i + d + tabs.length) % tabs.length];
    if (next !== undefined) {
      setCategory(next);
      document.getElementById(`cat-tab-${tabs.indexOf(next)}`)?.focus();
    }
  };

  return (
    <section className="pos-products" aria-label={t('pos.products')}>
      <div className="pos-toolbar">
        <div role="tablist" aria-label={t('pos.categories')} className="cat-tabs">
          {tabs.map((c, i) => (
            <button
              key={c}
              id={`cat-tab-${i}`}
              type="button"
              role="tab"
              aria-selected={c === category}
              tabIndex={c === category ? 0 : -1}
              className={`cat-tab ${c === category ? 'is-active' : ''}`}
              onClick={() => setCategory(c)}
              onKeyDown={(e) => onTabKey(e, i)}
            >
              {c === ALL ? t('pos.allCategories') : c}
            </button>
          ))}
        </div>
        <label className="sr-only" htmlFor="pos-search">
          {t('pos.search')}
        </label>
        <input
          id="pos-search"
          type="search"
          className="pos-search"
          placeholder={t('pos.search')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      {visible.length === 0 ? <p className="muted">{t('pos.noProducts')}</p> : null}
      <ul className="tile-grid" aria-label={t('pos.products')}>
        {visible.map((item) => {
          const out = item.onHand <= 0;
          const unavailable = !item.active || out;
          const qty = inCart.get(item.sku) ?? 0;
          return (
            <li key={item.sku}>
              <button
                type="button"
                className={`tile ${unavailable ? 'is-unavailable' : ''}`}
                data-sku={item.sku}
                onClick={() => onAdd(item)}
                disabled={unavailable}
                aria-label={t('pos.addItem', { name: item.name })}
              >
                <span className="tile-name">{item.name}</span>
                <span className="tile-price">{formatMoney(item.unitPrice, item.currency)}</span>
                <span className="tile-meta">
                  {!item.active ? (
                    <span className="badge badge-muted">{t('pos.inactive')}</span>
                  ) : out ? (
                    <span className="badge badge-bad">{t('pos.outOfStock')}</span>
                  ) : item.onHand < LOW_STOCK ? (
                    <span className="badge badge-warn">
                      {t('pos.lowStock', { count: item.onHand })}
                    </span>
                  ) : null}
                  {qty > 0 ? (
                    <span className="badge badge-info tile-qty">
                      {t('pos.inCart', { count: qty })}
                    </span>
                  ) : null}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
