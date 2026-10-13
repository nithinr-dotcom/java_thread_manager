package com.fleet.storefront.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRIBUTING.md invariants 3, 7 and 8 at the schema level: applied migrations are immutable, versions are
 * contiguous, and no migration introduces a SEQUENCE / SERIAL / IDENTITY (gaps on rollback, and
 * auto-increment keys collide across edge nodes).
 */
class MigrationLockTest {

    private static final Path LOCK = Path.of("src/test/resources/db/migration.lock");
    private static final List<Path> MIGRATION_DIRS =
            List.of(Path.of("src/main/resources/db/migration"), Path.of("src/main/resources/db/demo"));
    private static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\bcreate\\s+sequence\\b|\\bnextval\\s*\\(|\\b(big|small)?serial\\b|\\bgenerated\\s+.*\\bas\\s+identity\\b|\\bauto_increment\\b");

    @Test
    void appliedMigrationsAreNeverEditedOrDeleted() throws IOException {
        Map<String, String> locked = lockedChecksums();
        List<String> violations = new ArrayList<>();
        locked.forEach((path, sha) -> {
            Path file = Path.of(path);
            if (!Files.exists(file)) {
                violations.add(path + " was deleted — applied migrations must never be removed");
            } else if (!sha.equals(sha256(file))) {
                violations.add(path + " was edited — write a new V{n+1}__*.sql migration instead");
            }
        });
        assertThat(violations).as("immutable migrations").isEmpty();
    }

    @Test
    void everyMigrationIsRecordedInTheLock() throws IOException {
        Map<String, String> locked = lockedChecksums();
        List<String> missing = migrations()
                .filter(f -> !locked.containsKey(unix(f)))
                .map(f -> "append to " + LOCK + ":  " + sha256(f) + "  " + unix(f))
                .toList();
        assertThat(missing).as("new migrations must be added to the lock file").isEmpty();
    }

    @Test
    void migrationsAreNamedAndNumberedContiguously() throws IOException {
        List<Integer> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIRS.getFirst())) {
            for (Path file : files.sorted().toList()) {
                Matcher m = NAME.matcher(file.getFileName().toString());
                assertThat(m.matches()).as(file + " must be named V{n}__lower_snake_case.sql").isTrue();
                versions.add(Integer.parseInt(m.group(1)));
            }
        }
        versions.sort(Integer::compare);
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).as("schema versions must be 1..n without gaps or duplicates").isEqualTo(i + 1);
        }
    }

    @Test
    void noMigrationUsesSequencesOrAutoIncrement() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : migrations().toList()) {
            String sql = withoutComments(Files.readString(file)).toLowerCase(Locale.ROOT);
            Matcher m = FORBIDDEN.matcher(sql);
            if (m.find()) {
                violations.add(unix(file) + " uses '" + m.group() + "' — use UUIDv7 keys / the fiscal_sequence counter row");
            }
        }
        assertThat(violations).isEmpty();
    }

    private static Map<String, String> lockedChecksums() throws IOException {
        Map<String, String> locked = new LinkedHashMap<>();
        for (String line : Files.readAllLines(LOCK)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            locked.put(parts[1], parts[0]);
        }
        return locked;
    }

    private static Stream<Path> migrations() throws IOException {
        List<Path> all = new ArrayList<>();
        for (Path dir : MIGRATION_DIRS) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".sql")).sorted().forEach(all::add);
            }
        }
        return all.stream();
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String unix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
