import { useCallback, useState, type FormEvent } from 'react';
import { listTerminals, registerTerminal, revokeTerminal } from '../../../api/endpoints';
import { ROLES, type Problem, type RegisteredTerminal, type Role } from '../../../api/types';
import { formatDateTime } from '../../../shared/formatting';
import { t } from '../../../shared/i18n';
import { useSession } from '../../../shared/session';
import {
  Badge,
  ConfirmButton,
  CopyButton,
  Field,
  FetchError,
  Notice,
  Panel,
  ProblemView,
} from '../../../shared/ui';
import { usePolling } from '../../../shared/usePolling';

const TERMINAL_RE = /^[A-Za-z0-9._:-]{1,64}$/;

export function DevicesSection() {
  const { me } = useSession();
  const terminals = usePolling(
    useCallback(() => listTerminals(), []),
    30_000,
  );
  const [terminalId, setTerminalId] = useState('');
  const [name, setName] = useState('');
  const [role, setRole] = useState<Role>('POS');
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [created, setCreated] = useState<RegisteredTerminal | null>(null);

  const idValid = TERMINAL_RE.test(terminalId.trim());
  const nameValid = name.trim().length >= 1 && name.trim().length <= 200;

  const register = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (!idValid || !nameValid) return;
    setBusy(true);
    setProblem(null);
    setNotice(null);
    const r = await registerTerminal({ terminalId: terminalId.trim(), name: name.trim(), role });
    setBusy(false);
    if (r.ok) {
      setCreated(r.data);
      setTerminalId('');
      setName('');
      setSubmitted(false);
      terminals.refresh();
    } else setProblem(r.problem);
  };

  const revoke = async (id: string) => {
    setProblem(null);
    setNotice(null);
    const r = await revokeTerminal(id);
    if (r.ok) setNotice(t('devices.revoked', { id }));
    else setProblem(r.problem);
    terminals.refresh();
  };

  return (
    <div className="stack">
      {problem ? <ProblemView problem={problem} /> : null}
      {notice ? <Notice tone="ok">{notice}</Notice> : null}

      {created ? (
        <div className="banner banner-warn key-once" role="alert">
          <strong>{t('devices.keyOnceTitle', { id: created.terminalId })}</strong>
          <p>{t('devices.keyOnceWarning')}</p>
          <div className="inline">
            <code className="api-key" data-testid="new-api-key">
              {created.apiKey}
            </code>
            <CopyButton text={created.apiKey} />
          </div>
          <div className="btn-row">
            <button type="button" onClick={() => setCreated(null)}>
              {t('devices.keyStored')}
            </button>
          </div>
        </div>
      ) : null}

      <Panel title={t('devices.register')}>
        <form onSubmit={(e) => void register(e)} noValidate>
          <div className="form-grid">
            <Field
              label={t('devices.terminalId')}
              htmlFor="dev-id"
              hint={t('devices.terminalIdHint')}
              error={submitted && !idValid ? t('devices.err.terminalId') : null}
            >
              <input
                id="dev-id"
                value={terminalId}
                spellCheck={false}
                onChange={(e) => setTerminalId(e.target.value)}
              />
            </Field>
            <Field
              label={t('devices.name')}
              htmlFor="dev-name"
              error={submitted && !nameValid ? t('devices.err.name') : null}
            >
              <input id="dev-name" value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field label={t('devices.role')} htmlFor="dev-role">
              <select id="dev-role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {t(`role.${r}`)}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <button type="submit" className="btn-primary" disabled={busy}>
            {t('devices.registerButton')}
          </button>
        </form>
      </Panel>

      <Panel
        title={t('devices.list')}
        actions={
          <button type="button" onClick={terminals.refresh}>
            {t('common.refresh')}
          </button>
        }
      >
        {terminals.result && !terminals.result.ok ? (
          <FetchError problem={terminals.result.problem} />
        ) : null}
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">{t('devices.terminalId')}</th>
                <th scope="col">{t('devices.name')}</th>
                <th scope="col">{t('devices.role')}</th>
                <th scope="col">{t('devices.state')}</th>
                <th scope="col">{t('devices.createdAt')}</th>
                <th scope="col">
                  <span className="sr-only">{t('common.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {(terminals.data ?? []).map((d) => {
                const self = d.terminalId === me.terminalId;
                return (
                  <tr key={d.terminalId}>
                    <td className="mono">
                      {d.terminalId}
                      {self ? (
                        <span className="muted small"> ({t('devices.thisDevice')})</span>
                      ) : null}
                    </td>
                    <td>{d.name}</td>
                    <td>{t(`role.${d.role}`)}</td>
                    <td>
                      {d.active ? (
                        <Badge tone="ok">{t('devices.active')}</Badge>
                      ) : (
                        <Badge tone="muted">
                          {t('devices.revokedAt', {
                            at: d.revokedAt ? formatDateTime(d.revokedAt) : '—',
                          })}
                        </Badge>
                      )}
                    </td>
                    <td>{formatDateTime(d.createdAt)}</td>
                    <td>
                      {d.active && !self ? (
                        <ConfirmButton
                          label={t('devices.revoke')}
                          confirmLabel={t('devices.revokeConfirm')}
                          question={t('devices.revokeQuestion', { id: d.terminalId })}
                          onConfirm={() => void revoke(d.terminalId)}
                          danger
                        />
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Panel>
    </div>
  );
}
