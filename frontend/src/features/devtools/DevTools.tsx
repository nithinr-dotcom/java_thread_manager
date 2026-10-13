import { useState } from 'react';
import { t } from '../../shared/i18n';
import { StressLab } from '../stress/StressLab';
import { IdempotencyDemo } from './IdempotencyDemo';

type Tool = 'idempotency' | 'stress';

/** MANAGER-only test tools. They create real orders on the node — never use them in service. */
export function DevTools() {
  const [tool, setTool] = useState<Tool>('idempotency');
  return (
    <div className="devtools">
      <h1 className="page-title">{t('dev.title')}</h1>
      <div className="banner banner-warn" role="note">
        {t('dev.warning')}
      </div>
      <nav className="subnav" aria-label={t('dev.title')}>
        {(['idempotency', 'stress'] as const).map((x) => (
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
