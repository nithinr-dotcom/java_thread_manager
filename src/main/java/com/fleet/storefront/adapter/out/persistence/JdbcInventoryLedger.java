package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stock is changed with a single conditional statement per product. PostgreSQL row-locks the row, and a
 * concurrent writer that was waiting re-evaluates {@code on_hand >= :qty} against the committed value,
 * so the check and the decrement are one atomic step: overselling is impossible without any
 * read-then-write in application code or optimistic retry loops.
 *
 * <p>Every statement is a CTE that also appends the matching {@code stock_movement} journal row, so the
 * journal can never disagree with the stock it explains.
 */
@Repository
class JdbcInventoryLedger implements InventoryLedger {

    private final JdbcClient jdbc;

    JdbcInventoryLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void take(List<StockRequirement> requirements, StockChange change) {
        for (StockRequirement requirement : requirements) {
            if (decrement(requirement.productId(), requirement.quantity().value(), change).isEmpty()) {
                throw new InsufficientStockException(requirement.sku(), requirement.quantity().value());
            }
        }
    }

    @Override
    public int remove(ProductId productId, Sku sku, int quantity, StockChange change) {
        return decrement(productId, quantity, change)
                .orElseThrow(() -> new InsufficientStockException(sku, quantity));
    }

    private Optional<Integer> decrement(ProductId productId, int quantity, StockChange change) {
        return movement(jdbc.sql("""
                        WITH changed AS (
                            UPDATE inventory
                               SET on_hand = on_hand - :qty, version = version + 1, updated_at = now()
                             WHERE product_id = :productId AND on_hand >= :qty
                            RETURNING product_id, on_hand)
                        INSERT INTO stock_movement (id, product_id, delta, reason, reference_id, terminal_id, note, on_hand_after)
                        SELECT CAST(:movementId AS uuid), product_id, CAST(:delta AS integer), CAST(:reason AS varchar),
                               CAST(:referenceId AS uuid), CAST(:terminal AS varchar), CAST(:note AS varchar), on_hand
                          FROM changed
                        RETURNING on_hand_after
                        """), productId, quantity, -quantity, change);
    }

    /** Increment only, in one statement; creates the inventory row if the product has none yet. */
    @Override
    public int add(ProductId productId, int quantity, StockChange change) {
        return movement(jdbc.sql("""
                        WITH changed AS (
                            INSERT INTO inventory (product_id, on_hand) VALUES (:productId, :qty)
                            ON CONFLICT (product_id) DO UPDATE
                               SET on_hand = inventory.on_hand + EXCLUDED.on_hand,
                                   version = inventory.version + 1, updated_at = now()
                            RETURNING product_id, on_hand)
                        INSERT INTO stock_movement (id, product_id, delta, reason, reference_id, terminal_id, note, on_hand_after)
                        SELECT CAST(:movementId AS uuid), product_id, CAST(:delta AS integer), CAST(:reason AS varchar),
                               CAST(:referenceId AS uuid), CAST(:terminal AS varchar), CAST(:note AS varchar), on_hand
                          FROM changed
                        RETURNING on_hand_after
                        """), productId, quantity, quantity, change)
                .orElseThrow(() -> new IllegalStateException("Restock of " + productId + " wrote no stock row"));
    }

    private static Optional<Integer> movement(JdbcClient.StatementSpec sql, ProductId productId, int quantity, int delta,
                                              StockChange change) {
        return sql.param("productId", productId.value())
                .param("qty", quantity)
                .param("movementId", UuidV7.next())
                .param("delta", delta)
                .param("reason", change.reason().name())
                .param("referenceId", change.referenceId())
                .param("terminal", change.terminalId())
                .param("note", change.note())
                .query(Integer.class)
                .optional();
    }

    @Override
    public Map<ProductId, Integer> onHand(Collection<ProductId> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT product_id, on_hand FROM inventory WHERE product_id IN (:ids)")
                .param("ids", productIds.stream().map(ProductId::value).toList())
                .query((rs, n) -> Map.entry(new ProductId(rs.getObject("product_id", UUID.class)), rs.getInt("on_hand")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public List<StockMovementView> movements(ProductId productId, Sku sku, int limit) {
        return jdbc.sql("""
                        SELECT id, delta, reason, reference_id, terminal_id, note, on_hand_after, created_at
                          FROM stock_movement
                         WHERE product_id = :productId
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("productId", productId.value())
                .param("limit", limit)
                .query((rs, n) -> new StockMovementView(
                        rs.getObject("id", UUID.class),
                        sku.value(),
                        rs.getInt("delta"),
                        rs.getString("reason"),
                        rs.getObject("reference_id", UUID.class),
                        rs.getString("terminal_id"),
                        rs.getString("note"),
                        rs.getInt("on_hand_after"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
