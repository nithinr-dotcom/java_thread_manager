package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderLine;
import com.fleet.storefront.domain.order.OrderNumber;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.payment.PaymentId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.refund.Refund;
import com.fleet.storefront.domain.refund.RefundId;
import com.fleet.storefront.domain.refund.RefundLine;
import com.fleet.storefront.domain.refund.RefundNumber;
import com.fleet.storefront.domain.shared.UuidV7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orders and their settlement (payments, refund documents). Loads always batch child rows by parent
 * ids (one query per table, never per order).
 */
@Repository
class JdbcOrderRepository implements OrderRepository {

    private static final String ORDER_COLUMNS = "id, order_number, terminal_id, currency, status, placed_at, fiscal_hash";

    private final JdbcClient jdbc;
    private final String nodeId;

    JdbcOrderRepository(JdbcClient jdbc, @Value("${storefront.branch.node-id}") String nodeId) {
        this.jdbc = jdbc;
        this.nodeId = nodeId;
    }

    // --- Writes --------------------------------------------------------------------------------

    @Override
    public void insert(Order order) {
        OrderNumber number = order.number();
        jdbc.sql("""
                        INSERT INTO customer_order (id, order_number, branch_code, business_date, sequence_no, terminal_id,
                                                    status, currency, subtotal, tax_total, grand_total, placed_at,
                                                    origin_node, fiscal_hash)
                        VALUES (:id, :number, :branch, :date, :seq, :terminal, :status, :currency, :subtotal, :tax,
                                :total, :placedAt, :node, :hash)
                        """)
                .param("id", order.id().value())
                .param("number", number.value())
                .param("branch", number.branchCode())
                .param("date", number.businessDate())
                .param("seq", number.sequence())
                .param("terminal", order.terminalId().value())
                .param("status", order.status().name())
                .param("currency", order.currency().getCurrencyCode())
                .param("subtotal", order.subtotal().amount())
                .param("tax", order.taxTotal().amount())
                .param("total", order.total().amount())
                .param("placedAt", Timestamp.from(order.placedAt()))
                .param("node", nodeId)
                .param("hash", order.fiscalHash())
                .update();
        for (OrderLine line : order.lines()) {
            jdbc.sql("""
                            INSERT INTO order_line (id, order_id, line_no, product_id, sku, name, quantity, unit_price,
                                                    tax_rate_percent, line_tax, line_total, requires_preparation, station)
                            VALUES (:id, :orderId, :lineNo, :productId, :sku, :name, :qty, :price, :rate, :tax, :total,
                                    :prep, :station)
                            """)
                    .param("id", UuidV7.next())
                    .param("orderId", order.id().value())
                    .param("lineNo", line.lineNo())
                    .param("productId", line.productId().value())
                    .param("sku", line.sku().value())
                    .param("name", line.name())
                    .param("qty", line.quantity().value())
                    .param("price", line.unitPrice().amount())
                    .param("rate", line.taxRate().percent())
                    .param("tax", line.tax().amount())
                    .param("total", line.total().amount())
                    .param("prep", line.requiresPreparation())
                    .param("station", line.station())
                    .update();
        }
    }

