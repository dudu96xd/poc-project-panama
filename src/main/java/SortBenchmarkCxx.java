import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

@FunctionalInterface
interface ThrowingRunnable {
    void run() throws Throwable;
}

void main(String[] args) throws Throwable {
    final int N = (args.length > 0) ? Integer.parseInt(args[0]) : 5_000_000;
    final long seed = 42L;
    IO.println("Tamanho do array: " + N);

    // dados base idênticos
    int[] base = new Random(seed).ints(N).toArray();

    // ---- Java sort ----
    int[] javaArr = base.clone();
    long tJava = measureMs(() -> Arrays.sort(javaArr));
    IO.println("Java sort      : " + tJava + " ms");

    // ---- Native std::sort (sem upcall) ----
    int[] cxxOut = new int[N];
    long tCxx;

    Linker linker = Linker.nativeLinker();
    MemoryLayout SIZE_T = linker.canonicalLayouts().get("size_t");

    try (Arena arena = Arena.ofConfined()) {
        // Carrega a biblioteca nativa por caminho absoluto no diretório ./native
        Path libPath = resolveNativeLibPath();
        SymbolLookup lib = SymbolLookup.libraryLookup(libPath.toString(), arena);

        // void sort_ints_cxx(int* base, size_t n)
        MethodHandle sortCxx = linker.downcallHandle(
                lib.findOrThrow("sort_ints_cxx"),
                FunctionDescriptor.ofVoid(ADDRESS, SIZE_T)
        );

        // Copia dados para memória nativa
        MemorySegment nativeArr = arena.allocateFrom(JAVA_INT, base);

        // Mede só a chamada de sort (fronteira Java→nativo acontece 1x)
        tCxx = measureMs(() -> {
            // use invoke (não invokeExact) para tolerar width de size_t por plataforma
            sortCxx.invoke(nativeArr, (long) base.length);
        });

        // Copia de volta para checagem
        MemorySegment dst = MemorySegment.ofArray(cxxOut);
        long bytes = (long) N * JAVA_INT.byteSize();
        MemorySegment.copy(nativeArr, 0, dst, 0, bytes);
    }

    IO.println("Native std::sort: " + tCxx + " ms");

    // Sanidade
    if (!isSortedAsc(javaArr)) throw new IllegalStateException("Java não ordenou!");
    if (!isSortedAsc(cxxOut)) throw new IllegalStateException("C++ não ordenou!");
    if (!Arrays.equals(javaArr, cxxOut))
        throw new IllegalStateException("Resultados diferentes!");

    IO.println("OK: resultados iguais e ordenados.");
}

private static Path resolveNativeLibPath() {
    Path base = Paths.get("native");
    String name = isWindows() ? "intsrt.dll" : (isMac() ? "libintsrt.dylib" : "libintsrt.so");
    Path p = base.resolve(name).toAbsolutePath();
    if (!Files.exists(p)) {
        throw new IllegalStateException("Biblioteca nativa não encontrada em: " + p);
    }
    return p;
}

private static long measureMs(ThrowingRunnable r) throws Throwable {
    long t0 = System.nanoTime();
    r.run();
    long t1 = System.nanoTime();
    return (t1 - t0) / 1_000_000L;
}

private static boolean isSortedAsc(int[] a) {
    for (int i = 1; i < a.length; i++) if (a[i - 1] > a[i]) return false;
    return true;
}

static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
}

static boolean isMac() {
    return System.getProperty("os.name").toLowerCase().contains("mac");
}
