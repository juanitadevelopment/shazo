# Shazo

A small object-persistence abstraction for Java. One repository interface, many
storage backends — JDBC, the file system, external shell commands, and remote
HTTP — behind a single typed contract.

Shazo separates **what** to persist (your domain object) from **how** it maps to
storage (a `Describer` that emits typed commands), so the same domain code can be
backed by a relational database in production and a directory of files in a test,
without changing a line of business logic.

> **Status:** early release (`0.1.3`). The core API is stable and fully tested,
> but minor breaking changes are still possible before `1.0.0`.

## Requirements

- **Java 25+** (uses records, pattern-matching `switch`, and virtual threads)
- Build: Gradle (wrapper included)

## The core contract

Every backend implements the same five-operation interface:

```java
public interface Repository<T> {
    boolean      contains(T query)         throws ShazoException;
    void         store(T entity)           throws ShazoException;
    void         delete(T entity)          throws ShazoException;
    Optional<T>  retrieve(T query)         throws ShazoException;
    T            retrieveRequired(T query) throws ShazoException, NotFoundException;
    List<T>      catalog(T query)          throws ShazoException;
}
```

The same domain object doubles as the query: pass a sparsely-populated instance
(e.g. only the id) to look something up.

## How it fits together

```
Repository<T>            ← the contract your code depends on
   ▲
AbstractRepository<T,C>   ← template method; turns a Describer into storage calls
   ▲
JdbcRepository / FileRepository / ShellRepository / …

Describer<T, C extends Command>
   ├─ produces typed commands (SqlCommand / FileCommand / ShellCommand)
   ├─ Infuser<T>   : RawResult → one entity
   ├─ Cataloger<T> : RawResult → List<T>
   └─ Verifier     : does a result count as "found"?
```

A `Describer` is parameterized by its command type `C`, so a
`Describer<Memo, SqlCommand>` can only be paired with a JDBC repository and a
`Describer<Memo, FileCommand>` only with a file repository — mismatches are
**compile-time** errors, not runtime surprises.

## Quick start (JDBC)

```java
record Person(String id, String name, int age) {}

// 1. A data source (embedded H2 here; any javax.sql.DataSource works)
DataSource ds = EmbeddedDataSource.inMemory("demo");

// 2. (optional) run versioned migrations from the classpath
SchemaManager.apply(ds, "db/migration/");   // V001__*.sql, V002__*.sql, ...

// 3. Describe how Person maps to SQL
Describer<Person, SqlCommand> describer = Describer.<Person, SqlCommand>builder()
    .contains(p -> List.of(SqlCommand.of("SELECT 1 FROM person WHERE id = ?", p.id())))
    .store(p    -> List.of(SqlCommand.of(
        "MERGE INTO person (id, name, age) KEY (id) VALUES (?, ?, ?)",
        p.id(), p.name(), p.age())))
    .delete(p   -> List.of(SqlCommand.of("DELETE FROM person WHERE id = ?", p.id())))
    .retrieve(p -> List.of(SqlCommand.of(
        "SELECT id, name, age FROM person WHERE id = ?", p.id())))
    .catalog(p  -> List.of(SqlCommand.of("SELECT id, name, age FROM person ORDER BY name")))
    .infuser(r -> r.first().map(row -> new Person(
        (String) row.get("id"), (String) row.get("name"),
        ((Number) row.get("age")).intValue())).orElseThrow())
    .cataloger(r -> r.rows().stream().map(row -> new Person(
        (String) row.get("id"), (String) row.get("name"),
        ((Number) row.get("age")).intValue())).toList())
    .build();

// 4. Use it
var repo = new JdbcRepository<>(ds, describer);
repo.store(new Person("1", "Alice", 30));
Optional<Person> alice = repo.retrieve(new Person("1", null, 0));
List<Person>     all   = repo.catalog(new Person(null, null, 0));
```

Column lookups in the infuser are **case-insensitive**, so `row.get("id")` works
whether the driver reports `id`, `ID`, or `Id`.

### Transactions

```java
repo.transact(r -> {
    r.store(alice);
    r.store(bob);
    return null;        // commits on normal return, rolls back on exception
});
```

### Storing several types at once

`Repository<T>` is precise but makes you name a describer per call. When you'd
rather "store anything", register describers by type with `Repositories` and
dispatch on the object's runtime class — including a varargs `store(...)`:

```java
var repos = Repositories.builder()
    .register(Order.class,   orderDescriber)
    .register(Booking.class, bookingDescriber)
    .build();

new Transactor(dataSource).execute(uow -> {
    repos.in(uow).store(order, booking);   // both types, one transaction
    return null;
});

Optional<Order> o = repos.in(uow).retrieve(Order.class, new Order(id, null));
```

## Backends

| Backend | Class | Command type | Notes |
|---|---|---|---|
| Relational DB | `JdbcRepository<T>` | `SqlCommand` | any `DataSource`; `transact(...)` |
| File system | `FileRepository<T>` | `FileCommand` | atomic writes, path-traversal guarded, thread-safe |
| Shell command | `ShellRepository<T>` | `ShellCommand` | `ProcessBuilder`; per-process timeout |
| Remote HTTP | `HttpRepositoryAdapter<T>` / `HttpRepositoryServlet<T>` | — | binary protocol; pluggable `Codec` |
| In-memory cache | `CacheRepository<T>` | — | TTL decorator over any repository |
| Fan-out | `MixedRepository<T>` | — | writes to many, reads from a primary |
| Async | `AsyncRepository<T>` | — | `CompletableFuture` wrapper on virtual threads |

### File backend

```java
var repo = new FileRepository<>(Path.of("./data"), new FileMemoDescriber());
```

Writes are staged in a temp file and atomically moved into place, all operations
are guarded by a read/write lock, and file names that try to escape the base
directory (`../…`, absolute paths) are rejected.

### Decorators

```java
// TTL cache keyed by id
var cached = new CacheRepository<>(jdbcRepo, Duration.ofMinutes(10), Person::id);

// mirror writes to a replica; read from the primary
var mixed  = MixedRepository.of(primaryRepo, replicaRepo);

// non-blocking access (closes its executor)
try (var async = new AsyncRepository<>(jdbcRepo)) {
    async.retrieve(new Person("1", null, 0))
         .thenAccept(opt -> opt.ifPresent(System.out::println));
}
```

### Remote HTTP

The client and server share a `Codec`. The default Java-serialization codec is
guarded by a deserialization **allowlist** — you must declare the permitted
type(s), which blocks gadget-chain payloads over the wire:

```java
Codec<Person> codec = Codec.java(Person.class);

// server side (any servlet container)
var servlet = new HttpRepositoryServlet<>(backingRepo, codec);

// client side
try (var repo = new HttpRepositoryAdapter<>(URI.create("http://host/api/persons"), codec)) {
    repo.store(new Person("1", "Alice", 30));
}
```

## Schema migrations

`SchemaManager` applies `V<n>__<description>.sql` scripts from a classpath
location in version order, inside a transaction, tracking what has run in a
`_shazo_schema_migrations` table. Applied scripts are checksummed: editing one
after it has run is detected and refused (add a new version instead). The
statement splitter understands SQL comments, quoted strings/identifiers, and
PostgreSQL dollar-quoted blocks.

```java
SchemaManager.apply(dataSource, "net/teppan/myapp/schema/");
```

`EmbeddedDataSource` provides H2 data sources (file, in-memory, server) with
PostgreSQL-compatibility options preset.

## Build

```sh
./gradlew test     # run the test suite
./gradlew jar      # build the library jar
./gradlew javadoc  # generate API docs
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE)
for attribution.
