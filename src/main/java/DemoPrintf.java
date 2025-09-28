import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

void main() throws Throwable {
    Linker linker = Linker.nativeLinker();
    MethodHandle printf = linker.downcallHandle(
            linker.defaultLookup().findOrThrow("printf"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
            Linker.Option.firstVariadicArg(1) // 0=format, 1.. são variádicos
    );
    try (Arena arena = Arena.ofConfined()) {
        int rc = (int) printf.invokeExact(
                arena.allocateFrom("%d + %d = %d\n"), 2, 2, 4
        );
    }
}
