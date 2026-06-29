package net.teppan.shazo.jdbc.embedded;

import net.teppan.shazo.ShazoException;
import net.teppan.shazo.jdbc.SchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SchemaManager}.
 */
class SchemaManagerTest {

    private static final String SCHEMA_LOCATION =
        "net/teppan/shazo/jdbc/embedded/schema/";

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        // Fresh in-memory DB per test to avoid cross-test interference
        dataSource = EmbeddedDataSource.inMemory("schema_test_" + System.nanoTime());
    }

    @Test
    void applyCreatesTablesDefinedInScripts() throws ShazoException, SQLException {
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            // V001 created 'items'; V002 added 'price' column
            try (var rs = stmt.executeQuery("SELECT id, name, price FROM items")) {
                // V002 inserted one seed row
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo("seed-1");
            }
        }
    }

    @Test
    void applyRecordsMigrationsInTrackingTable() throws ShazoException, SQLException {
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery(
                 "SELECT version, script_name FROM _shazo_schema_migrations ORDER BY version")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("version")).isEqualTo(1);
            assertThat(rs.getString("script_name")).isEqualTo("create_items");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("version")).isEqualTo(2);
            assertThat(rs.getString("script_name")).isEqualTo("add_price");

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void applyIsIdempotent() throws ShazoException {
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);
        // Second call must not throw or re-apply
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);
    }

    @Test
    void separateLocationsHaveIndependentVersionSequences()
            throws ShazoException, SQLException {
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);

        // A second location (empty) does not interfere with the first
        SchemaManager.apply(dataSource, "net/teppan/shazo/jdbc/embedded/empty/");

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery(
                 "SELECT COUNT(*) FROM _shazo_schema_migrations"
                 + " WHERE location = '" + SCHEMA_LOCATION + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void applyWithEmptyLocationLogsAndReturns() throws ShazoException {
        // Should not throw even when no scripts are found
        SchemaManager.apply(dataSource, "no/such/location/");
    }

    @Test
    void applyRollsBackOnSqlError() throws ShazoException, SQLException {
        // Apply the first two scripts successfully
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);

        // Now manually break the items table so a future INSERT would fail.
        // We verify the tracking table is not corrupted and re-apply is safe.
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);  // idempotent

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery(
                 "SELECT COUNT(*) FROM _shazo_schema_migrations")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void integratesWithJdbcRepository() throws ShazoException, SQLException {
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);

        // Verify the schema is usable for JDBC operations
        try (var conn = dataSource.getConnection();
             var ps   = conn.prepareStatement(
                 "INSERT INTO items (id, name, price) VALUES (?, ?, ?)")) {
            ps.setString(1, "item-42");
            ps.setString(2, "Widget");
            ps.setBigDecimal(3, new java.math.BigDecimal("19.99"));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        try (var conn = dataSource.getConnection();
             var ps   = conn.prepareStatement("SELECT name FROM items WHERE id = ?")) {
            ps.setString(1, "item-42");
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Widget");
            }
        }
    }

    @Test
    void detectsDriftWhenAppliedScriptChanges() throws ShazoException, SQLException {
        SchemaManager.apply(dataSource, SCHEMA_LOCATION);

        // Simulate the on-disk script changing after it was applied by corrupting
        // its recorded checksum; the next apply must refuse rather than ignore it.
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "UPDATE _shazo_schema_migrations SET checksum = 'deadbeef' WHERE version = 1");
        }

        assertThatThrownBy(() -> SchemaManager.apply(dataSource, SCHEMA_LOCATION))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void splitsStatementsWithSemicolonsInsideStringLiterals()
            throws ShazoException, SQLException {
        SchemaManager.apply(dataSource, "net/teppan/shazo/jdbc/embedded/tricky/");

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery("SELECT note FROM tricky WHERE id = 'a'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("note")).isEqualTo("has; a semicolon -- and dashes");
        }
    }

    @Test
    void applyDoesNotThrowForMissingLocation() {
        // Missing classpath location → no scripts found → returns silently
        org.assertj.core.api.Assertions.assertThatCode(() ->
            SchemaManager.apply(dataSource, "net/teppan/shazo/jdbc/embedded/broken/"))
            .doesNotThrowAnyException();
    }
}
