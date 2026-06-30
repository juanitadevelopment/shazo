package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.jdbc.embedded.EmbeddedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Transactor} / {@link UnitOfWork}: multiple repositories
 * committing or rolling back together as a single transaction.
 */
class TransactorTest {

    record Widget(String id, String name) {}
    record Gadget(String id, String name) {}

    private static final AtomicInteger DB = new AtomicInteger();

    private DataSource ds;
    private Transactor transactor;
    private Describer<Widget, SqlCommand> widgets;
    private Describer<Gadget, SqlCommand> gadgets;

    @BeforeEach
    void setUp() throws Exception {
        ds = EmbeddedDataSource.inMemory("uow_" + DB.incrementAndGet());
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE widget (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200))");
            st.execute("CREATE TABLE gadget (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200))");
        }
        transactor = new Transactor(ds);

        widgets = Describer.<Widget, SqlCommand>builder()
            .contains(w -> List.of(SqlCommand.of("SELECT 1 FROM widget WHERE id = ?", w.id())))
            .store(w    -> List.of(SqlCommand.of(
                "MERGE INTO widget (id, name) KEY (id) VALUES (?, ?)", w.id(), w.name())))
            .delete(w   -> List.of(SqlCommand.of("DELETE FROM widget WHERE id = ?", w.id())))
            .retrieve(w -> List.of(SqlCommand.of("SELECT id, name FROM widget WHERE id = ?", w.id())))
            .catalog(w  -> List.of(SqlCommand.of("SELECT id, name FROM widget")))
            .infuser(r  -> r.first().map(row -> new Widget(
                (String) row.get("id"), (String) row.get("name"))).orElseThrow())
            .cataloger(r -> r.rows().stream().map(row -> new Widget(
                (String) row.get("id"), (String) row.get("name"))).toList())
            .build();

        gadgets = Describer.<Gadget, SqlCommand>builder()
            .contains(g -> List.of(SqlCommand.of("SELECT 1 FROM gadget WHERE id = ?", g.id())))
            .store(g    -> List.of(SqlCommand.of(
                "MERGE INTO gadget (id, name) KEY (id) VALUES (?, ?)", g.id(), g.name())))
            .delete(g   -> List.of(SqlCommand.of("DELETE FROM gadget WHERE id = ?", g.id())))
            .retrieve(g -> List.of(SqlCommand.of("SELECT id, name FROM gadget WHERE id = ?", g.id())))
            .catalog(g  -> List.of(SqlCommand.of("SELECT id, name FROM gadget")))
            .infuser(r  -> r.first().map(row -> new Gadget(
                (String) row.get("id"), (String) row.get("name"))).orElseThrow())
            .cataloger(r -> r.rows().stream().map(row -> new Gadget(
                (String) row.get("id"), (String) row.get("name"))).toList())
            .build();
    }

    @Test
    void commitsMultipleRepositoriesAtomically() throws ShazoException {
        transactor.execute(uow -> {
            uow.repository(widgets).store(new Widget("w1", "Widget One"));
            uow.repository(gadgets).store(new Gadget("g1", "Gadget One"));
            return null;
        });

        assertThat(new JdbcRepository<>(ds, widgets).contains(new Widget("w1", null))).isTrue();
        assertThat(new JdbcRepository<>(ds, gadgets).contains(new Gadget("g1", null))).isTrue();
    }

    @Test
    void rollsBackAllRepositoriesOnFailure() throws ShazoException {
        assertThatThrownBy(() -> transactor.execute(uow -> {
            uow.repository(widgets).store(new Widget("w2", "Widget Two"));
            uow.repository(gadgets).store(new Gadget("g2", "Gadget Two"));
            throw new ShazoException("boom");
        })).isInstanceOf(ShazoException.class);

        // Neither write survives the rollback.
        assertThat(new JdbcRepository<>(ds, widgets).contains(new Widget("w2", null))).isFalse();
        assertThat(new JdbcRepository<>(ds, gadgets).contains(new Gadget("g2", null))).isFalse();
    }

    @Test
    void returnsTaskResult() throws ShazoException {
        String id = transactor.execute(uow -> {
            uow.repository(widgets).store(new Widget("w3", "Three"));
            return "w3";
        });
        assertThat(id).isEqualTo("w3");
    }

    @Test
    void repositoriesWithinUnitOfWorkSeeEachOthersUncommittedWrites() throws ShazoException {
        boolean seen = transactor.execute(uow -> {
            var repo = uow.repository(widgets);
            repo.store(new Widget("w4", "Four"));
            // Same connection → the uncommitted row is visible within the unit of work.
            return repo.contains(new Widget("w4", null));
        });
        assertThat(seen).isTrue();
    }

    @Test
    void guardedConnectionForbidsTransactionBoundaryOperations() throws ShazoException {
        transactor.execute(uow -> {
            var conn = uow.connection();
            assertThatThrownBy(conn::commit).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(conn::rollback).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(conn::close).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> conn.setAutoCommit(true))
                .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> conn.abort(Runnable::run))
                .isInstanceOf(UnsupportedOperationException.class);
            return null;
        });
    }

    @Test
    void guardedConnectionStillAllowsOrdinaryOperations() throws ShazoException {
        transactor.execute(uow -> {
            var conn = uow.connection();
            try {
                // A representative non-boundary call is forwarded to the real connection.
                assertThat(conn.getAutoCommit()).isFalse();      // Transactor disabled it
                assertThat(conn.getMetaData()).isNotNull();
                assertThat(conn.isClosed()).isFalse();
            } catch (Exception e) {
                throw new ShazoException("forwarded call failed", e);
            }
            return null;
        });
    }

    @Test
    void guardedConnectionUnwrapsUnderlyingSqlException() throws ShazoException {
        // A forwarded call that fails must surface the real SQLException, not the
        // proxy's InvocationTargetException / UndeclaredThrowableException.
        transactor.execute(uow -> {
            assertThatThrownBy(() -> uow.connection().prepareStatement("SELECT * FROM no_such_table").execute())
                .isInstanceOf(java.sql.SQLException.class);
            return null;
        });
    }

    @Test
    void exposesRawConnectionParticipatingInTheTransaction() throws ShazoException {
        transactor.execute(uow -> {
            try (var ps = uow.connection().prepareStatement(
                    "INSERT INTO widget (id, name) VALUES (?, ?)")) {
                ps.setString(1, "w5");
                ps.setString(2, "Five via raw SQL");
                ps.executeUpdate();
            } catch (Exception e) {
                throw new ShazoException("raw insert failed", e);
            }
            return null;
        });

        Repository<Widget> repo = new JdbcRepository<>(ds, widgets);
        assertThat(repo.retrieve(new Widget("w5", null)))
            .map(Widget::name).contains("Five via raw SQL");
    }
}
