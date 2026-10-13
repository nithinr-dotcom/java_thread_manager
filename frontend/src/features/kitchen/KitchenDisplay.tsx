import { useCallback, useMemo, useState } from 'react';
import { getCatalog, setTicketStatus } from '../../api/endpoints';
import type { Problem, TicketStatus } from '../../api/types';
import {
  ageLevel,
  allowedTransitions,
  columnTickets,
  COLUMNS,
  stationsOf,
  type BoardEntry,
  type BumpAction,
  type Column,
} from '../../domain/kitchen';
import { formatTimer } from '../../shared/formatting';
import { t, type MessageKey } from '../../shared/i18n';
import { loadString, saveString, STORAGE_KEYS } from '../../shared/storage';
import { Badge, Notice, ProblemView } from '../../shared/ui';
import { useNow } from '../../shared/useNow';
import { usePolling } from '../../shared/usePolling';
import { beep } from './beep';
import { useKitchenFeed } from './useKitchenFeed';

const ALL = '';

const COLUMN_LABEL: Record<Column, MessageKey> = {
  QUEUED: 'kds.col.QUEUED',
  IN_PROGRESS: 'kds.col.IN_PROGRESS',
  READY: 'kds.col.READY',
};

const ACTION_LABEL: Record<BumpAction, MessageKey> = {
  start: 'kds.action.start',
  ready: 'kds.action.ready',
  served: 'kds.action.served',
  recall: 'kds.action.recall',
};

export function KitchenDisplay() {
  const [station, setStation] = useState(() => loadString(STORAGE_KEYS.kdsStation, ALL));
  const [sound, setSound] = useState(() => loadString(STORAGE_KEYS.kdsSound, 'off') === 'on');
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const now = useNow(1000);

  const catalog = usePolling(
    useCallback(() => getCatalog(), []),
    60_000,
  );
  const stations = useMemo(() => stationsOf(catalog.data ?? []), [catalog.data]);

  const onNew = useCallback(() => {
    if (sound) beep();
  }, [sound]);
  const feed = useKitchenFeed(station || null, onNew);

  const chooseStation = (s: string) => {
    setStation(s);
    saveString(STORAGE_KEYS.kdsStation, s);
  };
  const toggleSound = (on: boolean) => {
    setSound(on);
    saveString(STORAGE_KEYS.kdsSound, on ? 'on' : 'off');
    if (on) beep(660, 80); // also unlocks audio on browsers that need a user gesture
  };

  const bump = async (entry: BoardEntry, to: TicketStatus) => {
    setBusyId(entry.ticket.id);
    setProblem(null);
    setNotice(null);
    const r = await setTicketStatus(entry.ticket.id, to);
    setBusyId(null);
    if (r.ok) {
      feed.apply(r.data);
      return;
    }
    if (r.problem.code === 'TICKET_STATE_CONFLICT' || r.status === 404) {
      setNotice(t('kds.conflict', { number: entry.ticket.orderNumber }));
      await feed.resync();
      return;
    }
    setProblem(r.problem);
  };

  const total = feed.board.size;

  return (
    <div className="kds">
      <h1 className="sr-only">{t('kds.title')}</h1>
      <div className="kds-toolbar">
        <div className="field-inline">
          <label htmlFor="kds-station">{t('kds.station')}</label>
          <select
            id="kds-station"
            className="input-lg"
            value={station}
            onChange={(e) => chooseStation(e.target.value)}
          >
            <option value={ALL}>{t('kds.allStations')}</option>
            {stations.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
            {station && !stations.includes(station) ? (
              <option value={station}>{station}</option>
            ) : null}
          </select>
        </div>
        <label className="toggle">
          <input type="checkbox" checked={sound} onChange={(e) => toggleSound(e.target.checked)} />
          {t('kds.sound')}
        </label>
        <span aria-live="polite" className="kds-conn">
          {feed.mode === 'live' ? (
            <Badge tone="ok">{t('kds.live')}</Badge>
          ) : feed.mode === 'polling' ? (
            <Badge tone="warn">{t('kds.polling')}</Badge>
          ) : (
            <Badge tone="muted">{t('kds.connecting')}</Badge>
          )}
        </span>
        <span className="muted">{t('kds.count', { count: total })}</span>
        <button type="button" className="btn-lg" onClick={() => void feed.resync()}>
          {t('common.refresh')}
        </button>
      </div>
      {problem ? <ProblemView problem={problem} /> : null}
      {notice ? <Notice tone="warn">{notice}</Notice> : null}

      <div className="kds-columns">
        {COLUMNS.map((col) => {
          const entries = columnTickets(feed.board, col);
          return (
            <section key={col} className={`kds-col kds-col-${col}`} aria-labelledby={`col-${col}`}>
              <h2 id={`col-${col}`} className="kds-col-head">
                {t(COLUMN_LABEL[col])} <span className="kds-col-count">{entries.length}</span>
              </h2>
              <ul className="kds-tickets" aria-label={t(COLUMN_LABEL[col])}>
                {entries.map((e) => (
                  <TicketCard
                    key={e.ticket.id}
                    entry={e}
                    now={now}
                    station={station || null}
                    busy={busyId === e.ticket.id}
                    onBump={(to) => void bump(e, to)}
                  />
                ))}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}

function TicketCard({
  entry,
  now,
  station,
  busy,
  onBump,
}: {
  entry: BoardEntry;
  now: number;
  station: string | null;
  busy: boolean;
  onBump: (to: TicketStatus) => void;
}) {
  const tk = entry.ticket;
  const cancelled = tk.status === 'CANCELLED';
  const level = ageLevel(tk.receivedAt, now);
  return (
    <li
      className={`kds-ticket age-${level} ${cancelled ? 'is-cancelled' : ''}`}
      data-testid={`ticket-${tk.orderNumber}`}
    >
      <header className="kds-ticket-head">
        <strong className="kds-order">{tk.orderNumber}</strong>
        <span className={`kds-timer timer-${level}`} aria-label={t('kds.age')}>
          {formatTimer(tk.receivedAt, now)}
        </span>
      </header>
      {cancelled ? <Badge tone="bad">{t('status.CANCELLED')}</Badge> : null}
      <ul className="kds-items">
        {tk.items.map((i) => (
          <li
            key={`${i.sku}-${i.station}`}
            className={station && i.station !== station ? 'other-station' : undefined}
          >
            <span className="kds-qty">{i.quantity}×</span> {i.name}
            {!station ? <span className="muted small"> · {i.station}</span> : null}
          </li>
        ))}
      </ul>
      {!cancelled ? (
        <div className="kds-actions">
          {allowedTransitions(tk.status).map((tr, idx) => (
            <button
              key={tr.action}
              type="button"
              className={idx === 0 ? 'btn-primary btn-bump' : 'btn-bump'}
              onClick={() => onBump(tr.to)}
              disabled={busy}
              aria-label={`${t(ACTION_LABEL[tr.action])} ${tk.orderNumber}`}
            >
              {t(ACTION_LABEL[tr.action])}
            </button>
          ))}
        </div>
      ) : null}
    </li>
  );
}