    @Override
    public void updateStatus(OrderId id, OrderStatus status, String reason) {
        jdbc.sql("""
                        UPDATE customer_order
                           SET status = :status, cancel_reason = COALESCE(CAST(:reason AS varchar), cancel_reason),
                               status_changed_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("status", status.name())
                .param("reason", reason)
                .param("id", id.value())
                .update();
    }

    @Override
    public void insertPayment(Payment p) {
        jdbc.sql("""
                        INSERT INTO payment (id, order_id, method, currency, amount, tendered, change_given, reference,
                                             terminal_id, received_at, origin_node)
                        VALUES (:id, :orderId, :method, :currency, :amount, :tendered, :change, :reference, :terminal,
                                :receivedAt, :node)
                        """)
                .param("id", p.id().value())
                .param("orderId", p.orderId().value())
                .param("method", p.method().name())
                .param("currency", p.amount().currency().getCurrencyCode())
                .param("amount", p.amount().amount())
                .param("tendered", p.tendered() == null ? null : p.tendered().amount())
                .param("change", p.change() == null ? null : p.change().amount())
                .param("reference", p.reference())
                .param("terminal", p.terminalId().value())
                .param("receivedAt", Timestamp.from(p.receivedAt()))
                .param("node", nodeId)
                .update();
    }

    @Override
    public void voidPayment(Payment voided) {
        jdbc.sql("UPDATE payment SET voided_at = :at, void_reason = :reason WHERE id = :id AND voided_at IS NULL")
                .param("at", Timestamp.from(voided.voidedAt()))
                .param("reason", voided.voidReason())
                .param("id", voided.id().value())
                .update();
    }

    @Override
    public void insertRefund(Refund r) {
        RefundNumber number = r.number();
        jdbc.sql("""
                        INSERT INTO refund (id, refund_number, order_id, branch_code, business_date, sequence_no, method,
                                            reason, restocked, currency, subtotal, tax_total, grand_total, terminal_id,
                                            issued_at, origin_node, fiscal_hash)
                        VALUES (:id, :number, :orderId, :branch, :date, :seq, :method, :reason, :restocked, :currency,
                                :subtotal, :tax, :total, :terminal, :issuedAt, :node, :hash)
                        """)
                .param("id", r.id().value())
                .param("number", number.value())
                .param("orderId", r.orderId().value())
                .param("branch", number.branchCode())
                .param("date", number.businessDate())
                .param("seq", number.sequence())
                .param("method", r.method().name())
                .param("reason", r.reason())
                .param("restocked", r.restocked())
                .param("currency", r.currency().getCurrencyCode())
                .param("subtotal", r.subtotal().amount())
                .param("tax", r.taxTotal().amount())
                .param("total", r.total().amount())
                .param("terminal", r.terminalId().value())
                .param("issuedAt", Timestamp.from(r.issuedAt()))
                .param("node", nodeId)
                .param("hash", r.fiscalHash())
                .update();
        for (RefundLine line : r.lines()) {
            jdbc.sql("""
                            INSERT INTO refund_line (id, refund_id, line_no, quantity, net, tax, total)
                            VALUES (:id, :refundId, :lineNo, :qty, :net, :tax, :total)
                            """)
                    .param("id", UuidV7.next())
                    .param("refundId", r.id().value())
                    .param("lineNo", line.lineNo())
                    .param("qty", line.quantity().value())
                    .param("net", line.net().amount())
                    .param("tax", line.tax().amount())
                    .param("total", line.total().amount())
                    .update();
        }
    }

    // --- Reads ---------------------------------------------------------------------------------

    @Override
    public Optional<Order> findById(OrderId id) {
        return loadOrders(jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE id = :id")
                .param("id", id.value())).stream().findFirst();
    }

    @Override
    public Optional<OrderAccount> findAccount(OrderId id) {
        return findById(id).map(o -> accounts(List.of(o)).getFirst());
    }

    @Override
    public Optional<OrderAccount> lockAccount(OrderId id) {
        Optional<UUID> locked = jdbc.sql("SELECT id FROM customer_order WHERE id = :id FOR UPDATE")
                .param("id", id.value())
                .query(UUID.class)
                .optional();
        return locked.flatMap(x -> findAccount(id));
    }

    @Override
    public List<OrderAccount> findRecentAccounts(int limit, Optional<OrderStatus> status) {
        List<Order> orders = loadOrders(jdbc.sql("SELECT " + ORDER_COLUMNS + """
                         FROM customer_order
                        WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
                        ORDER BY placed_at DESC, order_number DESC
                        LIMIT :limit
                        """)
                .param("status", status.map(Enum::name).orElse(null))
                .param("limit", limit));
        return accounts(orders);
    }

    @Override
    public List<Order> issuedOn(String branchCode, LocalDate businessDate) {
        return loadOrders(jdbc.sql("SELECT " + ORDER_COLUMNS + """
                         FROM customer_order
                        WHERE branch_code = :branch AND business_date = :date
                        ORDER BY sequence_no
                        """)
                .param("branch", branchCode)
                .param("date", businessDate));
    }

    @Override
    public List<Refund> refundsIssuedOn(String branchCode, LocalDate businessDate) {
        List<UUID> ids = jdbc.sql("""
                        SELECT id FROM refund WHERE branch_code = :branch AND business_date = :date ORDER BY sequence_no
                        """)
                .param("branch", branchCode)
                .param("date", businessDate)
                .query(UUID.class)
                .list();
        Map<UUID, Refund> byId = loadRefunds("r.id IN (:ids)", ids).stream()
                .collect(Collectors.toMap(r -> r.id().value(), r -> r));
        return ids.stream().map(byId::get).toList();
    }

    /** Header rows, then all their lines in one query; keeps the header order. */
    private List<Order> loadOrders(JdbcClient.StatementSpec headers) {
        List<OrderHeader> rows = headers.query((rs, n) -> new OrderHeader(
                        new OrderId(rs.getObject("id", UUID.class)),
                        OrderNumber.parse(rs.getString("order_number")),
                        new TerminalId(rs.getString("terminal_id")),
                        Currency.getInstance(rs.getString("currency")),
                        OrderStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("placed_at").toInstant(),
                        rs.getString("fiscal_hash")))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Currency> currencies = rows.stream()
                .collect(Collectors.toMap(h -> h.id().value(), OrderHeader::currency));
        Map<UUID, List<OrderLine>> lines = jdbc.sql("SELECT * FROM order_line WHERE order_id IN (:ids) ORDER BY order_id, line_no")
                .param("ids", List.copyOf(currencies.keySet()))
                .query((rs, n) -> {
                    UUID orderId = rs.getObject("order_id", UUID.class);
                    return Map.entry(orderId, new OrderLine(
                            rs.getInt("line_no"),
                            new ProductId(rs.getObject("product_id", UUID.class)),
                            Sku.of(rs.getString("sku")),
                            rs.getString("name"),
                            Money.of(rs.getBigDecimal("unit_price"), currencies.get(orderId)),
                            Quantity.of(rs.getInt("quantity")),
                            new TaxRate(rs.getBigDecimal("tax_rate_percent")),
                            rs.getBoolean("requires_preparation"),
                            rs.getString("station")));
                })
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return rows.stream()
                .map(h -> Order.restore(h.id(), h.number(), h.terminalId(), h.currency(),
                        lines.getOrDefault(h.id().value(), List.of()), h.status(), h.placedAt(), h.fiscalHash()))
                .toList();
    }

    private List<OrderAccount> accounts(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = orders.stream().map(o -> o.id().value()).toList();
        Map<UUID, List<Payment>> payments = jdbc.sql("""
                        SELECT * FROM payment WHERE order_id IN (:ids) ORDER BY received_at, id
                        """)
                .param("ids", ids)
                .query((rs, n) -> payment(rs))
                .list().stream()
                .collect(Collectors.groupingBy(p -> p.orderId().value()));
        Map<UUID, List<Refund>> refunds = loadRefunds("r.order_id IN (:ids)", ids).stream()
                .collect(Collectors.groupingBy(r -> r.orderId().value()));
        return orders.stream()
                .map(o -> new OrderAccount(o, payments.getOrDefault(o.id().value(), List.of()),
                        refunds.getOrDefault(o.id().value(), List.of())))
                .toList();
    }

    private List<Refund> loadRefunds(String where, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<RefundLine>> lines = jdbc.sql("""
                        SELECT rl.refund_id, rl.line_no, rl.quantity, rl.net, rl.tax, r.currency,
                               ol.product_id, ol.sku, ol.name
                          FROM refund_line rl
                          JOIN refund r ON r.id = rl.refund_id
                          JOIN order_line ol ON ol.order_id = r.order_id AND ol.line_no = rl.line_no
                         WHERE\s""" + where + " ORDER BY rl.refund_id, rl.line_no")
                .param("ids", ids)
                .query((rs, n) -> {
                    Currency currency = Currency.getInstance(rs.getString("currency"));
                    return Map.entry(rs.getObject("refund_id", UUID.class), new RefundLine(
                            rs.getInt("line_no"),
                            new ProductId(rs.getObject("product_id", UUID.class)),
                            Sku.of(rs.getString("sku")),
                            rs.getString("name"),
                            Quantity.of(rs.getInt("quantity")),
                            Money.of(rs.getBigDecimal("net"), currency),
                            Money.of(rs.getBigDecimal("tax"), currency)));
                })
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.sql("SELECT r.* FROM refund r WHERE " + where + " ORDER BY r.issued_at, r.sequence_no")
                .param("ids", ids)
                .query((rs, n) -> new Refund(
                        new RefundId(rs.getObject("id", UUID.class)),
                        RefundNumber.parse(rs.getString("refund_number")),
                        new OrderId(rs.getObject("order_id", UUID.class)),
                        PaymentMethod.valueOf(rs.getString("method")),
                        rs.getString("reason"),
                        rs.getBoolean("restocked"),
                        new TerminalId(rs.getString("terminal_id")),
                        rs.getTimestamp("issued_at").toInstant(),
                        Currency.getInstance(rs.getString("currency")),
                        lines.getOrDefault(rs.getObject("id", UUID.class), List.of()),
                        rs.getString("fiscal_hash")))
                .list();
    }

    private static Payment payment(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new Payment(
                new PaymentId(rs.getObject("id", UUID.class)),
                new OrderId(rs.getObject("order_id", UUID.class)),
                PaymentMethod.valueOf(rs.getString("method")),
                Money.of(rs.getBigDecimal("amount"), currency),
                money(rs.getBigDecimal("tendered"), currency),
                money(rs.getBigDecimal("change_given"), currency),
                rs.getString("reference"),
                new TerminalId(rs.getString("terminal_id")),
                rs.getTimestamp("received_at").toInstant(),
                Optional.ofNullable(rs.getTimestamp("voided_at")).map(Timestamp::toInstant).orElse(null),
                rs.getString("void_reason"));
    }

    private static Money money(BigDecimal amount, Currency currency) {
        return amount == null ? null : Money.of(amount, currency);
    }

    private record OrderHeader(OrderId id, OrderNumber number, TerminalId terminalId, Currency currency,
                               OrderStatus status, Instant placedAt, String fiscalHash) {
    }
}
