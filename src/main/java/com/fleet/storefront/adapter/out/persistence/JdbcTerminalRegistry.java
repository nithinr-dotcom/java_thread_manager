package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.TerminalRegistry;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.terminal.Role;
import com.fleet.storefront.domain.terminal.Terminal;
import com.fleet.storefront.domain.terminal.TerminalExistsException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcTerminalRegistry implements TerminalRegistry {

    private static final String COLUMNS = "id, terminal_id, name, role, created_at, revoked_at";

    private static final RowMapper<Terminal> TERMINAL = (rs, n) -> {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new Terminal(rs.getObject("id", UUID.class), new TerminalId(rs.getString("terminal_id")),
                rs.getString("name"), Role.valueOf(rs.getString("role")), rs.getTimestamp("created_at").toInstant(),
                revoked == null ? null : revoked.toInstant());
    };

    private final JdbcClient jdbc;

    JdbcTerminalRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Terminal> findByKeyHash(String apiKeyHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM terminal WHERE api_key_hash = :hash")
                .param("hash", apiKeyHash)
                .query(TERMINAL)
                .optional();
    }

    @Override
    public Optional<Terminal> findByTerminalId(TerminalId terminalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM terminal WHERE terminal_id = :terminal")
                .param("terminal", terminalId.value())
                .query(TERMINAL)
                .optional();
    }

    @Override
    public List<Terminal> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM terminal ORDER BY terminal_id").query(TERMINAL).list();
    }

    @Override
    public void insert(Terminal terminal, String apiKeyHash) {
        try {
            insertRow(terminal, apiKeyHash);
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent registration of the same id.
            throw new TerminalExistsException(terminal.terminalId());
        }
    }

    private void insertRow(Terminal terminal, String apiKeyHash) {
        jdbc.sql("""
                        INSERT INTO terminal (id, terminal_id, name, role, api_key_hash, created_at)
                        VALUES (:id, :terminal, :name, :role, :hash, :created)
                        """)
                .param("id", terminal.id())
                .param("terminal", terminal.terminalId().value())
                .param("name", terminal.name())
                .param("role", terminal.role().name())
                .param("hash", apiKeyHash)
                .param("created", Timestamp.from(terminal.createdAt()))
                .update();
    }

    @Override
    public Optional<Terminal> revoke(TerminalId terminalId, Instant at) {
        return jdbc.sql("""
                        UPDATE terminal SET revoked_at = COALESCE(revoked_at, :at)
                         WHERE terminal_id = :terminal
                        RETURNING
                        """ + COLUMNS)
                .param("at", Timestamp.from(at))
                .param("terminal", terminalId.value())
                .query(TERMINAL)
                .optional();
    }

    @Override
    public long countActiveForUpdate(Role role) {
        return jdbc.sql("SELECT id FROM terminal WHERE role = :role AND revoked_at IS NULL FOR UPDATE")
                .param("role", role.name())
                .query(UUID.class)
                .list()
                .size();
    }

    @Override
    public long countActive(Role role) {
        return jdbc.sql("SELECT count(*) FROM terminal WHERE role = :role AND revoked_at IS NULL")
                .param("role", role.name())
                .query(Long.class)
                .single();
    }
}
