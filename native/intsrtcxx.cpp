// native/intsrtcxx.cpp — sort + I/O (Windows otimizado com OVERLAPPED)
//
// Build (Windows, MSVC, x64, runtime estático):
//   cl /O2 /EHsc /LD /MT /std:c++20 intsrtcxx.cpp /Fe:intsrt.dll
//
// Build (Linux):
//   g++ -O3 -fPIC -shared -std=c++20 -o libintsrt.so intsrtcxx.cpp
//
// Build (macOS):
//   clang++ -O3 -fPIC -shared -std=c++20 -o libintsrt.dylib intsrtcxx.cpp

#include <algorithm>
#include <cstddef>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <cstdint>
#include <cstring>

#ifdef _WIN32
  #define DLL_EXPORT __declspec(dllexport)
  #define WIN32_LEAN_AND_MEAN
  #include <windows.h>
#else
  #define DLL_EXPORT
  #include <unistd.h>   // ftruncate, fileno
  #include <fcntl.h>    // posix_fadvise (Linux)
#endif

// -------------------- helpers comuns --------------------

static inline void fill_block(unsigned char* buf, size_t n, unsigned int* x) {
    // LCG determinístico (compatível com o lado Java)
    size_t i = 0;
    for (; i + 8 <= n; i += 8) {
        *x = 1664525u * (*x) + 1013904223u; buf[i + 0] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 1] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 2] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 3] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 4] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 5] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 6] = (unsigned char)((*x) >> 24);
        *x = 1664525u * (*x) + 1013904223u; buf[i + 7] = (unsigned char)((*x) >> 24);
    }
    for (; i < n; ++i) {
        *x = 1664525u * (*x) + 1013904223u;
        buf[i] = (unsigned char)((*x) >> 24);
    }
}

// -------------------- sort (igual ao anterior) --------------------

extern "C" {

DLL_EXPORT void sort_ints_cxx(int* base, std::size_t n) {
    std::sort(base, base + n);
}

#if __has_include(<execution>)
  #include <execution>
DLL_EXPORT void sort_ints_cxx_par(int* base, std::size_t n) {
    std::sort(std::execution::par_unseq, base, base + n);
}
#endif

} // extern "C"

// -------------------- I/O: implementações específicas --------------------

#ifdef _WIN32

// UTF-8 -> UTF-16 (Wide) para CreateFileW
static wchar_t* wide_from_utf8(const char* s) {
    int len = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (len <= 0) return NULL;
    wchar_t* w = (wchar_t*)malloc(len * sizeof(wchar_t));
    if (!w) return NULL;
    if (!MultiByteToWideChar(CP_UTF8, 0, s, -1, w, len)) { free(w); return NULL; }
    return w;
}

