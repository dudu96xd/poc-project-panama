import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import static java.lang.foreign.ValueLayout.*;

void main() throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup stdlib = linker.defaultLookup();

    // 1) configurar captura de errno
    Linker.Option ccs = Linker.Option.captureCallState("errno");
    StructLayout capturedStateLayout = Linker.Option.captureStateLayout();
    VarHandle ERRNO = capturedStateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    // 2) handles nativos
    MethodHandle fopen = linker.downcallHandle(
            stdlib.findOrThrow("fopen"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            ccs
    );
    MethodHandle strerror = linker.downcallHandle(
            stdlib.findOrThrow("strerror"),
            FunctionDescriptor.of(ADDRESS, JAVA_INT)
    );

    try (Arena arena = Arena.ofConfined()) {
        // 3) alocar o "capture state" e os argumentos C
        MemorySegment capturedState = arena.allocate(capturedStateLayout);
        MemorySegment path = arena.allocateFrom("/definitely/not/there");
        MemorySegment mode = arena.allocateFrom("r");

        // 4) invocar passando capturedState como 1º argumento
        MemorySegment file = (MemorySegment) fopen.invokeExact(capturedState, path, mode);

        if (file.equals(MemorySegment.NULL)) {
            // 5) ler errno do capturedState
            int errno = (int) ERRNO.get(capturedState, 0L);

            // 6) converter errno em mensagem
            MemorySegment msgSeg = (MemorySegment) strerror.invokeExact(errno);
            String msg = msgSeg.reinterpret(Long.MAX_VALUE).getString(0);
            IO.println("fopen falhou: errno=" + errno + " msg=" + msg);
        }
    }
}
