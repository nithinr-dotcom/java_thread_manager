import { Component, type ErrorInfo, type ReactNode } from 'react';
import { t } from '../shared/i18n';

interface State {
  error: Error | null;
}

/** Top-level crash guard: shows a recovery screen instead of a blank page. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('UI crashed', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <main className="crash" role="alert">
        <h1>{t('crash.title')}</h1>
        <p>{t('crash.body')}</p>
        <pre className="crash-detail">{this.state.error.message}</pre>
        <div className="btn-row">
          <button
            type="button"
            className="btn-primary btn-lg"
            onClick={() => this.setState({ error: null })}
          >
            {t('crash.retry')}
          </button>
          <button type="button" className="btn-lg" onClick={() => window.location.reload()}>
            {t('crash.reload')}
          </button>
        </div>
      </main>
    );
  }
}