extern "C" {

// -------------------- WRITE (Win32 + OVERLAPPED + duplo buffer) --------------------
DLL_EXPORT int write_file_native(const char* path, size_t total_bytes, size_t block_size, unsigned int seed) {
    int rc_err = 0;

    wchar_t* wpath = wide_from_utf8(path);
    if (!wpath) return EINVAL;

    HANDLE h = CreateFileW(
        wpath,
        GENERIC_WRITE,
        FILE_SHARE_READ,
        NULL,
        CREATE_ALWAYS,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN | FILE_FLAG_OVERLAPPED,
        NULL
    );
    free(wpath);

    if (h == INVALID_HANDLE_VALUE) {
        return (int) GetLastError();
    }

    // aloca dois buffers (pipeline)
    unsigned char* buf[2] = { nullptr, nullptr };
    buf[0] = (unsigned char*)malloc(block_size);
    buf[1] = (unsigned char*)malloc(block_size);
    if (!buf[0] || !buf[1]) {
        if (buf[0]) free(buf[0]);
        if (buf[1]) free(buf[1]);
        CloseHandle(h);
        return ENOMEM;
    }

    OVERLAPPED ov[2];
    ZeroMemory(ov, sizeof(ov));
    DWORD wrote[2] = {0, 0};
    size_t chunk[2] = {0, 0};

    unsigned int x = seed;
    size_t remaining = total_bytes;
    unsigned long long offset = 0ULL;
    int cur = 0;   // índice a enviar agora
    int prev = 1;  // índice enviado anteriormente

    // 1) preparar e enviar o primeiro bloco
    if (remaining > 0) {
        chunk[cur] = (remaining < block_size) ? remaining : block_size;
        fill_block(buf[cur], chunk[cur], &x);

        ov[cur].Offset     = (DWORD)(offset & 0xFFFFFFFFULL);
        ov[cur].OffsetHigh = (DWORD)(offset >> 32);

        wrote[cur] = 0;
        DWORD req = (DWORD)chunk[cur];
        BOOL ok = WriteFile(h, buf[cur], req, &wrote[cur], &ov[cur]);
        if (!ok) {
            DWORD e = GetLastError();
            if (e != ERROR_IO_PENDING) { rc_err = (int)e; goto cleanup; }
        }
        remaining -= chunk[cur];
        offset    += chunk[cur];
    }

    // 2) pipeline: enquanto a anterior escreve, geramos a próxima
    while (remaining > 0) {
        prev = cur;
        cur  = 1 - cur;

        chunk[cur] = (remaining < block_size) ? remaining : block_size;
        fill_block(buf[cur], chunk[cur], &x);

        ZeroMemory(&ov[cur], sizeof(OVERLAPPED));
        ov[cur].Offset     = (DWORD)(offset & 0xFFFFFFFFULL);
        ov[cur].OffsetHigh = (DWORD)(offset >> 32);

        wrote[cur] = 0;
        DWORD req = (DWORD)chunk[cur];
        BOOL ok = WriteFile(h, buf[cur], req, &wrote[cur], &ov[cur]);
        if (!ok) {
            DWORD e = GetLastError();
            if (e != ERROR_IO_PENDING) { rc_err = (int)e; goto cleanup; }
        }

        // aguarda a conclusão do bloco anterior (prev) enquanto o atual corre
        {
            DWORD wr = 0;
            BOOL done = GetOverlappedResult(h, &ov[prev], &wr, TRUE);
            if (!done || wr != (DWORD)chunk[prev]) { rc_err = done ? EIO : (int)GetLastError(); goto cleanup; }
        }

        remaining -= chunk[cur];
        offset    += chunk[cur];
    }

    // 3) espera a última pendência (cur)
    {
        DWORD wr = 0;
        BOOL done = GetOverlappedResult(h, &ov[cur], &wr, TRUE);
        if (!done || wr != (DWORD)chunk[cur]) { rc_err = done ? EIO : (int)GetLastError(); goto cleanup; }
    }

cleanup:
    if (buf[0]) free(buf[0]);
    if (buf[1]) free(buf[1]);
    if (!CloseHandle(h) && rc_err == 0) rc_err = (int)GetLastError();
    return rc_err;
}

// -------------------- READ (Win32 síncrono, sequencial) --------------------
DLL_EXPORT long long read_file_native(const char* path, size_t block_size) {
    wchar_t* wpath = wide_from_utf8(path);
    if (!wpath) return -1;

    HANDLE h = CreateFileW(
        wpath,
        GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        NULL
    );
    free(wpath);

    if (h == INVALID_HANDLE_VALUE) {
        return -1;
    }

    unsigned char* buf = (unsigned char*)malloc(block_size);
    if (!buf) { CloseHandle(h); return -1; }

    unsigned long long sum = 0;
    for (;;) {
        DWORD read = 0;
        BOOL ok = ReadFile(h, buf, (DWORD)(block_size > 0x7fffffff ? 0x7fffffff : block_size), &read, NULL);
        if (!ok) { sum = (unsigned long long)-1; break; }
        if (read == 0) break;
        for (DWORD i = 0; i < read; i++) sum += buf[i];
    }

    free(buf);
    CloseHandle(h);
    return (long long)sum;
}


// Assinatura simples: in-place
// void radix_sort_i32(int32_t* base, size_t n)
#ifdef _WIN32
__declspec(dllexport)
#endif
void radix_sort_i32(int32_t* base, size_t n) {
    if (n < 2) return;

    // Vamos trabalhar como u32 (somente para extrair bytes via shift)
    uint32_t* src = reinterpret_cast<uint32_t*>(base);
    uint32_t* tmp = (uint32_t*) std::malloc(n * sizeof(uint32_t));
    if (!tmp) return; // sem memória -> não ordena

    uint32_t* in  = src;
    uint32_t* out = tmp;

    // 4 passes de 8 bits (LSD). Para ter a MESMA ordem de int (signed),
    // usamos chave transformada u = (x ^ 0x80000000).
    for (int pass = 0; pass < 4; pass++) {
        size_t count[256];
        std::memset(count, 0, sizeof(count));

        // contagem
        for (size_t i = 0; i < n; i++) {
            uint32_t u = in[i] ^ 0x80000000u;
            uint32_t b = (u >> (pass * 8)) & 0xFFu;
            count[b]++;
        }

        // prefixo
        size_t pos[256];
        size_t sum = 0;
        for (int b = 0; b < 256; b++) {
            pos[b] = sum;
            sum   += count[b];
        }

        // scatter
        for (size_t i = 0; i < n; i++) {
            uint32_t v = in[i];
            uint32_t u = v ^ 0x80000000u;
            uint32_t b = (u >> (pass * 8)) & 0xFFu;
            out[pos[b]++] = v;
        }

        // troca buffers
        uint32_t* t = in; in = out; out = t;
    }

    // Após 4 passes (par), o resultado final está em 'in' que aponta para o array original.
    std::free(tmp);
}

} // extern "C"

