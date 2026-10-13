import type { BackOfficeSection } from '../../domain/routes';
import { BACK_OFFICE_SECTIONS } from '../../domain/routes';
import { t, type MessageKey } from '../../shared/i18n';
import { go } from '../common/nav';
import { CatalogSection } from './catalog/CatalogSection';
import { DevicesSection } from './devices/DevicesSection';
import { InventorySection } from './inventory/InventorySection';
import { OperationsSection } from './operations/OperationsSection';
import { OrderDetail } from './orders/OrderDetail';
import { OrdersSection } from './orders/OrdersSection';
import { ReportsSection } from './reports/ReportsSection';

const LABELS: Record<BackOfficeSection, MessageKey> = {
  orders: 'bo.orders',
  catalog: 'bo.catalog',
  inventory: 'bo.inventory',
  devices: 'bo.devices',
  operations: 'bo.operations',
  reports: 'bo.reports',
};

export function BackOffice({ section, id }: { section: BackOfficeSection; id: string | null }) {
  return (
    <div className="backoffice">
      <nav className="subnav" aria-label={t('bo.title')}>
        {BACK_OFFICE_SECTIONS.map((s) => (
          <a
            key={s}
            href={`#/backoffice/${s}`}
            className={`subnav-link ${s === section ? 'is-active' : ''}`}
            aria-current={s === section ? 'page' : undefined}
            onClick={(e) => {
              e.preventDefault();
              go({ app: 'backoffice', section: s, id: null });
            }}
          >
            {t(LABELS[s])}
          </a>
        ))}
      </nav>
      <h1 className="page-title">{t(LABELS[section])}</h1>
      {section === 'orders' ? id ? <OrderDetail id={id} key={id} /> : <OrdersSection /> : null}
      {section === 'catalog' ? <CatalogSection /> : null}
      {section === 'inventory' ? <InventorySection /> : null}
      {section === 'devices' ? <DevicesSection /> : null}
      {section === 'operations' ? <OperationsSection /> : null}
      {section === 'reports' ? <ReportsSection /> : null}
    </div>
  );
}
