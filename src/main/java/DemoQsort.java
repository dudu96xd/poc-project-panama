import java.lang.foreign.*;
import java.lang.invoke.*;
import static java.lang.foreign.ValueLayout.*;

public class DemoQsort {
  // comparator C: int (*cmp)(const void*, const void*)
  static int cmp(MemorySegment a, MemorySegment b) {
      a = a.reinterpret(JAVA_INT.byteSize());
      b = b.reinterpret(JAVA_INT.byteSize());
      int av = a.get(JAVA_INT, 0);
      int bv = b.get(JAVA_INT, 0);
      return Integer.compare(av, bv);
  }


    static void main() throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup stdlib = linker.defaultLookup();

    // size_t portátil via canonicalLayouts()
    MemoryLayout SIZE_T = linker.canonicalLayouts().get("size_t");

    MethodHandle qsort = linker.downcallHandle(
        stdlib.findOrThrow("qsort"),
        FunctionDescriptor.ofVoid(
            ADDRESS,            // base
            SIZE_T,             // nmemb
            SIZE_T,             // size
            ADDRESS             // compar
        )
    );

    try (Arena arena = Arena.ofConfined()) {
      // aloca e inicializa o array nativo de int
      MemorySegment arr = arena.allocateFrom(JAVA_INT, 5, 2, 9, 1, 3);

      // upcall stub para passar como function pointer
      MethodHandle mhCmp = MethodHandles.lookup().findStatic(
          DemoQsort.class, "cmp",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)
      );
      MemorySegment cmpPtr = linker.upcallStub(
          mhCmp,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
          arena
      );

      long n = 5;
      long elem = JAVA_INT.byteSize();
      qsort.invokeExact(arr, n, elem, cmpPtr);

      // lê de volta (ordenado)
      for (int i = 0; i < n; i++) {
        System.out.print(arr.getAtIndex(JAVA_INT, i) + " ");
      }
      System.out.println();
    }
  }
}
