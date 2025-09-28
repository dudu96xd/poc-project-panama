import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public class FileIOBenchmark {

    // Mantém vivo o arena que sustenta os endereços dos símbolos da DLL
    private static final Arena LIB_ARENA = Arena.ofShared();

    static final String JAVA_FILE   = "bench_java.bin";
    static final String NATIVE_FILE = "bench_native.bin";
    static final int    SEED        = 12345;

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Throwable; }

    public static void main(String[] args) throws Throwable {
        long sizeMB  = args.length > 0 ? Long.parseLong(args[0]) : 1024; // 1 GiB
        long blockKB = args.length > 1 ? Long.parseLong(args[1]) : 1024; // 1 MiB
        long total   = sizeMB * 1024L * 1024L;
        int  block   = (int) (blockKB * 1024L);

        System.out.printf("Tamanho: %,d MB  |  Bloco: %,d KB%n", sizeMB, blockKB);

        Path javaPath   = Paths.get(JAVA_FILE).toAbsolutePath();
        Path nativePath = Paths.get(NATIVE_FILE).toAbsolutePath();

        // -------- Java WRITE --------
        deleteIfExists(javaPath);
        long tJavaW = measureMs(() -> writeJava(javaPath, total, block, SEED));
        System.out.printf("Java  write : %d ms%n", tJavaW);

        // -------- Java READ ---------
        long[] sumJava = new long[1];
        long tJavaR = measureMs(() -> sumJava[0] = readJava(javaPath, block));
        System.out.printf("Java  read  : %d ms  | checksum=%,d%n", tJavaR, sumJava[0]);

        // -------- Nativo (carrega símbolos) --------
        NativeIO nio = loadNativeHandles();

        // -------- Native WRITE -------
        deleteIfExists(nativePath);
        long tNatW = measureMs(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment path = a.allocateFrom(nativePath.toString());
                // int write_file_native(const char*, size_t, size_t, unsigned int)
                int rc = (int) nio.write.invoke(path, total, (long) block, SEED);
                if (rc != 0) throw new IOException("write_file_native rc=" + rc);
            }
        });
        System.out.printf("Native write: %d ms%n", tNatW);

        // -------- Native READ --------
        long[] sumNat = new long[1];
        long tNatR = measureMs(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment path = a.allocateFrom(nativePath.toString());
                // long long read_file_native(const char*, size_t)
                long s = (long) nio.read.invoke(path, (long) block);
                if (s < 0) throw new IOException("read_file_native rc=" + s);
                sumNat[0] = s;
            }
        });
        System.out.printf("Native read : %d ms  | checksum=%,d%n", tNatR, sumNat[0]);

        // -------- Sanidade ----------
        if (sumJava[0] != sumNat[0]) {
            System.out.println("ATENÇÃO: checksums diferentes (arquivos têm conteúdo gerado separadamente).");
        } else {
            System.out.println("Checksums iguais (mesmo conteúdo).");
        }

        System.out.println("Concluído.");
    }

    // ---------- Java I/O (FileChannel + Direct ByteBuffer) ----------

    static void writeJava(Path path, long totalBytes, int blockSize, int seed) throws IOException {
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {

            ByteBuffer buf = ByteBuffer.allocateDirect(blockSize);
            int x = seed;
            long remaining = totalBytes;

            while (remaining > 0) {
                int n = (int) Math.min(remaining, blockSize);
                buf.clear();
                for (int i = 0; i < n; i++) {
                    x = 1664525 * x + 1013904223;
                    buf.put((byte) (x >>> 24));
                }
                buf.flip();
                while (buf.hasRemaining()) ch.write(buf);
                remaining -= n;
            }
        }
    }

    static long readJava(Path path, int blockSize) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(blockSize); // heap buffer (tem array())
            long sum = 0;
            while (true) {
                buf.clear();
                int r = ch.read(buf);
                if (r <= 0) break;
                byte[] a = buf.array();
                for (int i = 0; i < r; i++) {
                    sum += (a[i] & 0xFF);
                }
            }
            return sum;
        }
    }

    // ---------- Panama: handles para I/O nativo ----------

    record NativeIO(MethodHandle write, MethodHandle read) {}

    static NativeIO loadNativeHandles() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MemoryLayout SIZE_T = linker.canonicalLayouts().get("size_t");

        Path lib = Paths.get(
                "native",
                isWindows() ? "intsrt.dll" : (isMac() ? "libintsrt.dylib" : "libintsrt.so")
        ).toAbsolutePath();

        if (!Files.exists(lib)) {
            throw new IllegalStateException("Biblioteca nativa não encontrada: " + lib);
        }

        // Carrega a DLL e consulta símbolos *dessa* DLL; o LIB_ARENA mantém vivos os addresses.
        SymbolLookup so = SymbolLookup.libraryLookup(lib.toString(), LIB_ARENA);

        MethodHandle write = linker.downcallHandle(
                so.findOrThrow("write_file_native"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T, SIZE_T, JAVA_INT)
        );

        MethodHandle read = linker.downcallHandle(
                so.findOrThrow("read_file_native"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, SIZE_T)
        );

        return new NativeIO(write, read);
    }

    // ---------- util ----------

    static long measureMs(ThrowingRunnable r) throws Throwable {
        long t0 = System.nanoTime();
        r.run();
        long t1 = System.nanoTime();
        return (t1 - t0) / 1_000_000L;
    }

    static void deleteIfExists(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    static boolean isMac()     { return System.getProperty("os.name").toLowerCase().contains("mac"); }
}
