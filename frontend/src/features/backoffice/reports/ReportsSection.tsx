import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { getDailyReport, getFiscalChain } from '../../../api/endpoints';
import type { DailyReport, FiscalChainReport, Problem } from '../../../api/types';
import { businessDate, isBusinessDate } from '../../../domain/dates';
import { formatMoney, formatPercent } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { useSession } from '../../../shared/session';
import { Badge, Field, FetchError, Panel, Stat } from '../../../shared/ui';

export function ReportsSection() {
  const { me } = useSession();
  const [date, setDate] = useState(() => businessDate(me.branch.zone));
  const [daily, setDaily] = useState<DailyReport | null>(null);
  const [chain, setChain] = useState<FiscalChainReport | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (d: string) => {
    setBusy(true);
    const [a, b] = await Promise.all([getDailyReport(d), getFiscalChain(d)]);
    setBusy(false);
    setDaily(a.ok ? a.data : null);
    setChain(b.ok ? b.data : null);
    setProblem(!a.ok ? a.problem : !b.ok ? b.problem : null);
  }, []);

  useEffect(() => {
    void load(businessDate(me.branch.zone));
  }, [load, me.branch.zone]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (isBusinessDate(date)) void load(date);
  };

  return (
    <div className="stack">
      <Panel title={t('reports.choose')}>
        <form className="inline" onSubmit={submit}>
          <Field
            label={t('reports.date')}
            htmlFor="report-date"
            hint={t('reports.zone', { zone: me.branch.zone })}
          >
            <input
              id="report-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <button type="submit" className="btn-primary" disabled={busy || !isBusinessDate(date)}>
            {t('reports.load')}
          </button>
          <button type="button" onClick={() => window.print()} disabled={!daily}>
            {t('receipt.print')}
          </button>
        </form>
      </Panel>
      {problem ? <FetchError problem={problem} /> : null}
      {daily ? <DailyView r={daily} /> : null}
      {chain ? <ChainView r={chain} /> : null}
    </div>
  );
}

function DailyView({ r }: { r: DailyReport }) {
  const m = (v: number) => formatMoney(v, r.currency);
  return (
    <Panel
      title={t('reports.zReport', { date: r.businessDate, branch: r.branchCode })}
      className="printable"
    >
      <div className="stats" aria-live="polite">
        <Stat label={t('reports.ordersIssued')} value={r.orders.issued} />
        <Stat label={t('reports.ordersCancelled')} value={r.orders.cancelled} />
        <Stat label={t('order.subtotal')} value={m(r.sales.subtotal)} />
        <Stat label={t('order.tax')} value={m(r.sales.taxTotal)} />
        <Stat
          label={t('reports.salesTotal')}
          value={<span data-testid="report-total">{m(r.sales.total)}</span>}
        />
        <Stat label={t('reports.refunds', { count: r.refunds.count })} value={m(r.refunds.total)} />
        <Stat label={t('reports.unpaid')} value={m(r.unpaidTotal)} />
      </div>
      <p className="muted small">
        {t('reports.numberRange', {
          first: r.orders.firstNumber ?? '—',
          last: r.orders.lastNumber ?? '—',
        })}
      </p>
      <div className="grid-2">
        <table className="table">
          <caption>{t('reports.taxByRate')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('order.taxRate')}</th>
              <th scope="col" className="num">
                {t('reports.net')}
              </th>
              <th scope="col" className="num">
                {t('order.tax')}
              </th>
            </tr>
          </thead>
          <tbody>
            {r.taxByRate.map((x) => (
              <tr key={x.taxRatePercent}>
                <th scope="row">{formatPercent(x.taxRatePercent)}</th>
                <td className="num">{m(x.net)}</td>
                <td className="num">{m(x.tax)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <table className="table">
          <caption>{t('order.payments')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('payment.method')}</th>
              <th scope="col" className="num">
                {t('reports.count')}
              </th>
              <th scope="col" className="num">
                {t('payment.amount')}
              </th>
            </tr>
          </thead>
          <tbody>
            {r.payments.map((p) => (
              <tr key={p.method}>
                <th scope="row">{p.method === 'CASH' ? t('payment.cash') : t('payment.card')}</th>
                <td className="num">{p.count}</td>
                <td className="num">{m(p.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}

function ChainView({ r }: { r: FiscalChainReport }) {
  const allValid = r.series.every((s) => s.valid);
  return (
    <Panel
      title={
        <>
          {t('reports.fiscalChain', { date: r.businessDate })}{' '}
          <Badge tone={allValid ? 'ok' : 'bad'}>
            {allValid ? t('reports.chainValid') : t('reports.chainBroken')}
          </Badge>
        </>
      }
    >
      <table className="table">
        <thead>
          <tr>
            <th scope="col">{t('reports.series')}</th>
            <th scope="col" className="num">
              {t('reports.documents')}
            </th>
            <th scope="col">{t('reports.result')}</th>
            <th scope="col">{t('reports.firstBroken')}</th>
          </tr>
        </thead>
        <tbody>
          {r.series.map((s) => (
            <tr key={s.series}>
              <th scope="row">{s.series}</th>
              <td className="num">{s.documents}</td>
              <td>
                <Badge tone={s.valid ? 'ok' : 'bad'}>
                  {s.valid ? t('reports.valid') : t('reports.broken')}
                </Badge>
              </td>
              <td className="mono">{s.firstBrokenNumber ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
