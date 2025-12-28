package maple.expectation.global.common.function;

@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Throwable;
}