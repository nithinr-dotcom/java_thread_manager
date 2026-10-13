import { useCallback, useState } from 'react';
import { getCatalog } from '../../api/endpoints';
import { newAttempt } from '../../domain/idempotency';
import {
  analyzeDuplicateStorm,
  analyzeStockRace,
  countByBucket,
  isSuccess,
  latencyStats,
  type Check,
  type StressSample,
} from '../../domain/stress';
import { formatMs } from '../../shared/formatting';
import { Badge, FetchError, Panel } from '../../shared/ui';
import { usePolling } from '../../shared/usePolling';
import { checkout, fireBatch, latestOrderNumber, readStock, restock } from './runner';

interface Report {
  title: string;
  sku: string;
  n: number;
  stockBefore: number;
  stockAfter: number | null;
  elapsedMs: number;
  samples: StressSample[];
  checks: Check[];
  note?: string;
}

const clampN = (v: number) => (Number.isFinite(v) ? Math.min(500, Math.max(1, Math.trunc(v))) : 1);

export function StressLab() {
  const catalog = usePolling(
    useCallback(() => getCatalog(), []),
    5000,
  );
  const skus = (catalog.data ?? []).map((i) => i.sku);
  const [selectedSku, setSku] = useState('');
  const [raceN, setRaceN] = useState(50);
  const [stormN, setStormN] = useState(20);
  const [restockQty, setRestockQty] = useState(10);
  const [running, setRunning] = useState<string | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const sku = selectedSku || skus[0] || '';

  const current = catalog.data?.find((i) => i.sku === sku);

  const runRace = async () => {
    setRunning('race');
    setMessage(null);
    const stockBefore = await readStock(sku);
    if (stockBefore === null) {
      setMessage('Could not read stock before the race (backend unreachable or unknown SKU).');
      setRunning(null);
      return;
    }
    const previousOrderNumber = await latestOrderNumber();
    const keys = Array.from({ length: raceN }, () => newAttempt().key);
    const batch = await fireBatch(raceN, (i) =>
      checkout(keys[i] as string, { items: [{ sku, quantity: 1 }] }),
    );
    const stockAfter = await readStock(sku);
    setReport({
      title: 'Stock race',
      sku,
      n: raceN,
      stockBefore,
      stockAfter,
      elapsedMs: batch.elapsedMs,
      samples: batch.samples,
      checks: analyzeStockRace({
        samples: batch.samples,
        quantity: 1,
        stockBefore,
        stockAfter,
        previousOrderNumber,
      }),
      note: previousOrderNumber
        ? `Latest order before the race: ${previousOrderNumber}`
        : 'No previous order found — continuity with existing orders not checked.',
    });
    setRunning(null);
    catalog.refresh();
  };

  const runStorm = async () => {
    setRunning('storm');
    setMessage(null);
    const stockBefore = await readStock(sku);
    if (stockBefore === null) {
      setMessage('Could not read stock before the storm (backend unreachable or unknown SKU).');
      setRunning(null);
      return;
    }
    const key = newAttempt().key;
    const batch = await fireBatch(stormN, () => checkout(key, { items: [{ sku, quantity: 1 }] }));
    const stockAfter = await readStock(sku);
    setReport({
      title: 'Duplicate storm',
      sku,
      n: stormN,
      stockBefore,
      stockAfter,
      elapsedMs: batch.elapsedMs,
      samples: batch.samples,
      checks: analyzeDuplicateStorm({
        samples: batch.samples,
        quantity: 1,
        stockBefore,
        stockAfter,
      }),
      note: `Idempotency-Key ${key}`,
    });
    setRunning(null);
    catalog.refresh();
  };

  const runRestock = async () => {
    setRunning('restock');
    const r = await restock(sku, newAttempt().key, { quantity: restockQty });
    setMessage(
      r.ok
        ? `Restocked ${r.data.sku}: on hand now ${r.data.onHand}`
        : `Restock failed: ${r.status} ${r.problem.code ?? ''} ${r.problem.detail ?? r.problem.title ?? ''}`,
    );
    setRunning(null);
    catalog.refresh();
  };

  const disabled = running !== null || !sku;

  return (
    <div className="stack">
      <Panel title="Setup">
        {catalog.result && !catalog.result.ok ? (
          <FetchError problem={catalog.result.problem} />
        ) : null}
        <div className="form-grid">
          <div className="field">
            <label htmlFor="stress-sku">SKU</label>
            <select id="stress-sku" value={sku} onChange={(e) => setSku(e.target.value)}>
              {skus.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
            <span className="muted small">
              on hand: <strong>{current?.onHand ?? '—'}</strong>
            </span>
          </div>
          <div className="field">
            <label htmlFor="restock-qty">Restock quantity</label>
            <div className="inline">
              <input
                id="restock-qty"
                type="number"
                min={1}
                max={100000}
                value={restockQty}
                onChange={(e) =>
                  setRestockQty(
                    Math.min(100000, Math.max(1, Math.trunc(Number(e.target.value) || 1))),
                  )
                }
              />
              <button type="button" onClick={() => void runRestock()} disabled={disabled}>
                Restock
              </button>
            </div>
          </div>
        </div>
        {message ? (
          <p className="hint" role="status">
            {message}
          </p>
        ) : null}
        <p className="hint">
          Browsers cap concurrent HTTP/1.1 connections per origin (~6), so requests beyond that
          queue client-side; latencies include that queueing. Use the backend IT suite or a load
          tool for true 50-way parallelism.
        </p>
      </Panel>

      <div className="grid-2">
        <Panel title="Stock race (Scenarios A + B)">
          <p className="muted small">
            N concurrent checkouts of 1 × SKU, each with its own Idempotency-Key (all from this
            device). Expect exactly min(N, stock) successes, the rest 409 OUT_OF_STOCK, and a
            gap-free fiscal run. The orders stay unpaid (<code>PLACED</code>); cancel them in the
            back office if needed.
          </p>
          <div className="inline">
            <label htmlFor="race-n">Concurrent requests (N)</label>
            <input
              id="race-n"
              type="number"
              min={1}
              max={500}
              value={raceN}
              onChange={(e) => setRaceN(clampN(Number(e.target.value)))}
            />
            <button
              type="button"
              className="btn-primary"
              onClick={() => void runRace()}
              disabled={disabled}
            >
              {running === 'race' ? 'Running…' : 'Run stock race'}
            </button>
          </div>
        </Panel>
        <Panel title="Duplicate storm (Scenario C)">
          <p className="muted small">
            N identical requests — same Idempotency-Key, same basket — fired at once. Expect one
            201, the rest 200 replays of the same order, stock decremented once.
          </p>
          <div className="inline">
            <label htmlFor="storm-n">Identical requests (N)</label>
            <input
              id="storm-n"
              type="number"
              min={1}
              max={500}
              value={stormN}
              onChange={(e) => setStormN(clampN(Number(e.target.value)))}
            />
            <button
              type="button"
              className="btn-primary"
              onClick={() => void runStorm()}
              disabled={disabled}
            >
              {running === 'storm' ? 'Running…' : 'Run duplicate storm'}
            </button>
          </div>
        </Panel>
      </div>

      {report ? <ReportView report={report} /> : null}
    </div>
  );
}

function ReportView({ report }: { report: Report }) {
  const buckets = Object.entries(countByBucket(report.samples)).sort(([a], [b]) =>
    a < b ? -1 : 1,
  );
  const lat = latencyStats(report.samples.map((s) => s.elapsedMs));
  const allPass = report.checks.every((c) => c.pass);
  const numbers = report.samples
    .filter(isSuccess)
    .map((s) => s.orderNumber ?? '')
    .filter((v, i, a) => a.indexOf(v) === i)
    .sort();
  return (
    <Panel
      title={
        <>
          {report.title} — <code>{report.sku}</code> × {report.n}{' '}
          <Badge tone={allPass ? 'ok' : 'bad'}>{allPass ? 'PASS' : 'FAIL'}</Badge>
        </>
      }
    >
      <div className="stats">
        <Stat label="Stock before" value={report.stockBefore} />
        <Stat label="Stock after" value={report.stockAfter ?? '—'} />
        <Stat label="Elapsed" value={formatMs(report.elapsedMs)} />
        <Stat label="p50" value={formatMs(lat.p50)} />
        <Stat label="p95" value={formatMs(lat.p95)} />
        <Stat label="max" value={formatMs(lat.max)} />
      </div>
      {report.note ? <p className="muted small">{report.note}</p> : null}
      <div className="grid-2">
        <table className="table">
          <caption>Responses by status / code</caption>
          <thead>
            <tr>
              <th scope="col">Outcome</th>
              <th scope="col">Count</th>
            </tr>
          </thead>
          <tbody>
            {buckets.map(([k, v]) => (
              <tr key={k}>
                <td>
                  <code>{k}</code>
                </td>
                <td className="num">{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <ul className="checks" aria-label="Checks">
          {report.checks.map((c) => (
            <li key={c.id} className={c.pass ? 'check-pass' : 'check-fail'}>
              <Badge tone={c.pass ? 'ok' : 'bad'}>{c.pass ? 'PASS' : 'FAIL'}</Badge>{' '}
              <strong>{c.label}</strong>
              <div className="muted small">{c.detail}</div>
            </li>
          ))}
        </ul>
      </div>
      {numbers.length > 0 ? (
        <details>
          <summary>Order numbers ({numbers.length})</summary>
          <p className="mono small wrap">{numbers.join(' ')}</p>
        </details>
      ) : null}
    </Panel>
  );
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}
