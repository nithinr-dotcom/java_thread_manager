package com.fleet.storefront.application.port.in;

import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.Sku;

import java.util.List;

public interface StockMovementsQuery {

    /** The product's stock journal, newest first. */
    List<StockMovementView> movements(Sku sku, Limit limit);
}
