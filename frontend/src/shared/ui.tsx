import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import type { Problem } from '../api/types';
import { t } from './i18n';
import { problemMessage } from './problems';
import { statusTone, type Tone } from './tone';

export function Badge({ tone = 'muted', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function StatusBadge({ status }: { status: number }) {
  return <Badge tone={statusTone(status)}>{status === 0 ? 'NETWORK' : `HTTP ${status}`}</Badge>;
}

/** RFC 7807 problem: friendly sentence + stable code (+ server detail when it adds something). */
export function ProblemView({ problem }: { problem: Problem }) {
  const message = problemMessage(problem);
  const detail = problem.detail && problem.detail !== message ? problem.detail : null;
  return (
    <div className="problem" role="alert">
      <div className="problem-head">
        {problem.code ? <code className="problem-code">{problem.code}</code> : null}
        <strong>{message}</strong>
      </div>
      {detail ? <p className="problem-detail muted small">{detail}</p> : null}
    </div>
  );
}

export function Panel({
  title,
  actions,
  children,
  className,
  headingLevel = 2,
}: {
  title: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  headingLevel?: 2 | 3;
}) {
  const H = headingLevel === 2 ? 'h2' : 'h3';
  return (
    <section className={`panel ${className ?? ''}`}>
      <header className="panel-head">
        <H>{title}</H>
        {actions ? <div className="panel-actions">{actions}</div> : null}
      </header>
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** Small inline error for a failed fetch (network or HTTP). */
export function FetchError({ problem }: { problem: Problem | null | undefined }) {
  if (!problem) return null;
  return <ProblemView problem={problem} />;
}

/** Polite live region for action results ("Saved", "Restocked …"). */
export function Notice({ tone = 'info', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <p className={`notice notice-${tone}`} role="status" aria-live="polite">
      {children}
    </p>
  );
}

/**
 * A button that asks for confirmation in place (no window.confirm): first click reveals
 * "confirm / cancel" buttons, focus moves to the confirm button.
 */
export function ConfirmButton({
  label,
  ariaLabel,
  confirmLabel,
  question,
  onConfirm,
  disabled,
  danger = false,
}: {
  label: string;
  /** Accessible name of the first button when `label` alone is ambiguous (tables). */
  ariaLabel?: string;
  confirmLabel: string;
  question: string;
  onConfirm: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  const [asking, setAsking] = useState(false);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    if (asking) confirmRef.current?.focus();
  }, [asking]);
  if (!asking) {
    return (
      <button
        type="button"
        className={danger ? 'btn-danger' : undefined}
        onClick={() => setAsking(true)}
        disabled={disabled}
        aria-label={ariaLabel}
      >
        {label}
      </button>
    );
  }
  return (
    <span className="confirm" role="group" aria-labelledby={id}>
      <span id={id} className="confirm-question">
        {question}
      </span>
      <button
        ref={confirmRef}
        type="button"
        className={danger ? 'btn-danger' : 'btn-primary'}
        onClick={() => {
          setAsking(false);
          onConfirm();
        }}
        disabled={disabled}
      >
        {confirmLabel}
      </button>
      <button type="button" onClick={() => setAsking(false)}>
        {t('common.cancel')}
      </button>
    </span>
  );
}

/** Copy-to-clipboard button with feedback; falls back to selecting the text. */
export function CopyButton({ text, label }: { text: string; label?: string }) {
  const [copied, setCopied] = useState<boolean | null>(null);
  const copy = async () => {
    try {
      await globalThis.navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <>
      <button type="button" onClick={() => void copy()}>
        {label ?? t('common.copy')}
      </button>
      <span role="status" aria-live="polite" className="muted small">
        {copied === true ? t('common.copied') : copied === false ? t('common.copyFailed') : ''}
      </span>
    </>
  );
}

/** Labeled form field wrapper (label + control + optional hint/error). */
export function Field({
  label,
  htmlFor,
  hint,
  error,
  children,
}: {
  label: ReactNode;
  htmlFor: string;
  hint?: ReactNode;
  error?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="field">
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {hint ? (
        <span className="muted small" id={`${htmlFor}-hint`}>
          {hint}
        </span>
      ) : null}
      {error ? (
        <span className="field-error small" id={`${htmlFor}-error`} role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

export function Stat({ label, value }: { label: ReactNode; value: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}