#else  // ---------- POSIX (Linux/macOS): stdio + prealloc/hints ----------

static inline void tune_stdio_buffer(FILE* f, size_t block_size) {
    const size_t MINB = 1u << 20;  // 1 MiB
    const size_t MAXB = 8u << 20;  // 8 MiB
    size_t sz = block_size;
    if (sz < MINB) sz = MINB;
    if (sz > MAXB) sz = MAXB;
    setvbuf(f, NULL, _IOFBF, sz);
}

extern "C" {

DLL_EXPORT int write_file_native(const char* path, size_t total_bytes, size_t block_size, unsigned int seed) {
    FILE* f = fopen(path, "wb");
    if (!f) return errno ? errno : -1;

    tune_stdio_buffer(f, block_size);
    // pré-aloca (normalmente ajuda em POSIX)
    int fd = fileno(f);
    ftruncate(fd, (off_t) total_bytes);
  #if defined(__linux__)
    posix_fadvise(fd, 0, 0, POSIX_FADV_SEQUENTIAL);
  #endif

    unsigned char* buf = (unsigned char*)malloc(block_size);
    if (!buf) { fclose(f); return ENOMEM; }

    unsigned int x = seed;
    size_t remaining = total_bytes;

    while (remaining > 0) {
        size_t n = (remaining < block_size) ? remaining : block_size;
        fill_block(buf, n, &x);

        size_t off = 0;
        while (off < n) {
            size_t w = fwrite(buf + off, 1, n - off, f);
            if (w == 0) {
                int err = ferror(f) ? errno : EIO;
                free(buf); fclose(f);
                return err ? err : -2;
            }
            off += w;
        }
        remaining -= n;
    }

    free(buf);
    int rc = fclose(f);
    return (rc == 0) ? 0 : (errno ? errno : -3);
}

DLL_EXPORT long long read_file_native(const char* path, size_t block_size) {
    FILE* f = fopen(path, "rb");
    if (!f) return -1;

    tune_stdio_buffer(f, block_size);
  #if defined(__linux__)
    posix_fadvise(fileno(f), 0, 0, POSIX_FADV_SEQUENTIAL);
  #endif

    unsigned char* buf = (unsigned char*)malloc(block_size);
    if (!buf) { fclose(f); return -1; }

    unsigned long long sum = 0;
    for (;;) {
        size_t r = fread(buf, 1, block_size, f);
        if (r == 0) break;
        for (size_t i = 0; i < r; i++) sum += buf[i];
    }

    free(buf);
    if (fclose(f) != 0) return -1;
    return (long long)sum;
}

} // extern "C"
#endif
