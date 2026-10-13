import { useState, type FormEvent } from 'react';
import type { CatalogItem } from '../../../api/types';
import { amountToInput, parseAmount } from '../../../domain/decimal';
import { currencyDigits } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { Field, Panel } from '../../../shared/ui';

export interface ProductFormValues {
  sku: string;
  name: string;
  unitPrice: number;
  taxRatePercent: number;
  requiresPreparation: boolean;
  station: string;
  category: string;
  active: boolean;
}

const SKU_RE = /^[A-Z0-9][A-Z0-9_-]{0,63}$/;

export function ProductForm({
  initial,
  currency,
  busy,
  onSubmit,
  onCancel,
}: {
  initial: CatalogItem | null;
  currency: string;
  busy: boolean;
  onSubmit: (v: ProductFormValues) => void;
  onCancel: () => void;
}) {
  const digits = currencyDigits(currency);
  const [sku, setSku] = useState(initial?.sku ?? '');
  const [name, setName] = useState(initial?.name ?? '');
  const [price, setPrice] = useState(initial ? amountToInput(initial.unitPrice, digits) : '');
  const [tax, setTax] = useState(initial ? String(initial.taxRatePercent) : '');
  const [prep, setPrep] = useState(initial?.requiresPreparation ?? true);
  const [station, setStation] = useState(initial?.station ?? 'KITCHEN');
  const [category, setCategory] = useState(initial?.category ?? 'General');
  const [active, setActive] = useState(initial?.active ?? true);
  const [submitted, setSubmitted] = useState(false);

  const skuNorm = sku.trim().toUpperCase();
  const priceRes = parseAmount(price, digits);
  const taxRes = parseAmount(tax, 4);
  const errors = {
    sku: initial === null && !SKU_RE.test(skuNorm) ? t('catalog.err.sku') : null,
    name: name.trim().length < 1 || name.trim().length > 200 ? t('catalog.err.name') : null,
    price: !priceRes.ok ? t('catalog.err.price') : null,
    tax: !taxRes.ok || taxRes.value > 100 ? t('catalog.err.tax') : null,
    station:
      station.trim().length < 1 || station.trim().length > 32 ? t('catalog.err.station') : null,
    category:
      category.trim().length < 1 || category.trim().length > 64 ? t('catalog.err.category') : null,
  };
  const valid = Object.values(errors).every((e) => e === null);
  const show = (e: string | null) => (submitted ? e : null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (!valid || !priceRes.ok || !taxRes.ok) return;
    onSubmit({
      sku: skuNorm,
      name: name.trim(),
      unitPrice: priceRes.value,
      taxRatePercent: taxRes.value,
      requiresPreparation: prep,
      station: station.trim(),
      category: category.trim(),
      active,
    });
  };

  return (
    <Panel title={initial ? t('catalog.editTitle', { sku: initial.sku }) : t('catalog.new')}>
      <form
        onSubmit={submit}
        noValidate
        aria-label={initial ? t('catalog.editTitle', { sku: initial.sku }) : t('catalog.new')}
      >
        <div className="form-grid">
          {initial === null ? (
            <Field
              label={t('catalog.sku')}
              htmlFor="pf-sku"
              error={show(errors.sku)}
              hint={t('catalog.skuHint')}
            >
              <input id="pf-sku" value={sku} onChange={(e) => setSku(e.target.value)} />
            </Field>
          ) : null}
          <Field label={t('catalog.name')} htmlFor="pf-name" error={show(errors.name)}>
            <input id="pf-name" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field
            label={t('catalog.priceIn', { currency })}
            htmlFor="pf-price"
            error={show(errors.price)}
          >
            <input
              id="pf-price"
              inputMode="decimal"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
            />
          </Field>
          <Field label={t('catalog.taxRate')} htmlFor="pf-tax" error={show(errors.tax)}>
            <input
              id="pf-tax"
              inputMode="decimal"
              value={tax}
              onChange={(e) => setTax(e.target.value)}
            />
          </Field>
          <Field label={t('catalog.category')} htmlFor="pf-category" error={show(errors.category)}>
            <input
              id="pf-category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
            />
          </Field>
          <Field label={t('catalog.station')} htmlFor="pf-station" error={show(errors.station)}>
            <input id="pf-station" value={station} onChange={(e) => setStation(e.target.value)} />
          </Field>
        </div>
        <div className="inline">
          <label className="toggle">
            <input type="checkbox" checked={prep} onChange={(e) => setPrep(e.target.checked)} />
            {t('catalog.requiresPrep')}
          </label>
          {initial ? (
            <label className="toggle">
              <input
                type="checkbox"
                checked={active}
                onChange={(e) => setActive(e.target.checked)}
              />
              {t('catalog.active')}
            </label>
          ) : null}
        </div>
        {initial ? <p className="hint">{t('catalog.priceHint')}</p> : null}
        <div className="btn-row">
          <button type="submit" className="btn-primary" disabled={busy}>
            {t('common.save')}
          </button>
          <button type="button" onClick={onCancel}>
            {t('common.cancel')}
          </button>
        </div>
      </form>
    </Panel>
  );
}
