// DTOs exactly per ../../../docs/API.md (source of truth). Keep in sync.

/** Money is serialised from BigDecimal as a JSON number at the currency's scale. Display only. */
export type MoneyAmount = number;
/** ISO-4217 code, e.g. "USD". */
export type CurrencyCode = string;
/** ISO-8601 UTC timestamp. */
export type Instant = string;
/** Business date `YYYY-MM-DD` in the branch time zone. */
export type BusinessDate = string;
/** UUIDv7 string. */
export type Uuid = string;

// ---------------------------------------------------------------- errors

export type ProblemCode =
  | 'INVALID_REQUEST'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'TERMINAL_MISMATCH'
  | 'ORDER_NOT_FOUND'
  | 'PAYMENT_NOT_FOUND'
  | 'TICKET_NOT_FOUND'
  | 'TERMINAL_NOT_FOUND'
  | 'DEAD_LETTER_NOT_FOUND'
  | 'OUT_OF_STOCK'
  | 'ORDER_NOT_PAYABLE'
  | 'ORDER_NOT_CANCELLABLE'
  | 'ORDER_NOT_REFUNDABLE'
  | 'TICKET_STATE_CONFLICT'
  | 'VERSION_CONFLICT'
  | 'PRODUCT_EXISTS'
  | 'TERMINAL_EXISTS'
  | 'CANNOT_REVOKE_SELF'
  | 'LAST_MANAGER'
  | 'PAYMENT_ALREADY_VOIDED'
  | 'UNKNOWN_PRODUCT'
  | 'PRODUCT_INACTIVE'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'CURRENCY_MISMATCH'
  | 'PAYMENT_EXCEEDS_DUE'
  | 'INSUFFICIENT_TENDER'
  | 'REFUND_EXCEEDS_SOLD'
  | 'UNKNOWN_ORDER_LINE'
  | 'INTERNAL_ERROR'
  | 'BUSY_RETRY'
  // Client-side synthetic codes (status 0, never sent by the server):
  | 'NETWORK_ERROR'
  | 'TIMEOUT';

/** RFC 7807 problem detail with the backend's stable `code` extension. */
export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Stable machine code; unknown codes are passed through as strings. */
  code?: ProblemCode | (string & {});
  /** OUT_OF_STOCK extras */
  sku?: string;
  requested?: number;
  /** VERSION_CONFLICT extra */
  currentVersion?: number;
  [extra: string]: unknown;
}

// ---------------------------------------------------------------- session

export type Role = 'POS' | 'KITCHEN' | 'MANAGER' | 'HQ' | 'MONITOR';
export const ROLES: readonly Role[] = ['POS', 'KITCHEN', 'MANAGER', 'HQ', 'MONITOR'];

export interface Branch {
  code: string;
  currency: CurrencyCode;
  /** IANA time zone, e.g. "America/New_York". */
  zone: string;
}

export interface Me {
  terminalId: string;
  name: string;
  role: Role;
  branch: Branch;
}

// ---------------------------------------------------------------- orders

export type OrderStatus = 'PLACED' | 'PAID' | 'PARTIALLY_REFUNDED' | 'REFUNDED' | 'CANCELLED';
export const ORDER_STATUSES: readonly OrderStatus[] = [
  'PLACED',
  'PAID',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'CANCELLED',
];

export type PaymentMethod = 'CASH' | 'CARD';

export interface CheckoutItem {
  sku: string;
  quantity: number;
}

export interface CheckoutRequest {
  items: CheckoutItem[];
}

export interface OrderLine {
  lineNo: number;
  sku: string;
  name: string;
  quantity: number;
  unitPrice: MoneyAmount;
  taxRatePercent: number;
  tax: MoneyAmount;
  total: MoneyAmount;
  refundedQuantity: number;
}

export interface Payment {
  id: Uuid;
  method: PaymentMethod;
  amount: MoneyAmount;
  /** CASH only; null for CARD. */
  tendered: MoneyAmount | null;
  /** CASH only (server-computed); null for CARD. */
  change: MoneyAmount | null;
  /** CARD approval reference; null for CASH. */
  reference: string | null;
  terminalId: string;
  receivedAt: Instant;
  /** Set when the tender was handed back (PLACED orders only); voided payments don't count. */
  voidedAt: Instant | null;
  voidReason: string | null;
}

export interface RefundLine {
  lineNo: number;
  sku: string;
  name: string;
  quantity: number;
  net: MoneyAmount;
  tax: MoneyAmount;
  total: MoneyAmount;
}

export interface Refund {
  id: Uuid;
  refundNumber: string;
  method: PaymentMethod;
  reason: string;
  restocked: boolean;
  issuedAt: Instant;
  terminalId: string;
  subtotal: MoneyAmount;
  taxTotal: MoneyAmount;
  total: MoneyAmount;
  fiscalHash: string | null;
  lines: RefundLine[];
}

export interface Order {
  id: Uuid;
  orderNumber: string;
  status: OrderStatus;
  terminalId: string;
  placedAt: Instant;
  currency: CurrencyCode;
  subtotal: MoneyAmount;
  taxTotal: MoneyAmount;
  total: MoneyAmount;
  amountPaid: MoneyAmount;
  amountDue: MoneyAmount;
  amountRefunded: MoneyAmount;
  /** null for documents issued before hash chaining was introduced. */
  fiscalHash: string | null;
  lines: OrderLine[];
  payments: Payment[];
  refunds: Refund[];
}

export type PaymentRequest =
  | { method: 'CASH'; amount: MoneyAmount; tendered: MoneyAmount }
  | { method: 'CARD'; amount: MoneyAmount; reference: string };

export interface PaymentResponse {
  payment: Payment;
  order: Order;
}

