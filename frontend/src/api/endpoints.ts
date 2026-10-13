import { authHeaders, request, type ApiResult, type FetchLike } from './client';
import type {
  AdjustmentRequest,
  CancelRequest,
  CatalogItem,
  CheckoutRequest,
  CreateProductRequest,
  DailyReport,
  DeadLetter,
  FiscalChainReport,
  Health,
  KitchenTicket,
  Me,
  Order,
  OrderStatus,
  OutboxStatus,
  PaymentRequest,
  PaymentResponse,
  RefundRequest,
  RefundResponse,
  RetryAllResponse,
  RegisteredTerminal,
  RegisterTerminalRequest,
  RestockRequest,
  StockLevel,
  StockMovement,
  SyncCatalogRequest,
  SyncCatalogResponse,
  Terminal,
  TicketStatus,
  UpdateProductRequest,
  VoidPaymentRequest,
} from './types';

export const BASE = '/api/v1';

/** All endpoints accept an optional fetch implementation (tests inject a mock). */
export interface EndpointOptions {
  fetchImpl?: FetchLike;
  signal?: AbortSignal;
  timeoutMs?: number;
}

const common = (o: EndpointOptions) => ({
  ...(o.signal ? { signal: o.signal } : {}),
  ...(o.timeoutMs !== undefined ? { timeoutMs: o.timeoutMs } : {}),
});

const enc = encodeURIComponent;

function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

function get<T>(url: string, o: EndpointOptions): Promise<ApiResult<T>> {
  return request<T>(url, common(o), o.fetchImpl);
}

function send<T>(
  method: 'POST' | 'PUT',
  url: string,
  body: unknown,
  o: EndpointOptions,
  headers: Record<string, string> = {},
): Promise<ApiResult<T>> {
  return request<T>(url, { method, headers, body, ...common(o) }, o.fetchImpl);
}

const idem = (key: string) => ({ 'Idempotency-Key': key });

// ---------------------------------------------------------------- session

/** `GET /me`. Pass `apiKey` to validate a candidate key before it is stored (sign-in). */
export function getMe(apiKey?: string, o: EndpointOptions = {}): Promise<ApiResult<Me>> {
  return request<Me>(
    `${BASE}/me`,
    { ...(apiKey ? { headers: authHeaders(apiKey) } : {}), ...common(o) },
    o.fetchImpl,
  );
}

// ---------------------------------------------------------------- orders

