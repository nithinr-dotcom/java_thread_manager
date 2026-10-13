// Display-only formatting. Money values are server-provided; never sum them client-side.

import { getLocale } from './i18n';

const moneyFormatters = new Map<string, Intl.NumberFormat>();

function moneyFormatter(currency: string, locale?: string): Intl.NumberFormat | null {
  const k = `${locale ?? ''}|${currency}`;
  let f = moneyFormatters.get(k);
  if (!f) {
    try {
      f = new Intl.NumberFormat(locale, { style: 'currency', currency });
    } catch {
      return null;
    }
    moneyFormatters.set(k, f);
  }
  return f;
}

/** Locale-aware money (locale defaults to the device locale; currency always from the API). */
export function formatMoney(
  amount: number,
  currency: string,
  locale: string = getLocale(),
): string {
  const f = moneyFormatter(currency, locale);
  // Unknown currency code: fall back to plain number + code.
  return f ? f.format(amount) : `${amount} ${currency}`;
}

/** Number of fraction digits of a currency (USD 2, JPY 0); 2 when unknown. */
export function currencyDigits(currency: string): number {
  try {
    return (
      new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits ?? 2
    );
  } catch {
    return 2;
  }
}

export function formatPercent(p: number, locale: string = getLocale()): string {
  try {
    return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 4 }).format(p)}%`;
  } catch {
    return `${p}%`;
  }
}

/** Compact age like "4s", "3m 12s", "2h 5m". Negative ages (clock skew) render as "0s". */
export function formatAge(fromIso: string | null | undefined, nowMs: number = Date.now()): string {
  if (!fromIso) return '—';
  const t = Date.parse(fromIso);
  if (Number.isNaN(t)) return '—';
  const s = Math.max(0, Math.floor((nowMs - t) / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

/** Ticket timer "m:ss" (or "h:mm:ss"). */
export function formatTimer(fromIso: string, nowMs: number = Date.now()): string {
  const t = Date.parse(fromIso);
  if (Number.isNaN(t)) return '—';
  const s = Math.max(0, Math.floor((nowMs - t) / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

function dateFormat(iso: string, opts: Intl.DateTimeFormatOptions, zone?: string): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  try {
    return new Intl.DateTimeFormat(getLocale(), {
      ...opts,
      ...(zone ? { timeZone: zone } : {}),
    }).format(new Date(t));
  } catch {
    return new Date(t).toISOString();
  }
}

export function formatTime(iso: string, zone?: string): string {
  return dateFormat(iso, { hour: '2-digit', minute: '2-digit', second: '2-digit' }, zone);
}

export function formatDateTime(iso: string, zone?: string): string {
  return dateFormat(iso, { dateStyle: 'short', timeStyle: 'medium' }, zone);
}

export function formatMs(ms: number): string {
  return `${Math.round(ms)} ms`;
}

/** First 12 hex chars of a fiscal hash for receipts ("—" when absent). */
export function shortHash(hash: string | null | undefined): string {
  return hash ? hash.slice(0, 12) : '—';
}
