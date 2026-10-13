package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.adapter.in.web.security.TerminalPrincipal;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.RestockUseCase;
import com.fleet.storefront.application.port.in.RestockUseCase.AdjustStockCommand;
import com.fleet.storefront.application.port.in.RestockUseCase.RestockCommand;
import com.fleet.storefront.application.port.in.StockMovementsQuery;
import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController {

    private static final String IDEMPOTENCY_KEY = OrderController.IDEMPOTENCY_KEY;

    private final RestockUseCase stock;
    private final StockMovementsQuery movements;

    InventoryController(RestockUseCase stock, StockMovementsQuery movements) {
        this.stock = stock;
        this.movements = movements;
    }

    @PostMapping("/{sku}/restock")
    ResponseEntity<StockLevelResponse> restock(@AuthenticationPrincipal TerminalPrincipal device,
                                               @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                               @PathVariable String sku, @Valid @RequestBody RestockRequest request) {
        RestockCommand command = Requests.parse(() -> new RestockCommand(Sku.of(sku), request.quantity(),
                request.note(), device.terminalId(), idempotencyKey));
        return respond(stock.restock(command));
    }

    @PostMapping("/{sku}/adjustments")
    ResponseEntity<StockLevelResponse> adjust(@AuthenticationPrincipal TerminalPrincipal device,
                                              @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                              @PathVariable String sku, @Valid @RequestBody AdjustmentRequest request) {
        AdjustStockCommand command = Requests.parse(() -> new AdjustStockCommand(Sku.of(sku), request.delta(),
                request.reason(), request.note(), device.terminalId(), idempotencyKey));
        return respond(stock.adjust(command));
    }

    private static ResponseEntity<StockLevelResponse> respond(RestockUseCase.StockLevel level) {
        return ResponseEntity.ok()
                .header(OrderController.REPLAYED, Boolean.toString(level.replayed()))
                .body(StockLevelResponse.from(level));
    }

    @GetMapping("/{sku}/movements")
    List<StockMovementView> movements(@PathVariable String sku, @RequestParam(defaultValue = "50") int limit) {
        Sku parsed = Requests.parse(() -> Sku.of(sku));
        Limit max = Requests.parse(() -> new Limit(limit));
        return movements.movements(parsed, max);
    }

    record RestockRequest(@Min(1) @Max(RestockCommand.MAX_QUANTITY) int quantity, @Size(max = 200) String note) {
    }

    record AdjustmentRequest(@Min(-RestockCommand.MAX_QUANTITY) @Max(RestockCommand.MAX_QUANTITY) int delta,
                             @NotNull StockChangeReason reason, @Size(max = 200) String note) {
    }

    record StockLevelResponse(String sku, int onHand) {
        static StockLevelResponse from(RestockUseCase.StockLevel level) {
            return new StockLevelResponse(level.sku().value(), level.onHand());
        }
    }
}
