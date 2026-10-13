package com.fleet.storefront.domain.order;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fiscal document number {@code ORD-{BRANCH}-{YYYYMMDD}-{NNNN}}: gap-free and sequential per branch
 * per business day. The sequence is padded to four digits and simply grows wider beyond 9999.
 */
public record OrderNumber(String branchCode, LocalDate businessDate, long sequence) implements Comparable<OrderNumber> {

    private static final Pattern BRANCH = Pattern.compile("[A-Z0-9]{2,16}");
    private static final Pattern FORMAT = Pattern.compile("ORD-([A-Z0-9]{2,16})-(\\d{8})-(\\d{4,})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public OrderNumber {
        Objects.requireNonNull(branchCode, "branchCode");
        Objects.requireNonNull(businessDate, "businessDate");
        if (!BRANCH.matcher(branchCode).matches()) {
            throw new IllegalArgumentException("Invalid branch code: '" + branchCode + "'");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("Sequence must start at 1: " + sequence);
        }
    }

    public static OrderNumber parse(String value) {
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid order number: '" + value + "'");
        }
        return new OrderNumber(m.group(1), LocalDate.parse(m.group(2), DATE), Long.parseLong(m.group(3)));
    }

    public String value() {
        return "ORD-%s-%s-%04d".formatted(branchCode, DATE.format(businessDate), sequence);
    }

    @Override
    public int compareTo(OrderNumber other) {
        int c = branchCode.compareTo(other.branchCode);
        if (c != 0) {
            return c;
        }
        c = businessDate.compareTo(other.businessDate);
        return c != 0 ? c : Long.compare(sequence, other.sequence);
    }

    @Override
    public String toString() {
        return value();
    }
}
