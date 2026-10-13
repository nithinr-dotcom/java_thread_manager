package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.ProductCatalog;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductExistsException;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
class JdbcProductCatalog implements ProductCatalog {

    private static final String COLUMNS = "id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation, "
            + "station, category, active, version";
    private static final String HQ_WATERMARK = "hq-catalog-version";

    private static final RowMapper<Product> PRODUCT = (rs, n) -> new Product(
            new ProductId(rs.getObject("id", UUID.class)),
            Sku.of(rs.getString("sku")),
            rs.getString("name"),
            Money.of(rs.getBigDecimal("unit_price"), Currency.getInstance(rs.getString("currency"))),
            new TaxRate(rs.getBigDecimal("tax_rate_percent")),
            rs.getBoolean("requires_preparation"),
            rs.getString("station"),
            rs.getString("category"),
            rs.getBoolean("active"),
            rs.getLong("version"));

    private final JdbcClient jdbc;

    JdbcProductCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Sku, Product> findBySkus(Collection<Sku> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM product WHERE sku IN (:skus)")
                .param("skus", skus.stream().map(Sku::value).toList())
                .query(PRODUCT)
                .list().stream()
                .collect(Collectors.toMap(Product::sku, Function.identity()));
    }

    @Override
    public Optional<Product> findBySku(Sku sku) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product WHERE sku = :sku")
                .param("sku", sku.value())
                .query(PRODUCT)
                .optional();
    }

    @Override
    public List<Product> findAll(boolean includeInactive) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product WHERE active OR :all ORDER BY sku")
                .param("all", includeInactive)
                .query(PRODUCT)
                .list();
    }

    @Override
    public void insert(Product product) {
        try {
            insertRow(product);
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent creation of the same SKU.
            throw new ProductExistsException(product.sku());
        }
    }

    private void insertRow(Product product) {
        bind(jdbc.sql("""
                INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation,
                                     station, category, active, version)
                VALUES (:id, :sku, :name, :price, :currency, :rate, :prep, :station, :category, :active, :version)
                """), product).update();
    }

    @Override
    public boolean update(Product product, long expectedVersion) {
        return bind(jdbc.sql("""
                        UPDATE product
                           SET name = :name, unit_price = :price, currency = :currency, tax_rate_percent = :rate,
                               requires_preparation = :prep, station = :station, category = :category,
                               active = :active, version = :version, updated_at = now()
                         WHERE id = :id AND version = :expected
                        """), product)
                .param("expected", expectedVersion)
                .update() == 1;
    }

    @Override
    public boolean upsertIfNewer(Product product) {
        return bind(jdbc.sql("""
                        INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation,
                                             station, category, active, version)
                        VALUES (:id, :sku, :name, :price, :currency, :rate, :prep, :station, :category, :active, :version)
                        ON CONFLICT (id) DO UPDATE
                           SET sku = EXCLUDED.sku, name = EXCLUDED.name, unit_price = EXCLUDED.unit_price,
                               currency = EXCLUDED.currency, tax_rate_percent = EXCLUDED.tax_rate_percent,
                               requires_preparation = EXCLUDED.requires_preparation, station = EXCLUDED.station,
                               category = EXCLUDED.category, active = EXCLUDED.active, version = EXCLUDED.version,
                               updated_at = now()
                         WHERE product.version < EXCLUDED.version
                        """), product)
                .update() == 1;
    }

    @Override
    public long hqCatalogWatermark() {
        return jdbc.sql("SELECT value FROM sync_state WHERE name = :name")
                .param("name", HQ_WATERMARK)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Override
    public void saveHqCatalogWatermark(long version) {
        jdbc.sql("""
                        INSERT INTO sync_state (name, value) VALUES (:name, :value)
                        ON CONFLICT (name) DO UPDATE
                           SET value = GREATEST(sync_state.value, EXCLUDED.value), updated_at = now()
                        """)
                .param("name", HQ_WATERMARK)
                .param("value", version)
                .update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, Product p) {
        return sql.param("id", p.id().value())
                .param("sku", p.sku().value())
                .param("name", p.name())
                .param("price", p.unitPrice().amount())
                .param("currency", p.unitPrice().currency().getCurrencyCode())
                .param("rate", p.taxRate().percent())
                .param("prep", p.requiresPreparation())
                .param("station", p.station())
                .param("category", p.category())
                .param("active", p.active())
                .param("version", p.version());
    }
}
