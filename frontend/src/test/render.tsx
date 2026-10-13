import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { setApiKey } from '../api/client';
import type { Me } from '../api/types';
import { ReachabilityContext, type Reachability } from '../shared/reachability';
import { SessionContext } from '../shared/session';

/** Render a screen as a signed-in device (no App shell). */
export function renderAs(
  ui: ReactElement,
  me: Me,
  opts: { reach?: Reachability; signOut?: () => void } = {},
) {
  setApiKey(`sf_test_${me.role.toLowerCase()}`);
  return render(
    <SessionContext.Provider value={{ me, signOut: opts.signOut ?? (() => undefined) }}>
      <ReachabilityContext.Provider value={{ state: opts.reach ?? 'up', refresh: () => undefined }}>
        {ui}
      </ReachabilityContext.Provider>
    </SessionContext.Provider>,
  );
}
