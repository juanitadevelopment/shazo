package net.teppan.shazo.jdbc;

import net.teppan.shazo.ShazoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Lightweight schema migration runner for JDBC databases.
 *
 * <p>Scans a classpath location for versioned SQL scripts, compares them
 * against previously applied migrations tracked in a
 * {@code _shazo_schema_migrations} table, and applies any scripts not yet
 * recorded — in version order, within a single transaction.
 *
 * <p>This class is intentionally JDBC-generic and works with any
 * {@link DataSource}, not only embedded H2. The migration tracking table
 * uses standard SQL compatible with H2, PostgreSQL, and most modern RDBMS.
 *
 * <h2>Script naming convention</h2>
 * <p>Files must follow the pattern {@code V<version>__<description>.sql}
 * where {@code <version>} is a positive integer (leading zeros allowed):
 * <pre>
 *   V001__create_users.sql
 *   V002__add_email_index.sql
 *   V010__rename_column.sql
 * </pre>
 * Scripts are applied in ascending version order.
 *
 * <h2>Statement parsing</h2>
 * <p>Each script is split into statements on top-level semicolons. The splitter
 * is aware of {@code --} line comments, {@code /*  *}{@code /} block comments,
 * single-quoted string literals (including doubled {@code ''} escapes),
 * double-quoted identifiers, and PostgreSQL dollar-quoted blocks
 * ({@code $$ ... $$} / {@code $tag$ ... $tag$}), so semicolons inside those
 * constructs do not split a statement.
 *
 * <h2>Drift detection</h2>
 * <p>Each applied script's SHA-256 checksum (line-ending-normalised) is recorded.
 * If a script that has already been applied is later edited, the next
 * {@code apply} fails with {@link ShazoException} rather than silently ignoring
 * the change. Applied migrations are immutable: add a new versioned script
 * instead of editing an old one.
 *
 * <h2>Concurrency</h2>
 * <p>Concurrent {@code apply} calls within a JVM are serialized. The migration
 * transaction plus the {@code UNIQUE(location, version)} constraint guard
 * against duplicate application; for first-time migration across multiple JVMs,
 * run migrations from a single instance.
 *
 * <h2>Idempotency</h2>
 * <p>Calling {@code apply} multiple times with the same arguments is safe:
 * already-applied, unchanged scripts are skipped.
 *
 * @see net.teppan.shazo.jdbc.embedded.EmbeddedDataSource
 */
public final class SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);

    private static final String MIGRATION_TABLE = "_shazo_schema_migrations";

    /** Serializes concurrent apply() calls within this JVM. */
    private static final ReentrantLock MIGRATION_LOCK = new ReentrantLock();

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS _shazo_schema_migrations (
                id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                location    VARCHAR(500) NOT NULL,
                version     INT          NOT NULL,
                script_name VARCHAR(500) NOT NULL,
                checksum    VARCHAR(64),
                applied_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_shazo_migration UNIQUE (location, version)
            )
            """;

    // Adds the checksum column to tracking tables created by older versions.
    private static final String ADD_CHECKSUM_COLUMN =
        "ALTER TABLE " + MIGRATION_TABLE + " ADD COLUMN IF NOT EXISTS checksum VARCHAR(64)";

    private static final Pattern SCRIPT_PATTERN =
        Pattern.compile("V(\\d+)__(.+)\\.sql", Pattern.CASE_INSENSITIVE);

    /**
     * Scans {@code classpathLocation} for versioned SQL scripts and applies
     * any that have not yet been recorded in the migration tracking table.
     *
     * <p>All pending scripts are applied in a single JDBC transaction.
     * If any script fails, the transaction is rolled back and no migration
     * is recorded.
     *
     * @param dataSource        the target database; never {@code null}
     * @param classpathLocation the classpath directory containing
     *                          {@code V<n>__<desc>.sql} scripts
     *                          (e.g. {@code "db/migration/"}); never {@code null}
     * @throws ShazoException   if script discovery, SQL execution, checksum
     *                          verification, or transaction management fails
     */
    public static void apply(DataSource dataSource, String classpathLocation)
            throws ShazoException {
        MIGRATION_LOCK.lock();
        try {
            applyLocked(dataSource, classpathLocation);
        } finally {
            MIGRATION_LOCK.unlock();
        }
    }

    private static void applyLocked(DataSource dataSource, String classpathLocation)
            throws ShazoException {
        var scripts = findScripts(classpathLocation);
        if (scripts.isEmpty()) {
            log.info("No migration scripts found at: {}", classpathLocation);
            return;
        }
        Collections.sort(scripts);

        try (var conn = dataSource.getConnection()) {
            ensureTrackingTable(conn);  // DDL outside the main transaction (idempotent)

            conn.setAutoCommit(false);
            try {
                var applied = loadApplied(conn, classpathLocation);
                int count   = 0;
                for (var script : scripts) {
                    String sql      = readScript(script);
                    String checksum = sha256(sql);

                    if (applied.containsKey(script.version())) {
                        verifyUnchanged(script, checksum, applied.get(script.version()));
                        continue;
                    }
                    applyStatements(conn, sql);
                    recordApplied(conn, classpathLocation, script, checksum);
                    count++;
                }
                conn.commit();
                if (count > 0) {
                    log.info("Applied {} migration(s) from: {}", count, classpathLocation);
                } else {
                    log.debug("All migrations already applied for: {}", classpathLocation);
                }
            } catch (ShazoException e) {
                safeRollback(conn);
                throw e;
            } catch (SQLException e) {
                safeRollback(conn);
                throw new ShazoException("SQL error during migration at: " + classpathLocation, e);
            }
        } catch (ShazoException e) {
            throw e;
        } catch (SQLException e) {
            throw new ShazoException("Failed to obtain connection for schema migration", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void ensureTrackingTable(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(ADD_CHECKSUM_COLUMN);
        }
    }

    private static Map<Integer, String> loadApplied(Connection conn, String location)
            throws SQLException {
        var applied = new HashMap<Integer, String>();
        try (var ps = conn.prepareStatement(
                "SELECT version, checksum FROM " + MIGRATION_TABLE + " WHERE location = ?")) {
            ps.setString(1, location);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) applied.put(rs.getInt("version"), rs.getString("checksum"));
            }
        }
        return applied;
    }

    private static void verifyUnchanged(MigrationScript script, String current, String recorded)
            throws ShazoException {
        // Legacy rows may have a null checksum; nothing to verify against.
        if (recorded != null && !recorded.equals(current)) {
            throw new ShazoException(
                "Migration V" + script.version() + " (" + script.name() + ") was modified after "
                + "it had been applied (checksum mismatch). Applied migrations are immutable; "
                + "add a new versioned script instead of editing this one.");
        }
    }

    private static String readScript(MigrationScript script) throws ShazoException {
        try (var in = SchemaManager.class.getClassLoader()
                .getResourceAsStream(script.resourcePath())) {
            if (in == null) {
                throw new ShazoException("Script not found on classpath: " + script.resourcePath());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ShazoException("Failed to read script: " + script.resourcePath(), e);
        }
    }

    private static void applyStatements(Connection conn, String sql)
            throws SQLException {
        for (var statement : splitStatements(sql)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute(statement);
            }
        }
    }

    private static void recordApplied(Connection conn, String location,
                                      MigrationScript script, String checksum)
            throws ShazoException, SQLException {
        log.info("Applying V{}  {}", script.version(), script.name());
        try (var ps = conn.prepareStatement(
                "INSERT INTO " + MIGRATION_TABLE
                + " (location, version, script_name, checksum) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, location);
            ps.setInt(2, script.version());
            ps.setString(3, script.name());
            ps.setString(4, checksum);
            ps.executeUpdate();
        }
    }

    /**
     * Splits a script into individual statements on top-level {@code ;},
     * honouring comments, quoted strings/identifiers, and dollar-quoted blocks.
     */
    static List<String> splitStatements(String sql) {
        var statements = new ArrayList<String>();
        var current    = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);

            // -- line comment
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                if (eol < 0) break;
                i = eol + 1;
                continue;
            }
            // /* block comment */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
                continue;
            }
            // '...' string literal (with '' escape)
            if (c == '\'') {
                current.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    current.append(d);
                    i++;
                    if (d == '\'') {
                        if (i < n && sql.charAt(i) == '\'') { current.append('\''); i++; }
                        else break;
                    }
                }
                continue;
            }
            // "..." quoted identifier
            if (c == '"') {
                current.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    current.append(d);
                    i++;
                    if (d == '"') break;
                }
                continue;
            }
            // $$...$$ or $tag$...$tag$ dollar-quoted block (PostgreSQL)
            if (c == '$') {
                int tagEnd = sql.indexOf('$', i + 1);
                if (tagEnd >= 0 && isDollarTag(sql, i, tagEnd)) {
                    String tag   = sql.substring(i, tagEnd + 1);
                    int    close = sql.indexOf(tag, tagEnd + 1);
                    if (close >= 0) {
                        current.append(sql, i, close + tag.length());
                        i = close + tag.length();
                        continue;
                    }
                }
                current.append(c);
                i++;
                continue;
            }
            // statement terminator
            if (c == ';') {
                addIfPresent(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addIfPresent(statements, current);
        return statements;
    }

    private static boolean isDollarTag(String sql, int start, int tagEnd) {
        for (int j = start + 1; j < tagEnd; j++) {
            char ch = sql.charAt(j);
            if (!Character.isLetterOrDigit(ch) && ch != '_') return false;
        }
        return true;
    }

    private static void addIfPresent(List<String> statements, StringBuilder current) {
        var s = current.toString().strip();
        if (!s.isBlank()) statements.add(s);
    }

    private static String sha256(String content) {
        // Normalise line endings so the same logical script hashes identically
        // across platforms (CRLF vs LF).
        var normalized = content.replace("\r\n", "\n");
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                md.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void safeRollback(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) {}
    }

    // ── Classpath scanning ────────────────────────────────────────────────────

    private static List<MigrationScript> findScripts(String location) throws ShazoException {
        var normalized = location.endsWith("/") ? location : location + "/";
        var scripts    = new ArrayList<MigrationScript>();
        try {
            var urls = SchemaManager.class.getClassLoader().getResources(normalized);
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    collectFromDirectory(url, normalized, scripts);
                } else {
                    collectFromJar(url, normalized, scripts);
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new ShazoException("Failed to scan classpath location: " + location, e);
        }
        return scripts;
    }

    private static void collectFromDirectory(URL url, String location,
                                             List<MigrationScript> into)
            throws URISyntaxException, IOException {
        var dir = Path.of(url.toURI());
        if (!Files.isDirectory(dir)) return;
        try (var entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".sql"))
                   .forEach(p -> {
                       var fn = p.getFileName().toString();
                       var m  = SCRIPT_PATTERN.matcher(fn);
                       if (m.matches()) {
                           into.add(new MigrationScript(
                               Integer.parseInt(m.group(1)), m.group(2), location + fn));
                       }
                   });
        }
    }

    private static void collectFromJar(URL url, String location,
                                       List<MigrationScript> into) throws IOException {
        var conn = (JarURLConnection) url.openConnection();
        try (var jar = new JarFile(conn.getJarFile().getName())) {
            jar.entries().asIterator().forEachRemaining(entry -> {
                var name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(location) && name.endsWith(".sql")) {
                    var fn = name.substring(location.length());
                    var m  = SCRIPT_PATTERN.matcher(fn);
                    if (m.matches()) {
                        into.add(new MigrationScript(
                            Integer.parseInt(m.group(1)), m.group(2), name));
                    }
                }
            });
        }
    }

    // ── Value type ────────────────────────────────────────────────────────────

    private record MigrationScript(int version, String name, String resourcePath)
            implements Comparable<MigrationScript> {
        @Override
        public int compareTo(MigrationScript other) {
            return Integer.compare(this.version, other.version);
        }
    }

    private SchemaManager() {}
}
