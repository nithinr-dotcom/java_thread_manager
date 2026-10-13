import { useCallback, useState } from 'react';
import { createProduct, getCatalog, updateProduct } from '../../../api/endpoints';
import type { CatalogItem, Problem } from '../../../api/types';
import { formatMoney, formatPercent } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { useSession } from '../../../shared/session';
import { Badge, FetchError, Notice, Panel, ProblemView } from '../../../shared/ui';
import { usePolling } from '../../../shared/usePolling';
import { ProductForm, type ProductFormValues } from './ProductForm';

type Editing = { kind: 'none' } | { kind: 'create' } | { kind: 'edit'; item: CatalogItem };

export function CatalogSection() {
  const { me } = useSession();
  const catalog = usePolling(
    useCallback(() => getCatalog({ includeInactive: true }), []),
    30_000,
  );
  const [editing, setEditing] = useState<Editing>({ kind: 'none' });
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [conflict, setConflict] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const reset = () => {
    setProblem(null);
    setConflict(false);
    setNotice(null);
  };

  const save = async (v: ProductFormValues) => {
    reset();
    setBusy(true);
    const body = {
      name: v.name,
      unitPrice: v.unitPrice,
      taxRatePercent: v.taxRatePercent,
      requiresPreparation: v.requiresPreparation,
      station: v.station,
      category: v.category,
    };
    const r =
      editing.kind === 'edit'
        ? await updateProduct(editing.item.sku, {
            ...body,
            active: v.active,
            version: editing.item.version,
          })
        : await createProduct({ ...body, sku: v.sku });
    setBusy(false);
    if (r.ok) {
      setNotice(t('catalog.saved', { sku: r.data.sku }));
      setEditing({ kind: 'none' });
      catalog.refresh();
      return;
    }
    if (r.problem.code === 'VERSION_CONFLICT') setConflict(true);
    setProblem(r.problem);
  };

  /** Reload the product after a VERSION_CONFLICT and reopen the form with the fresh version. */
  const reloadConflict = async () => {
    if (editing.kind !== 'edit') return;
    const r = await getCatalog({ includeInactive: true });
    if (!r.ok) {
      setProblem(r.problem);
      return;
    }
    const fresh = r.data.find((i) => i.sku === editing.item.sku);
    reset();
    if (fresh) setEditing({ kind: 'edit', item: fresh });
    else setEditing({ kind: 'none' });
    catalog.refresh();
  };

  const toggleActive = async (item: CatalogItem) => {
    reset();
    setBusy(true);
    const r = await updateProduct(item.sku, {
      name: item.name,
      unitPrice: item.unitPrice,
      taxRatePercent: item.taxRatePercent,
      requiresPreparation: item.requiresPreparation,
      station: item.station,
      category: item.category,
      active: !item.active,
      version: item.version,
    });
    setBusy(false);
    if (r.ok) {
      setNotice(
        t(r.data.active ? 'catalog.activated' : 'catalog.deactivated', { sku: r.data.sku }),
      );
    } else {
      setProblem(r.problem);
      if (r.problem.code === 'VERSION_CONFLICT') setNotice(t('catalog.conflictReloaded'));
    }
    catalog.refresh();
  };

  const items = catalog.data ?? [];

  return (
    <div className="stack">
      {notice ? <Notice tone="ok">{notice}</Notice> : null}
      {problem ? <ProblemView problem={problem} /> : null}
      {conflict ? (
        <div className="banner banner-warn" role="alert">
          {t('catalog.conflict')}{' '}
          <button type="button" onClick={() => void reloadConflict()}>
            {t('catalog.reload')}
          </button>
        </div>
      ) : null}

      {editing.kind !== 'none' ? (
        <ProductForm
          key={editing.kind === 'edit' ? `${editing.item.sku}-${editing.item.version}` : 'new'}
          initial={editing.kind === 'edit' ? editing.item : null}
          currency={me.branch.currency}
          busy={busy}
          onSubmit={(v) => void save(v)}
          onCancel={() => {
            reset();
            setEditing({ kind: 'none' });
          }}
        />
      ) : null}

      <Panel
        title={t('catalog.products', { count: items.length })}
        actions={
          <>
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                reset();
                setEditing({ kind: 'create' });
              }}
            >
              {t('catalog.new')}
            </button>
            <button type="button" onClick={catalog.refresh}>
              {t('common.refresh')}
            </button>
          </>
        }
      >
        {catalog.result && !catalog.result.ok ? (
          <FetchError problem={catalog.result.problem} />
        ) : null}
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t('catalog.sku')}</th>
                <th scope="col">{t('catalog.name')}</th>
                <th scope="col">{t('catalog.category')}</th>
                <th scope="col">{t('catalog.station')}</th>
                <th scope="col" className="num">
                  {t('catalog.price')}
                </th>
                <th scope="col" className="num">
                  {t('catalog.taxRate')}
                </th>
                <th scope="col" className="num">
                  {t('catalog.onHand')}
                </th>
                <th scope="col">{t('catalog.state')}</th>
                <th scope="col">
                  <span className="sr-only">{t('common.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.sku} className={i.active ? undefined : 'row-inactive'}>
                  <td className="mono">{i.sku}</td>
                  <td>{i.name}</td>
                  <td>{i.category}</td>
                  <td>
                    {i.station}
                    {i.requiresPreparation ? null : (
                      <span className="muted small"> ({t('catalog.noPrep')})</span>
                    )}
                  </td>
                  <td className="num">{formatMoney(i.unitPrice, i.currency)}</td>
                  <td className="num">{formatPercent(i.taxRatePercent)}</td>
                  <td className="num">{i.onHand}</td>
                  <td>
                    <Badge tone={i.active ? 'ok' : 'muted'}>
                      {i.active ? t('catalog.active') : t('catalog.inactive')}
                    </Badge>
                  </td>
                  <td>
                    <div className="inline">
                      <button
                        type="button"
                        onClick={() => {
                          reset();
                          setEditing({ kind: 'edit', item: i });
                        }}
                        aria-label={t('catalog.editItem', { sku: i.sku })}
                      >
                        {t('common.edit')}
                      </button>
                      <button
                        type="button"
                        onClick={() => void toggleActive(i)}
                        disabled={busy}
                        aria-label={t(
                          i.active ? 'catalog.deactivateItem' : 'catalog.activateItem',
                          {
                            sku: i.sku,
                          },
                        )}
                      >
                        {i.active ? t('catalog.deactivate') : t('catalog.activate')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  );
}
