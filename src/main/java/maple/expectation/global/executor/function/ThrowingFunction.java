package maple.expectation.global.executor.function;

@FunctionalInterface
public interface ThrowingFunction<T, R> {
    R apply(T t) throws Throwable;
}