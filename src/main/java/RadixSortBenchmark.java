import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

private static final Arena LIB_ARENA = Arena.ofShared();

@FunctionalInterface
interface ThrowingRunnable {
    void run() throws Throwable;
}

void main(String[] args) throws Throwable {
    final int N = (args.length > 0) ? Integer.parseInt(args[0]) : 300_000_000; // 30M (~114 MiB)
    final long seed = 42L;

    IO.println("N = " + N);

    int[] base = new Random(seed).ints(N).toArray();

    // Java parallelSort
    int[] j = base.clone();
    long tJava = measureMs(() -> Arrays.parallelSort(j));
    IO.println("Java parallelSort : " + tJava + " ms");

    // Nativo (radix sort)
    int[] out = base.clone();
    long tRadix;

    Linker linker = Linker.nativeLinker();
    MemoryLayout SIZE_T = linker.canonicalLayouts().get("size_t");

    Path lib = Paths.get("native",
            isWindows() ? "intsrt.dll" : (isMac() ? "libintsrt.dylib" : "libintsrt.so")
    ).toAbsolutePath();

    if (!Files.exists(lib)) {
        throw new IllegalStateException("Biblioteca nativa não encontrada: " + lib);
    }

    SymbolLookup so = SymbolLookup.libraryLookup(lib.toString(), LIB_ARENA);

    MethodHandle radix = linker.downcallHandle(
            so.findOrThrow("radix_sort_i32"),
            FunctionDescriptor.ofVoid(ADDRESS, SIZE_T)
    );

    try (Arena a = Arena.ofConfined()) {
        MemorySegment seg = a.allocateFrom(JAVA_INT, out);
        tRadix = measureMs(() -> radix.invoke(seg, (long) N));
        // copia de volta (não é estritamente necessário — já está in-place —
        // mas garante que 'out' reflita o conteúdo nativo caso você leia do array Java)
        long bytes = (long) N * JAVA_INT.byteSize();
        MemorySegment.copy(seg, 0, MemorySegment.ofArray(out), 0, bytes);
    }

    IO.println("Native radix_sort  : " + tRadix + " ms");

    // Sanidade
    if (!Arrays.equals(j, out)) {
        throw new IllegalStateException("Resultados diferentes!");
    }
    IO.println("OK: resultados iguais.");
}

static long measureMs(ThrowingRunnable r) throws Throwable {
    long t0 = System.nanoTime();
    r.run();
    long t1 = System.nanoTime();
    return (t1 - t0) / 1_000_000L;
}

static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
}

static boolean isMac() {
    return System.getProperty("os.name").toLowerCase().contains("mac");
}
