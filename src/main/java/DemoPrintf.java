import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

void main() throws Throwable {
    Linker linker = Linker.nativeLinker();
    MethodHandle printf = linker.downcallHandle(
            linker.defaultLookup().findOrThrow("printf"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
            Linker.Option.firstVariadicArg(1) // 0=format; variádicos começam em 1
    );

    try (Arena arena = Arena.ofConfined()) {
        MemorySegment fmt = arena.allocateFrom("%d + %d = %d\n");
        // ✔ invokeExact com assinatura idêntica: (MemorySegment,int,int,int) -> int
        int rc = (int) printf.invokeExact(fmt, 2, 2, 4);
        IO.println("printf returned: " + rc);
    }
}
