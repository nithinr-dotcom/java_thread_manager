// Test data shaped exactly like docs/API.md.
import type { CatalogItem, KitchenTicket, Me, Order, Payment, Role } from '../api/types';

export const me = (role: Role = 'POS', terminalId = `demo-${role.toLowerCase()}`): Me => ({
  terminalId,
  name: `Demo ${role}`,
  role,
  branch: { code: 'HOSP01', currency: 'USD', zone: 'America/New_York' },
});

export const catalogItem = (over: Partial<CatalogItem> = {}): CatalogItem => ({
  productId: 'p-burger',
  sku: 'BURGER-CLASSIC',
  name: 'Classic burger',
  unitPrice: 11.4,
  currency: 'USD',
  taxRatePercent: 8.25,
  requiresPreparation: true,
  station: 'GRILL',
  category: 'Mains',
  active: true,
  version: 1,
  onHand: 50,
  ...over,
});

export const catalog: CatalogItem[] = [
  catalogItem(),
  catalogItem({
    productId: 'p-water',
    sku: 'WATER-SPARKLING',
    name: 'Sparkling water',
    unitPrice: 2.5,
    requiresPreparation: false,
    station: 'BAR',
    category: 'Drinks',
    onHand: 0,
  }),
  catalogItem({
    productId: 'p-latte',
    sku: 'LATTE-LARGE',
    name: 'Large latte',
    unitPrice: 4.2,
    station: 'BAR',
    category: 'Drinks',
    onHand: 5,
  }),
];

export const placedOrder = (over: Partial<Order> = {}): Order => ({
  id: 'order-1',
  orderNumber: 'ORD-HOSP01-20261013-0001',
  status: 'PLACED',
  terminalId: 'demo-pos',
  placedAt: '2026-10-13T12:00:00.123456Z',
  currency: 'USD',
  subtotal: 22.8,
  taxTotal: 1.88,
  total: 24.68,
  amountPaid: 0,
  amountDue: 24.68,
  amountRefunded: 0,
  fiscalHash: '9f2c0a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789ab',
  lines: [
    {
      lineNo: 1,
      sku: 'BURGER-CLASSIC',
      name: 'Classic burger',
      quantity: 2,
      unitPrice: 11.4,
      taxRatePercent: 8.25,
      tax: 1.88,
      total: 24.68,
      refundedQuantity: 0,
    },
  ],
  payments: [],
  refunds: [],
  ...over,
});

export const cashPayment = (over: Partial<Payment> = {}): Payment => ({
  id: 'pay-1',
  method: 'CASH',
  amount: 24.68,
  tendered: 50,
  change: 25.32,
  reference: null,
  terminalId: 'demo-pos',
  receivedAt: '2026-10-13T12:00:05Z',
  voidedAt: null,
  voidReason: null,
  ...over,
});

export const ticket = (over: Partial<KitchenTicket> = {}): KitchenTicket => ({
  id: 'ticket-1',
  orderId: 'order-1',
  orderNumber: 'ORD-HOSP01-20261013-0001',
  terminalId: 'demo-pos',
  status: 'QUEUED',
  receivedAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  items: [{ sku: 'BURGER-CLASSIC', name: 'Classic burger', quantity: 2, station: 'GRILL' }],
  ...over,
});
