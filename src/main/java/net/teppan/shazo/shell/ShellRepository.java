package net.teppan.shazo.shell;

import net.teppan.shazo.AbstractRepository;
import net.teppan.shazo.Describer;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.ShazoException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * A {@link net.teppan.shazo.Repository} implementation that executes
 * {@link ShellCommand} directives as external processes via
 * {@link ProcessBuilder}.
 *
 * <p>Each non-blank line of stdout is converted to a {@code Map<String, Object>}
 * row by a {@link LineParser}.  The default parser maps each line to the single
 * column {@code "line"}.  Use {@link LineParser#tabDelimited(String...)} or
 * {@link LineParser#delimited(String, String...)} for structured output.
 *
 * <p>stdout and stderr are drained concurrently on virtual threads, eliminating
 * the deadlock risk that arises when a process fills its stdout buffer while the
 * caller is blocking on stderr (or vice versa).
 *
 * <p>Each process is bounded by a timeout (default {@link #DEFAULT_TIMEOUT}); a
 * process still running when it elapses is forcibly terminated and the operation
 * fails with {@link ShazoException}.
 *
 * <p>A non-zero exit code causes {@link #execute} to throw
 * {@link ShazoException} with the exit code and stderr content included in the
 * message.  Because this repository is typed
 * {@code AbstractRepository<T, ShellCommand>}, only {@link ShellCommand}
 * directives can reach it; an empty command list is a silent no-op.
 *
 * <h2>Basic example</h2>
 * <pre>{@code
 * Describer<LogEntry, ShellCommand> describer = Describer.<LogEntry, ShellCommand>builder()
 *     .contains(e -> List.of())
 *     .store  (e -> List.of())
 *     .delete (e -> List.of())
 *     .retrieve(e -> List.of(ShellCommand.of("grep", e.id(), "/var/log/app.log")))
 *     .catalog (e -> List.of(ShellCommand.of("tail", "-n", "100", "/var/log/app.log")))
 *     .infuser (result -> result.firstValue("line", Producer.asString())
 *         .map(LogEntry::parse).orElseThrow())
 *     .cataloger(result -> result.rows().stream()
 *         .map(row -> LogEntry.parse((String) row.get("line"))).toList())
 *     .build();
 *
 * var repo = new ShellRepository<>(describer);
 * }</pre>
 *
 * <h2>Tab-delimited output</h2>
 * <pre>{@code
 * var repo = new ShellRepository<>(describer,
 *     LineParser.tabDelimited("id", "name", "score"));
 * // Process output "42\tAlice\t99.5" → {"id":"42","name":"Alice","score":"99.5"}
 * }</pre>
 *
 * @param <T> the domain type managed by this repository
 * @see LineParser
 */
public final class ShellRepository<T> extends AbstractRepository<T, ShellCommand> {

    private static final Logger log = LoggerFactory.getLogger(ShellRepository.class);

    /** Default per-process timeout when none is specified. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final LineParser lineParser;
    private final File       workingDirectory;
    private final Duration   timeout;

    /**
     * Constructs a {@code ShellRepository} using the default
     * {@link LineParser#byLine()} parser, the JVM's current working
     * directory, and the {@link #DEFAULT_TIMEOUT}.
     *
     * @param describer the describer for domain type {@code T}; never {@code null}
     */
    public ShellRepository(Describer<T, ShellCommand> describer) {
        this(describer, LineParser.byLine(), null, DEFAULT_TIMEOUT);
    }

    /**
     * Constructs a {@code ShellRepository} with a custom line parser, the JVM's
     * current working directory, and the {@link #DEFAULT_TIMEOUT}.
     *
     * @param describer  the describer for domain type {@code T}; never {@code null}
     * @param lineParser the parser applied to each non-blank stdout line;
     *                   never {@code null}
     */
    public ShellRepository(Describer<T, ShellCommand> describer, LineParser lineParser) {
        this(describer, lineParser, null, DEFAULT_TIMEOUT);
    }

    /**
     * Constructs a {@code ShellRepository} with a custom line parser and
     * working directory, using the {@link #DEFAULT_TIMEOUT}.
     *
     * @param describer        the describer for domain type {@code T}; never {@code null}
     * @param lineParser       the parser for stdout lines; never {@code null}
     * @param workingDirectory the working directory for launched processes;
     *                         {@code null} inherits the JVM's working directory
     */
    public ShellRepository(Describer<T, ShellCommand> describer, LineParser lineParser,
                           File workingDirectory) {
        this(describer, lineParser, workingDirectory, DEFAULT_TIMEOUT);
    }

    /**
     * Constructs a {@code ShellRepository} with full control over the line
     * parser, working directory, and per-process timeout.
     *
     * @param describer        the describer for domain type {@code T}; never {@code null}
     * @param lineParser       the parser for stdout lines; never {@code null}
     * @param workingDirectory the working directory for launched processes;
     *                         {@code null} inherits the JVM's working directory
     * @param timeout          the maximum time to wait for each process; a
     *                         process still running when it elapses is killed and
     *                         the operation fails; must be positive
     */
    public ShellRepository(Describer<T, ShellCommand> describer, LineParser lineParser,
                           File workingDirectory, Duration timeout) {
        super(describer);
        this.lineParser       = Objects.requireNonNull(lineParser, "lineParser");
        this.workingDirectory = workingDirectory; // nullable
        this.timeout          = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
    }

    /**
     * Executes the given commands by launching one external process per
     * {@link ShellCommand}. An empty list is a silent no-op.
     *
     * @param commands the commands to execute; never {@code null}
     * @return the aggregated stdout rows from all shell commands
     * @throws ShazoException if any process exits with a non-zero code or fails
     *                        to start
     */
    @Override
    protected RawResult execute(List<ShellCommand> commands) throws ShazoException {
        var rows = new ArrayList<Map<String, Object>>();
        for (var shell : commands) {
            log.debug("Shell: {} {}", shell.executable(),
                      String.join(" ", shell.arguments()));
            rows.addAll(runShell(shell));
        }
        return RawResult.of(rows);
    }

    private List<Map<String, Object>> runShell(ShellCommand shell) throws ShazoException {
        var args = new ArrayList<String>(shell.arguments().size() + 1);
        args.add(shell.executable());
        args.addAll(shell.arguments());

        var builder = new ProcessBuilder(args);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ShazoException("Failed to start process: " + args, e);
        }

        // Both streams are drained on dedicated virtual threads. This avoids the
        // classic deadlock (a process filling one pipe buffer while we block on
        // the other) AND lets the current thread enforce the timeout with
        // waitFor(): if we read either stream inline, a process that never
        // closes it would block us before we could time out. Bare virtual
        // threads (not an Executor) mean nothing to leak per invocation.
        var stdoutHolder = new AtomicReference<List<String>>(List.of());
        var stderrHolder = new AtomicReference<String>("");
        var stdoutError  = new AtomicReference<IOException>();
        var stderrError  = new AtomicReference<IOException>();

        var stdoutThread = Thread.ofVirtual().name("shazo-shell-stdout").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                stdoutHolder.set(reader.lines().toList());
            } catch (IOException e) {
                stdoutError.set(e);
            }
        });
        var stderrThread = Thread.ofVirtual().name("shazo-shell-stderr").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                stderrHolder.set(reader.lines().collect(Collectors.joining("\n")));
            } catch (IOException e) {
                stderrError.set(e);
            }
        });

        int exitCode;
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                stdoutThread.join();
                stderrThread.join();
                throw new ShazoException(
                    "Process timed out after " + timeout + ": " + args);
            }
            exitCode = process.exitValue();
            stdoutThread.join();
            stderrThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ShazoException("Process interrupted: " + args, e);
        }

        if (stdoutError.get() != null) {
            throw new ShazoException("Failed to read stdout from: " + args, stdoutError.get());
        }
        if (stderrError.get() != null) {
            throw new ShazoException("Failed to read stderr from: " + args, stderrError.get());
        }
        List<String> stdoutLines = stdoutHolder.get();
        String       stderr      = stderrHolder.get();

        if (exitCode != 0) {
            var msg = "Process exited with code " + exitCode + ": " + args;
            if (!stderr.isBlank()) {
                msg += "\nstderr: " + stderr;
            }
            throw new ShazoException(msg);
        }

        return stdoutLines.stream()
                          .filter(line -> !line.isBlank())
                          .map(lineParser::parse)
                          .toList();
    }
}
