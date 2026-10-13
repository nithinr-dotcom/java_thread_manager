---
status: done
adr: 0016
---
# Inbound HQ sync for catalog and prices

- [x] Push (`PUT /api/v1/sync/catalog`, role HQ) and pull (scheduled, high-water mark) with last-writer-wins on `version`
- [x] Prices snapshot onto order lines — no issued order changes (`CatalogAndStockIT.priceChangesNeverAlterIssuedOrders`)
