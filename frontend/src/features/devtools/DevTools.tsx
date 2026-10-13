import { useState } from 'react';
import type { Route } from '../../domain/routes';
import { t, type MessageKey } from '../../shared/i18n';
import { Panel } from '../../shared/ui';
import { go } from '../common/nav';
import { StressLab } from '../stress/StressLab';
import { IdempotencyDemo } from './IdempotencyDemo';

type Tool = 'idempotency' | 'stress';

interface Scenario {
  id: string;
  title: MessageKey;
  how: MessageKey;
  open: { label: MessageKey; to: Tool | Route }[];
}

/** The five scenarios of the brief and where each one can be tried in this UI. */
const SCENARIOS: readonly Scenario[] = [
  {
    id: 'A',
    title: 'guide.a.title',
    how: 'guide.a.how',
    open: [{ label: 'dev.stressTitle', to: 'stress' }],
  },
  {
    id: 'B',
    title: 'guide.b.title',
    how: 'guide.b.how',
    open: [
      { label: 'dev.stressTitle', to: 'stress' },
      { label: 'guide.open.reports', to: { app: 'backoffice', section: 'reports', id: null } },
    ],
  },
  {
    id: 'C',
    title: 'guide.c.title',
    how: 'guide.c.how',
    open: [
      { label: 'dev.idempotencyTitle', to: 'idempotency' },
      { label: 'dev.stressTitle', to: 'stress' },
    ],
  },
  {
    id: 'D',
    title: 'guide.d.title',
    how: 'guide.d.how',
    open: [
      { label: 'guide.open.pos', to: { app: 'pos' } },
      { label: 'guide.open.kitchen', to: { app: 'kitchen' } },
      {
        label: 'guide.open.outbox',
        to: { app: 'backoffice', section: 'operations', id: null },
      },
    ],
  },
  {
    id: 'E',
    title: 'guide.e.title',
    how: 'guide.e.how',
    open: [
      { label: 'guide.open.pos', to: { app: 'pos' } },
      { label: 'guide.open.reports', to: { app: 'backoffice', section: 'reports', id: null } },
    ],
  },
];

/** MANAGER-only test tools. They create real orders on the node — never use them in service. */
export function DevTools() {
  const [tool, setTool] = useState<Tool>('stress');
  const open = (to: Tool | Route) => {
    if (typeof to === 'string') {
      setTool(to);
      document.getElementById('dev-tool')?.scrollIntoView?.({ block: 'start' });
    } else {
      go(to);
    }
  };
  return (
    <div className="devtools">
      <h1 className="page-title">{t('dev.title')}</h1>
      <div className="banner banner-warn" role="note">
        {t('dev.warning')}
      </div>
      <Panel title={t('guide.title')}>
        <table className="table">
          <thead>
            <tr>
              <th scope="col">{t('guide.scenario')}</th>
              <th scope="col">{t('guide.how')}</th>
              <th scope="col">{t('guide.where')}</th>
            </tr>
          </thead>
          <tbody>
            {SCENARIOS.map((s) => (
              <tr key={s.id}>
                <th scope="row">
                  {s.id} · {t(s.title)}
                </th>
                <td>{t(s.how)}</td>
                <td>
                  <div className="inline">
                    {s.open.map((o) => (
                      <button key={o.label} type="button" onClick={() => open(o.to)}>
                        {t(o.label)}
                      </button>
                    ))}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
      <nav id="dev-tool" className="subnav" aria-label={t('dev.title')}>
        {(['stress', 'idempotency'] as const).map((x) => (
          <button
            key={x}
            type="button"
            className={`subnav-link ${tool === x ? 'is-active' : ''}`}
            aria-pressed={tool === x}
            onClick={() => setTool(x)}
          >
            {x === 'idempotency' ? t('dev.idempotencyTitle') : t('dev.stressTitle')}
          </button>
        ))}
      </nav>
      {tool === 'idempotency' ? <IdempotencyDemo /> : <StressLab />}
    </div>
  );
}
