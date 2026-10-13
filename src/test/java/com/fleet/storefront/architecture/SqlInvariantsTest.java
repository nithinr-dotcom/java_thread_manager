package com.fleet.storefront.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants that live in SQL strings, which neither the compiler nor ArchUnit can see. Scans
 * {@code src/main/java}; each failure names the CONTRIBUTING.md invariant it protects.
 */
class SqlInvariantsTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STOCK_WRITE =
            Pattern.compile("\\b(update\\s+inventory|insert\\s+into\\s+inventory|delete\\s+from\\s+inventory)\\b");
    private static final Pattern FISCAL_WRITE =
            Pattern.compile("\\b(update\\s+fiscal_sequence|insert\\s+into\\s+fiscal_sequence|delete\\s+from\\s+fiscal_sequence)\\b");

    @Test
    void onlyTheInventoryLedgerWritesStock() {
        assertThat(filesMatching(STOCK_WRITE))
                .as("invariant 1: stock changes only through JdbcInventoryLedger's atomic SQL")
                .containsOnly("JdbcInventoryLedger.java");
    }

    @Test
    void everyStockDecrementIsGuardedInTheSameStatement() {
        String ledger = sources().get("JdbcInventoryLedger.java");
        Matcher blocks = TEXT_BLOCK.matcher(ledger);
        int decrements = 0;
        while (blocks.find()) {
            String sql = normalise(blocks.group(1));
            if (sql.contains("on_hand = on_hand -")) {
                decrements++;
                assertThat(sql)
                        .as("invariant 1: a decrement must carry 'WHERE … on_hand >= :qty' (no read-modify-write)")
                        .containsPattern("where .*on_hand >= :qty");
            }
        }
        assertThat(decrements).as("the guarded decrement statement exists").isPositive();
    }

    @Test
    void onlyTheFiscalSequenceAdapterAllocatesNumbers() {
        assertThat(filesMatching(FISCAL_WRITE))
                .as("invariant 3: fiscal numbers come only from the transactional counter row")
                .containsOnly("JdbcFiscalSequence.java");
        assertThat(filesMatching(Pattern.compile("\\bnextval\\s*\\(|\\bcreate\\s+sequence\\b")))
                .as("invariant 3: never a PostgreSQL SEQUENCE (non-transactional → gaps)")
                .isEmpty();
    }

    @Test
    void idempotencyClaimIsAnInsertOnConflictDoNothing() {
        String store = normalise(sources().get("JdbcIdempotencyStore.java"));
        assertThat(store)
                .as("invariant 4: the claim must block on concurrent duplicates via the primary key")
                .containsPattern("insert into idempotency_key .*on conflict \\(terminal_id, idempotency_key\\) do nothing");
    }

    @Test
    void transactionBoundariesBelongToUseCases() {
        assertThat(filesMatching(Pattern.compile("@transactional\\b")))
                .as("transactions are opened by use cases through the TransactionRunner port, not by annotations")
                .isEmpty();
    }

    private static List<String> filesMatching(Pattern pattern) {
        return sources().entrySet().stream()
                .filter(e -> pattern.matcher(normalise(e.getValue())).find())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Map<String, String> sources() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), SqlInvariantsTest::read));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalise(String source) {
        return source.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
