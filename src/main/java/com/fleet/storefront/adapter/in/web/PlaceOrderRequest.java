package com.fleet.storefront.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlaceOrderRequest(@NotEmpty @Size(max = 100) List<@Valid Item> items) {

    public record Item(@NotBlank String sku, @Min(1) @Max(999) int quantity) {
    }
}
