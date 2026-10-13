package com.fleet.storefront.domain.refund;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fiscal refund document number {@code REF-{BRANCH}-{YYYYMMDD}-{NNNN}}: its own gap-free daily series. */
public record RefundNumber(String branchCode, LocalDate businessDate, long sequence) {

    private static final Pattern BRANCH = Pattern.compile("[A-Z0-9]{2,16}");
    private static final Pattern FORMAT = Pattern.compile("REF-([A-Z0-9]{2,16})-(\\d{8})-(\\d{4,})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public RefundNumber {
        Objects.requireNonNull(branchCode, "branchCode");
        Objects.requireNonNull(businessDate, "businessDate");
        if (!BRANCH.matcher(branchCode).matches()) {
            throw new IllegalArgumentException("Invalid branch code: '" + branchCode + "'");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("Sequence must start at 1: " + sequence);
        }
    }

    public static RefundNumber parse(String value) {
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid refund number: '" + value + "'");
        }
        return new RefundNumber(m.group(1), LocalDate.parse(m.group(2), DATE), Long.parseLong(m.group(3)));
    }

    public String value() {
        return "REF-%s-%s-%04d".formatted(branchCode, DATE.format(businessDate), sequence);
    }

    @Override
    public String toString() {
        return value();
    }
}
