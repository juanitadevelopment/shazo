package net.teppan.shazo.jdbc;

import net.teppan.shazo.Describer;
import net.teppan.shazo.ShazoException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A type-dispatching registry of {@link Describer}s, so heterogeneous objects can
 * be stored through one call without naming a repository each time.
 *
 * <p>The typed {@link net.teppan.shazo.Repository Repository&lt;T&gt;} is precise
 * but makes a caller name the describer for every operation. This registry
 * restores the convenience of a single "store anything" facade — the role the
 * original framework's {@code DescriberFinder} played — on top of that typed
 * core: register a describer per type once, then dispatch on the object's runtime
 * class.
 *
 * <pre>{@code
 * var repos = Repositories.builder()
 *     .register(Order.class,   orderDescriber)
 *     .register(Booking.class, bookingDescriber)
 *     .build();
 *
 * new Transactor(dataSource).execute(uow -> {
 *     repos.in(uow).store(order, booking);   // both types, one transaction
 *     return null;
 * });
 * }</pre>
 *
 * <p>Dispatch is by exact runtime class ({@code entity.getClass()}); a type with
 * no registered describer raises {@link IllegalArgumentException}. The registry
 * itself holds no connection — bind it to a {@link UnitOfWork} with
 * {@link #in(UnitOfWork)} to run operations within that transaction.
 */
public final class Repositories {

    private final Map<Class<?>, Describer<?, SqlCommand>> describers;

    private Repositories(Map<Class<?>, Describer<?, SqlCommand>> describers) {
        this.describers = Map.copyOf(describers);
    }

    /**
     * Returns a new builder.
     *
     * @return a builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the describer registered for {@code type}.
     *
     * @param type the domain type; never {@code null}
     * @param <T>  the domain type
     * @return the registered describer
     * @throws IllegalArgumentException if no describer is registered for the type
     */
    @SuppressWarnings("unchecked")
    public <T> Describer<T, SqlCommand> describerFor(Class<T> type) {
        var d = describers.get(Objects.requireNonNull(type, "type"));
        if (d == null) {
            throw new IllegalArgumentException("No describer registered for " + type.getName());
        }
        return (Describer<T, SqlCommand>) d;
    }

    /**
     * Returns whether a describer is registered for {@code type}.
     *
     * @param type the domain type
     * @return {@code true} if the type can be dispatched
     */
    public boolean handles(Class<?> type) {
        return describers.containsKey(type);
    }

    /**
     * Returns the registered types.
     *
     * @return an immutable set of types
     */
    public Set<Class<?>> types() {
        return describers.keySet();
    }

    /**
     * Binds this registry to a unit of work, yielding heterogeneous operations
     * that all share that transaction.
     *
     * @param unitOfWork the transaction to run within; never {@code null}
     * @return a transaction-bound view
     */
    public InTransaction in(UnitOfWork unitOfWork) {
        return new InTransaction(this, Objects.requireNonNull(unitOfWork, "unitOfWork"));
    }

    /**
     * Heterogeneous repository operations bound to one {@link UnitOfWork}.
     *
     * <p>{@link #store(Object...)} and {@link #delete(Object...)} dispatch each
     * argument by its runtime class, so several types commit together within the
     * bound transaction. Reads take the type explicitly, since it determines the
     * result type.
     */
    public static final class InTransaction {

        private final Repositories registry;
        private final UnitOfWork uow;

        private InTransaction(Repositories registry, UnitOfWork uow) {
            this.registry = registry;
            this.uow = uow;
        }

        /**
         * Stores each entity, dispatching by its runtime class. All run within the
         * bound transaction; an empty call is a no-op.
         *
         * @param entities the entities to store; none {@code null}
         * @throws net.teppan.shazo.ShazoException if a store fails
         * @throws IllegalArgumentException        if an entity's type is not registered
         */
        public void store(Object... entities) throws ShazoException {
            for (Object entity : entities) {
                applyStore(entity);
            }
        }

        /**
         * Deletes each entity, dispatching by its runtime class.
         *
         * @param entities the entities to delete; none {@code null}
         * @throws net.teppan.shazo.ShazoException if a delete fails
         * @throws IllegalArgumentException        if an entity's type is not registered
         */
        public void delete(Object... entities) throws ShazoException {
            for (Object entity : entities) {
                applyDelete(entity);
            }
        }

        /**
         * Returns whether the given entity exists, dispatching by its runtime class.
         *
         * @param entity the query entity; never {@code null}
         * @return {@code true} if a matching record exists
         * @throws net.teppan.shazo.ShazoException if the check fails
         */
        public boolean contains(Object entity) throws ShazoException {
            return applyContains(entity);
        }

        /**
         * Retrieves an entity of the given type.
         *
         * @param type  the entity type; never {@code null}
         * @param query a query entity carrying the key; never {@code null}
         * @param <T>   the entity type
         * @return the entity if found
         * @throws net.teppan.shazo.ShazoException if the read fails
         */
        public <T> Optional<T> retrieve(Class<T> type, T query) throws ShazoException {
            return uow.repository(registry.describerFor(type)).retrieve(query);
        }

        /**
         * Lists entities of the given type.
         *
         * @param type  the entity type; never {@code null}
         * @param query a query entity; never {@code null}
         * @param <T>   the entity type
         * @return the matching entities
         * @throws net.teppan.shazo.ShazoException if the read fails
         */
        public <T> List<T> catalog(Class<T> type, T query) throws ShazoException {
            return uow.repository(registry.describerFor(type)).catalog(query);
        }

        @SuppressWarnings("unchecked")
        private <T> void applyStore(Object entity) throws ShazoException {
            Class<T> type = (Class<T>) Objects.requireNonNull(entity, "entity").getClass();
            uow.repository(registry.describerFor(type)).store(type.cast(entity));
        }

        @SuppressWarnings("unchecked")
        private <T> void applyDelete(Object entity) throws ShazoException {
            Class<T> type = (Class<T>) Objects.requireNonNull(entity, "entity").getClass();
            uow.repository(registry.describerFor(type)).delete(type.cast(entity));
        }

        @SuppressWarnings("unchecked")
        private <T> boolean applyContains(Object entity) throws ShazoException {
            Class<T> type = (Class<T>) Objects.requireNonNull(entity, "entity").getClass();
            return uow.repository(registry.describerFor(type)).contains(type.cast(entity));
        }
    }

    /**
     * Builder for {@link Repositories}.
     */
    public static final class Builder {

        private final Map<Class<?>, Describer<?, SqlCommand>> describers = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers the describer to use for objects of {@code type}.
         *
         * @param type      the domain type; never {@code null}
         * @param describer the describer for that type; never {@code null}
         * @param <T>       the domain type
         * @return this builder
         */
        public <T> Builder register(Class<T> type, Describer<T, SqlCommand> describer) {
            describers.put(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(describer, "describer"));
            return this;
        }

        /**
         * Builds the registry.
         *
         * @return a new registry
         */
        public Repositories build() {
            return new Repositories(describers);
        }
    }
}
