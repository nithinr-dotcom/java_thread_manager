package com.fleet.storefront.application.port.out;

import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.refund.Refund;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    void insert(Order order);

    Optional<Order> findById(OrderId id);

    /** The order with its payments and refunds, without locking. */
    Optional<OrderAccount> findAccount(OrderId id);

    /**
     * Locks the order row until commit ({@code SELECT … FOR UPDATE}) and loads its account. Every
     * lifecycle change (pay, cancel, refund) goes through this lock, so they serialize per order.
     */
    Optional<OrderAccount> lockAccount(OrderId id);

    /** Most recently placed first, optionally only orders in {@code status}. */
    List<OrderAccount> findRecentAccounts(int limit, Optional<OrderStatus> status);

    void updateStatus(OrderId id, OrderStatus status, String reason);

    void insertPayment(Payment payment);

    void voidPayment(Payment voided);

    void insertRefund(Refund refund);

    /** Orders of the branch and business day in fiscal-number order (for chain verification). */
    List<Order> issuedOn(String branchCode, LocalDate businessDate);

    /** Refund documents of the branch and business day in fiscal-number order. */
    List<Refund> refundsIssuedOn(String branchCode, LocalDate businessDate);
}