export function checkout(
  idempotencyKey: string,
  body: CheckoutRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<Order>> {
  return send<Order>('POST', `${BASE}/orders`, body, o, idem(idempotencyKey));
}

export function getOrder(id: string, o: EndpointOptions = {}): Promise<ApiResult<Order>> {
  return get<Order>(`${BASE}/orders/${enc(id)}`, o);
}

export function listOrders(
  params: { limit?: number; status?: OrderStatus | null } = {},
  o: EndpointOptions = {},
): Promise<ApiResult<Order[]>> {
  return get<Order[]>(
    `${BASE}/orders${query({ limit: params.limit ?? 20, status: params.status })}`,
    o,
  );
}

export function payOrder(
  orderId: string,
  idempotencyKey: string,
  body: PaymentRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<PaymentResponse>> {
  return send<PaymentResponse>(
    'POST',
    `${BASE}/orders/${enc(orderId)}/payments`,
    body,
    o,
    idem(idempotencyKey),
  );
}

export function cancelOrder(
  orderId: string,
  idempotencyKey: string,
  body: CancelRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<Order>> {
  return send<Order>(
    'POST',
    `${BASE}/orders/${enc(orderId)}/cancel`,
    body,
    o,
    idem(idempotencyKey),
  );
}

export function voidPayment(
  orderId: string,
  paymentId: string,
  idempotencyKey: string,
  body: VoidPaymentRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<Order>> {
  return send<Order>(
    'POST',
    `${BASE}/orders/${enc(orderId)}/payments/${enc(paymentId)}/void`,
    body,
    o,
    idem(idempotencyKey),
  );
}

export function refundOrder(
  orderId: string,
  idempotencyKey: string,
  body: RefundRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<RefundResponse>> {
  return send<RefundResponse>(
    'POST',
    `${BASE}/orders/${enc(orderId)}/refunds`,
    body,
    o,
    idem(idempotencyKey),
  );
}

// ---------------------------------------------------------------- catalog

export function getCatalog(
  params: { includeInactive?: boolean } = {},
  o: EndpointOptions = {},
): Promise<ApiResult<CatalogItem[]>> {
  return get<CatalogItem[]>(
    `${BASE}/catalog${query({ includeInactive: params.includeInactive ? true : undefined })}`,
    o,
  );
}

export function createProduct(
  body: CreateProductRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<CatalogItem>> {
  return send<CatalogItem>('POST', `${BASE}/catalog/products`, body, o);
}

export function updateProduct(
  sku: string,
  body: UpdateProductRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<CatalogItem>> {
  return send<CatalogItem>('PUT', `${BASE}/catalog/products/${enc(sku)}`, body, o);
}

// ---------------------------------------------------------------- inventory

export function restock(
  sku: string,
  idempotencyKey: string,
  body: RestockRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<StockLevel>> {
  return send<StockLevel>(
    'POST',
    `${BASE}/inventory/${enc(sku)}/restock`,
    body,
    o,
    idem(idempotencyKey),
  );
}

export function adjustStock(
  sku: string,
  idempotencyKey: string,
  body: AdjustmentRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<StockLevel>> {
  return send<StockLevel>(
    'POST',
    `${BASE}/inventory/${enc(sku)}/adjustments`,
    body,
    o,
    idem(idempotencyKey),
  );
}

export function listMovements(
  sku: string,
  limit = 50,
  o: EndpointOptions = {},
): Promise<ApiResult<StockMovement[]>> {
  return get<StockMovement[]>(`${BASE}/inventory/${enc(sku)}/movements${query({ limit })}`, o);
}

// ---------------------------------------------------------------- kitchen

export function listKitchenTickets(
  params: { limit?: number; active?: boolean; station?: string | null } = {},
  o: EndpointOptions = {},
): Promise<ApiResult<KitchenTicket[]>> {
  return get<KitchenTicket[]>(
    `${BASE}/kitchen/tickets${query({
      limit: params.limit ?? 50,
      active: params.active ? true : undefined,
      station: params.station,
    })}`,
    o,
  );
}

export function setTicketStatus(
  ticketId: string,
  status: TicketStatus,
  o: EndpointOptions = {},
): Promise<ApiResult<KitchenTicket>> {
  return send<KitchenTicket>(
    'POST',
    `${BASE}/kitchen/tickets/${enc(ticketId)}/status`,
    { status },
    o,
  );
}

/** URL of the SSE stream (consumed by `streamKitchen` in ./stream.ts). */
export function kitchenStreamUrl(station?: string | null): string {
  return `${BASE}/kitchen/stream${query({ station })}`;
}

// ---------------------------------------------------------------- devices

export function listTerminals(o: EndpointOptions = {}): Promise<ApiResult<Terminal[]>> {
  return get<Terminal[]>(`${BASE}/terminals`, o);
}

export function registerTerminal(
  body: RegisterTerminalRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<RegisteredTerminal>> {
  return send<RegisteredTerminal>('POST', `${BASE}/terminals`, body, o);
}

export function revokeTerminal(
  terminalId: string,
  o: EndpointOptions = {},
): Promise<ApiResult<Terminal>> {
  return send<Terminal>('POST', `${BASE}/terminals/${enc(terminalId)}/revoke`, undefined, o);
}

// ---------------------------------------------------------------- operations

export function getOutboxStatus(o: EndpointOptions = {}): Promise<ApiResult<OutboxStatus>> {
  return get<OutboxStatus>(`${BASE}/ops/outbox`, o);
}

export function listDeadLetters(
  limit = 50,
  o: EndpointOptions = {},
): Promise<ApiResult<DeadLetter[]>> {
  return get<DeadLetter[]>(`${BASE}/ops/outbox/dead${query({ limit })}`, o);
}

export function retryDeadLetter(id: string, o: EndpointOptions = {}): Promise<ApiResult<void>> {
  return send<void>('POST', `${BASE}/ops/outbox/${enc(id)}/retry`, undefined, o);
}

/** Re-queue every dead letter of one destination. */
export function retryAllDeadLetters(
  destination: string,
  o: EndpointOptions = {},
): Promise<ApiResult<RetryAllResponse>> {
  return send<RetryAllResponse>(
    'POST',
    `${BASE}/ops/outbox/dead/retry${query({ destination })}`,
    undefined,
    o,
  );
}

// ---------------------------------------------------------------- reports

export function getDailyReport(
  date?: string,
  o: EndpointOptions = {},
): Promise<ApiResult<DailyReport>> {
  return get<DailyReport>(`${BASE}/reports/daily${query({ date })}`, o);
}

export function getFiscalChain(
  date?: string,
  o: EndpointOptions = {},
): Promise<ApiResult<FiscalChainReport>> {
  return get<FiscalChainReport>(`${BASE}/reports/fiscal-chain${query({ date })}`, o);
}

// ---------------------------------------------------------------- HQ sync

export function syncCatalog(
  body: SyncCatalogRequest,
  o: EndpointOptions = {},
): Promise<ApiResult<SyncCatalogResponse>> {
  return send<SyncCatalogResponse>('PUT', `${BASE}/sync/catalog`, body, o);
}

// ---------------------------------------------------------------- actuator

/** Public health probe (no auth needed); used for reachability. */
export function getHealth(o: EndpointOptions = {}): Promise<ApiResult<Health>> {
  return get<Health>('/actuator/health', { timeoutMs: 5000, ...o });
}
