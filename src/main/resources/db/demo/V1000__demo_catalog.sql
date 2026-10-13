-- Demo catalog, only loaded with the "demo" profile (spring.flyway.locations includes classpath:db/demo).
INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation) VALUES
    ('01926b3a-0000-7000-8000-000000000001', 'MEALBOX-CHICKEN', 'Fresh chicken meal box', 8.95, 'USD', 8.25, FALSE),
    ('01926b3a-0000-7000-8000-000000000002', 'WATER-SPARKLING', 'Sparkling water 500ml',  2.50, 'USD', 8.25, FALSE),
    ('01926b3a-0000-7000-8000-000000000003', 'BURGER-CLASSIC',  'Classic burger',         11.40, 'USD', 8.25, TRUE),
    ('01926b3a-0000-7000-8000-000000000004', 'LATTE-LARGE',     'Large latte',            4.75, 'USD', 8.25, TRUE);

INSERT INTO inventory (product_id, on_hand) VALUES
    ('01926b3a-0000-7000-8000-000000000001', 10),
    ('01926b3a-0000-7000-8000-000000000002', 200),
    ('01926b3a-0000-7000-8000-000000000003', 50),
    ('01926b3a-0000-7000-8000-000000000004', 500);
