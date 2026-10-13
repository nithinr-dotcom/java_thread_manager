// Minimal i18n: one message catalog per language, `{name}` interpolation, no dependency.
// Only `en` ships today; add a catalog with the same keys (typed) to add a language.

import { en } from './messages.en';

export type MessageKey = keyof typeof en;
export type Messages = Record<MessageKey, string>;
export type MessageParams = Record<string, string | number>;

const catalogs: Record<string, Messages> = { en };

/** BCP 47 locale for Intl formatting (from the browser), e.g. "en-US", "de-DE". */
export function getLocale(): string {
  const nav = globalThis.navigator;
  const l = nav?.languages?.[0] ?? nav?.language;
  return typeof l === 'string' && l !== '' ? l : 'en';
}

function catalogFor(locale: string): Messages {
  const lang = locale.toLowerCase().split('-')[0] ?? 'en';
  return catalogs[lang] ?? en;
}

/** Translate `key`, replacing `{param}` placeholders. Unknown params are left as-is. */
export function t(key: MessageKey, params?: MessageParams): string {
  const template = catalogFor(getLocale())[key] ?? en[key] ?? key;
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (m, name: string) =>
    params[name] !== undefined ? String(params[name]) : m,
  );
}
