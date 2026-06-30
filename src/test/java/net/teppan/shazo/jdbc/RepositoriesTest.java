package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
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
 * Tests for {@link Repositories}: type-dispatching, heterogeneous, varargs store.
 */
class RepositoriesTest {

    record Widget(String id, String name) {}
    record Gadget(String id, String name) {}

    private static final AtomicInteger DB = new AtomicInteger();

    private DataSource ds;
    private Transactor transactor;
    private Repositories repos;

    @BeforeEach
    void setUp() throws Exception {
        ds = EmbeddedDataSource.inMemory("repos_" + DB.incrementAndGet());
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE widget (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200))");
            st.execute("CREATE TABLE gadget (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200))");
        }
        transactor = new Transactor(ds);

        Describer<Widget, SqlCommand> widgets = Describer.<Widget, SqlCommand>builder()
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

        Describer<Gadget, SqlCommand> gadgets = Describer.<Gadget, SqlCommand>builder()
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

        repos = Repositories.builder()
            .register(Widget.class, widgets)
            .register(Gadget.class, gadgets)
            .build();
    }

    @Test
    void storesHeterogeneousTypesInOneTransaction() throws ShazoException {
        transactor.execute(uow -> {
            repos.in(uow).store(new Widget("w1", "One"), new Gadget("g1", "Uno"));
            return null;
        });

        transactor.execute(uow -> {
            var r = repos.in(uow);
            assertThat(r.retrieve(Widget.class, new Widget("w1", null)))
                .map(Widget::name).contains("One");
            assertThat(r.retrieve(Gadget.class, new Gadget("g1", null)))
                .map(Gadget::name).contains("Uno");
            return null;
        });
    }

    @Test
    void varargsStoreRollsBackTogether() throws ShazoException {
        // The second describer is fine, but force a failure after both queued by
        // throwing from the task; nothing should persist.
        assertThatThrownBy(() -> transactor.execute(uow -> {
            repos.in(uow).store(new Widget("w2", "Two"), new Gadget("g2", "Dos"));
            throw new ShazoException("boom");
        })).isInstanceOf(ShazoException.class);

        transactor.execute(uow -> {
            assertThat(repos.in(uow).contains(new Widget("w2", null))).isFalse();
            assertThat(repos.in(uow).contains(new Gadget("g2", null))).isFalse();
            return null;
        });
    }

    @Test
    void emptyStoreIsNoOp() throws ShazoException {
        transactor.execute(uow -> { repos.in(uow).store(); return null; });
    }

    @Test
    void unregisteredTypeThrows() {
        record Sprocket(String id) {}
        // The IllegalArgumentException is raised inside the unit of work, so the
        // Transactor wraps it in a ShazoException.
        assertThatThrownBy(() -> transactor.execute(uow -> {
            repos.in(uow).store(new Sprocket("s1"));
            return null;
        })).isInstanceOf(ShazoException.class)
           .hasRootCauseInstanceOf(IllegalArgumentException.class)
           .rootCause().hasMessageContaining("Sprocket");
    }

    @Test
    void deleteDispatchesByType() throws ShazoException {
        transactor.execute(uow -> {
            repos.in(uow).store(new Widget("w3", "Three"), new Gadget("g3", "Tres"));
            return null;
        });
        transactor.execute(uow -> {
            repos.in(uow).delete(new Widget("w3", null), new Gadget("g3", null));
            return null;
        });
        transactor.execute(uow -> {
            assertThat(repos.in(uow).contains(new Widget("w3", null))).isFalse();
            assertThat(repos.in(uow).contains(new Gadget("g3", null))).isFalse();
            return null;
        });
    }

    @Test
    void registryReportsHandledTypes() {
        assertThat(repos.handles(Widget.class)).isTrue();
        assertThat(repos.types()).containsExactlyInAnyOrder(Widget.class, Gadget.class);
        assertThatThrownBy(() -> repos.describerFor(String.class))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