export interface CancelRequest {
  reason: string;
}

export interface VoidPaymentRequest {
  reason: string;
}

export interface RefundRequestLine {
  lineNo: number;
  quantity: number;
}

export interface RefundRequest {
  lines: RefundRequestLine[];
  restock: boolean;
  method: PaymentMethod;
  reason: string;
}

export interface RefundResponse {
  refund: Refund;
  order: Order;
}

// ---------------------------------------------------------------- catalog

export interface CatalogItem {
  productId: Uuid;
  sku: string;
  name: string;
  unitPrice: MoneyAmount;
  currency: CurrencyCode;
  taxRatePercent: number;
  requiresPreparation: boolean;
  station: string;
  category: string;
  active: boolean;
  /** Optimistic-concurrency version (epoch millis for HQ-originated versions). */
  version: number;
  onHand: number;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  unitPrice: MoneyAmount;
  taxRatePercent: number;
  requiresPreparation: boolean;
  station?: string;
  category?: string;
}

export interface UpdateProductRequest {
  name: string;
  unitPrice: MoneyAmount;
  taxRatePercent: number;
  requiresPreparation: boolean;
  station?: string;
  category?: string;
  active: boolean;
  version: number;
}

// ---------------------------------------------------------------- inventory

export interface RestockRequest {
  quantity: number;
  note?: string;
}

export type AdjustmentReason = 'WASTE' | 'DAMAGE' | 'COUNT_CORRECTION';
export const ADJUSTMENT_REASONS: readonly AdjustmentReason[] = [
  'WASTE',
  'DAMAGE',
  'COUNT_CORRECTION',
];

export interface AdjustmentRequest {
  delta: number;
  reason: AdjustmentReason;
  note?: string;
}

export interface StockLevel {
  sku: string;
  onHand: number;
}

export type MovementReason =
  | 'SALE'
  | 'CANCEL'
  | 'REFUND'
  | 'RESTOCK'
  | 'WASTE'
  | 'DAMAGE'
  | 'COUNT_CORRECTION';

export interface StockMovement {
  id: Uuid;
  sku: string;
  delta: number;
  reason: MovementReason;
  referenceId: Uuid | null;
  terminalId: string;
  note: string | null;
  onHandAfter: number;
  createdAt: Instant;
}

// ---------------------------------------------------------------- kitchen

export type TicketStatus = 'QUEUED' | 'IN_PROGRESS' | 'READY' | 'SERVED' | 'CANCELLED';

export interface KitchenTicketItem {
  sku: string;
  name: string;
  quantity: number;
  station: string;
}

export interface KitchenTicket {
  id: Uuid;
  orderId: Uuid;
  orderNumber: string;
  terminalId: string;
  status: TicketStatus;
  receivedAt: Instant;
  updatedAt: Instant;
  items: KitchenTicketItem[];
}

export interface TicketStatusRequest {
  status: TicketStatus;
}

// ---------------------------------------------------------------- devices

export interface Terminal {
  terminalId: string;
  name: string;
  role: Role;
  active: boolean;
  createdAt: Instant;
  revokedAt: Instant | null;
}

export interface RegisterTerminalRequest {
  terminalId: string;
  name: string;
  role: Role;
}

/** Returned once by `POST /terminals`; only the key's SHA-256 is stored server-side. */
export interface RegisteredTerminal extends Terminal {
  apiKey: string;
}

// ---------------------------------------------------------------- operations

export type OutboxDestination = 'KITCHEN' | 'HQ' | (string & {});

export interface OutboxDestinationStatus {
  destination: OutboxDestination;
  pending: number;
  published: number;
  failing: number;
  dead: number;
  oldestPendingAt: Instant | null;
  lastError: string | null;
}

export interface OutboxStatus {
  destinations: OutboxDestinationStatus[];
}

export interface RetryAllResponse {
  requeued: number;
}

export interface DeadLetter {
  id: Uuid;
  destination: OutboxDestination;
  eventType: string;
  aggregateId: Uuid;
  attempts: number;
  lastError: string | null;
  createdAt: Instant;
  deadAt: Instant;
}

// ---------------------------------------------------------------- reports

export interface DailyReport {
  businessDate: BusinessDate;
  branchCode: string;
  currency: CurrencyCode;
  orders: {
    issued: number;
    cancelled: number;
    firstNumber: string | null;
    lastNumber: string | null;
  };
  sales: { subtotal: MoneyAmount; taxTotal: MoneyAmount; total: MoneyAmount };
  taxByRate: { taxRatePercent: number; net: MoneyAmount; tax: MoneyAmount }[];
  payments: { method: PaymentMethod; count: number; amount: MoneyAmount }[];
  refunds: { count: number; total: MoneyAmount };
  unpaidTotal: MoneyAmount;
}

export interface FiscalSeriesCheck {
  series: 'ORD' | 'REF' | (string & {});
  documents: number;
  valid: boolean;
  firstBrokenNumber: string | null;
}

export interface FiscalChainReport {
  businessDate: BusinessDate;
  series: FiscalSeriesCheck[];
}

// ---------------------------------------------------------------- HQ sync

export interface SyncProduct {
  productId: Uuid;
  sku: string;
  name: string;
  unitPrice: MoneyAmount;
  currency: CurrencyCode;
  taxRatePercent: number;
  requiresPreparation: boolean;
  station: string;
  category: string;
  active: boolean;
  version: number;
}

export interface SyncCatalogRequest {
  products: SyncProduct[];
}

export interface SyncCatalogResponse {
  applied: number;
  skipped: number;
}

// ---------------------------------------------------------------- actuator

/** Spring Boot actuator health (only the field we use). */
export interface Health {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN' | (string & {});
}
