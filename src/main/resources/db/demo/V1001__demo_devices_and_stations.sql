-- Demo devices (profile "demo" only). Plain-text keys, for local testing ONLY:
--   sf_demo_manager (MANAGER), sf_demo_pos (POS), sf_demo_kitchen (KITCHEN), sf_demo_hq (HQ)
INSERT INTO terminal (id, terminal_id, name, role, api_key_hash, created_at) VALUES
    ('01926b3a-0000-7000-8000-0000000000a1', 'demo-manager', 'Demo back office', 'MANAGER', 'b6f15a352183d4f2430fd51a5ba23ac96a76f744b5d84296dffdc7ab173397d3', now()),
    ('01926b3a-0000-7000-8000-0000000000a2', 'demo-pos',     'Demo front counter', 'POS',   '4e52c1648c5242fb4f7ca550349f7149de93bd50657322fe10a609d3eeb4c110', now()),
    ('01926b3a-0000-7000-8000-0000000000a3', 'demo-kitchen', 'Demo kitchen screen', 'KITCHEN', 'd1c3d4b8b4efdafeb9604517f5a4a83b1f4b3a30bf55f56b0c2da0bb4cdc709a', now()),
    ('01926b3a-0000-7000-8000-0000000000a4', 'demo-hq',      'Demo HQ sync client', 'HQ',  '445f23415d1c04cca06d1e4eef6605775682843516378b911debb49c63d60dda', now());

UPDATE product SET station = 'GRILL',  category = 'Mains'  WHERE sku = 'BURGER-CLASSIC';
UPDATE product SET station = 'BAR',    category = 'Drinks' WHERE sku = 'LATTE-LARGE';
UPDATE product SET station = 'BAR',    category = 'Drinks' WHERE sku = 'WATER-SPARKLING';
UPDATE product SET station = 'KITCHEN', category = 'Grab & go' WHERE sku = 'MEALBOX-CHICKEN';

INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation, station, category) VALUES
    ('01926b3a-0000-7000-8000-000000000005', 'FRIES-REGULAR', 'Fries',            3.20, 'USD', 8.25, TRUE,  'GRILL', 'Sides'),
    ('01926b3a-0000-7000-8000-000000000006', 'SALAD-GARDEN',  'Garden salad',     6.80, 'USD', 8.25, TRUE,  'KITCHEN', 'Mains'),
    ('01926b3a-0000-7000-8000-000000000007', 'COOKIE-CHOC',   'Chocolate cookie', 1.95, 'USD', 8.25, FALSE, 'KITCHEN', 'Grab & go');
INSERT INTO inventory (product_id, on_hand) VALUES
    ('01926b3a-0000-7000-8000-000000000005', 80),
    ('01926b3a-0000-7000-8000-000000000006', 25),
    ('01926b3a-0000-7000-8000-000000000007', 60);
