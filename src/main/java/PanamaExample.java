import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

void main() throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
        // aloca uma C string (null-terminated)
        MemorySegment cString = arena.allocateFrom("Hello World! Panama style");

        // resolve "puts" na libc padrão do SO e cria o handle
        Linker linker = Linker.nativeLinker();
        SymbolLookup stdlib = linker.defaultLookup();
        MemorySegment putsAddr = stdlib.find("puts").orElseThrow();
        MethodHandle puts = linker.downcallHandle(putsAddr,
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // chama puts(cString)
        int rc = (int) puts.invoke(cString);
        IO.println("puts returned: " + rc);
    }
}
