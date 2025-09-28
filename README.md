## O que é o Projeto Panama? 🧰

O **Projeto Panama** é uma iniciativa do OpenJDK para tornar a interoperabilidade do Java com **código e dados nativos** mais simples, segura e eficiente, reduzindo (ou eliminando) a necessidade de escrever **JNI** manualmente. No Java 25, a peça central é a **Foreign Function & Memory (FFM) API**, que permite:

* **Chamar funções nativas** diretamente (*downcalls*) e **expor funções Java** para bibliotecas nativas (*upcalls*).
* **Acessar memória off-heap com segurança** usando `MemorySegment` e **arenas com escopo** (`Arena.ofConfined`, `Arena.ofShared`).
* **Descrever layouts C** (structs, arrays, alinhamento) com `MemoryLayout` e acessar campos com `VarHandle`.
* **Resolver símbolos** em bibliotecas do sistema ou em DLL/SO específicos com `SymbolLookup`/`Linker`.
* Tratar casos avançados: **funções variádicas** (`printf`), **captura de estado** de chamada (ex.: `errno`), **ponteiros de função** e tipos portáveis (ex.: `size_t` via `canonicalLayouts()`).

**Por que usar?**

* **Performance** e menos cópias (dados fora do heap, sem *glue code* JNI).
* **Segurança de memória** com escopos/validação e melhor ergonomia que JNI.
* **Portabilidade**: o mesmo código roda em **Windows / Linux / macOS**.

**Ferramenta relacionada**

* **`jextract`** (fora do JDK) pode gerar *bindings* Java diretamente de *headers* C — opcional, mas acelera muito o desenvolvimento.

Este repositório demonstra o uso da **Foreign Function & Memory API** (Project Panama) do **Java 25** por meio de exemplos pequenos e diretos, além de benchmarks comparando Java × nativo.

> **Requisitos**
>
> * **JDK 25** (OpenJDK 25)
> * **Maven 3.9+**
> * Windows / Linux / macOS
> * Em *runtime*, ative: `--enable-native-access=ALL-UNNAMED`

---

## Conteúdo 📚

### Exemplos básicos 🧪

* `PanamaExample` — chama `puts` (libc) e imprime uma C string.
* `DemoPrintf` — chamada variádica a `printf`.
* `DemoStruct` — modelagem de `struct` C com `MemoryLayout` + `VarHandle`.
* `DemoQsort` — *downcall* para `qsort` com **upcall** de comparador Java.
* `DemoErrno` — captura de `errno` com `Linker.Option.captureCallState("errno")`.

### Novos exemplos / Benchmarks 📊

* `SortBenchmarkCxx` — **Arrays.sort / Arrays.parallelSort** (Java) vs **std::sort** (nativo).
  (Opcional: `std::sort(par)` se `<execution>` disponível no seu toolchain.)
* `FileIOBenchmark` — **I/O de arquivos**: Java (`FileChannel` + buffers) vs nativo
  (Windows: Win32 com OVERLAPPED + duplo buffer; Linux/macOS: `stdio` com *tuning*).
* `RadixSortBenchmark` — **parallelSort (Java)** vs **radix_sort_i32** (nativo, O(n)), mantendo a mesma ordem de `int` assinado do Java.

> Os exemplos usam recursos **finais** do JDK 25 (API `java.lang.foreign`). Não é necessário `--enable-preview`.
> Se você optar por **main compacta / instance `main`** (ex.: `void main()`), veja **Execução via Maven**.

---

## Como rodar ▶️

### 1) IntelliJ IDEA (mais simples) 💻

* Aponte o projeto para o **JDK 25**.
* Clique com o botão direito na classe de exemplo e **Run**.
* Se o exemplo faz *downcall/upcall*, adicione em **Run Configuration → VM options**:

  ```
  --enable-native-access=ALL-UNNAMED
  ```

### 2) Maven — com `exec-maven-plugin:java` (para **classe nomeada**) 🧰

O `exec-maven-plugin` **ainda não suporta** o novo modelo **sem classe nomeada** (arquivo-fonte com `void main()` puro).
Para rodar via Maven **use classes com nome** e `main` convencional, **ou** veja a opção 3 abaixo.

Exemplo:

```bash
mvn -q clean compile exec:java -Dexec.mainClass=PanamaExample
# ou o FQN se tiver package:
# mvn -q clean compile exec:java -Dexec.mainClass=com.exemplo.PanamaExample
```

No `pom.xml`, passe a flag em runtime:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.5.1</version>
  <configuration>
    <mainClass>PanamaExample</mainClass>
    <jvmArgs>
      <jvmArg>--enable-native-access=ALL-UNNAMED</jvmArg>
    </jvmArgs>
  </configuration>
</plugin>
```

### 3) Maven — executando **arquivo-fonte** (suporta `void main()` sem classe) 🧾

Use `exec:exec` para chamar o *launcher* do Java diretamente no **source-file mode**:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.5.1</version>
  <executions>
    <execution>
      <id>run-source</id>
      <goals><goal>exec</goal></goals>
      <configuration>
        <executable>java</executable>
        <arguments>
          <argument>--enable-native-access=ALL-UNNAMED</argument>
          <argument>--source</argument>
          <argument>25</argument>
          <!-- ajuste para o caminho do seu arquivo .java com `void main()` -->
          <argument>${project.basedir}/src/main/java/Main.java</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Rodando:

```bash
mvn -q exec:exec@run-source
```

> **Dica:** crie `.mvn/jvm.config` com `--enable-native-access=ALL-UNNAMED` para também silenciar warnings do Maven (Jansi/Unsafe).

---

## Biblioteca nativa (`native/intsrtcxx.cpp`) 🧱

O arquivo exporta as funções abaixo:

* `void sort_ints_cxx(int* base, size_t n)` — `std::sort` (serial)
* `void sort_ints_cxx_par(int* base, size_t n)` — `std::sort` paralelo (se `<execution>` disponível)
* `int  write_file_native(const char* path, size_t total, size_t block, unsigned int seed)` — escrita
* `long long read_file_native(const char* path, size_t block)` — leitura
* `void radix_sort_i32(int32_t* base, size_t n)` — **radix sort** (4 passes × 8 bits) com ordem idêntica a `int` do Java via `x ^ 0x80000000`

### Windows (MSVC, 64-bit, runtime estático) 🪟

Abra **x64 Native Tools Command Prompt for VS 2022**:

```bat
cd native
cl /O2 /EHsc /LD /MT /std:c++20 intsrtcxx.cpp /Fe:intsrt.dll
```

Verifique exportações:

```bat
dumpbin /exports intsrt.dll | findstr /i "sort_ints_cxx write_file_native read_file_native radix_sort_i32"
```

### Linux 🐧

```bash
cd native
g++ -O3 -fPIC -shared -std=c++20 -o libintsrt.so intsrtcxx.cpp
```

### macOS 🍎

```bash
cd native
clang++ -O3 -fPIC -shared -std=c++20 -o libintsrt.dylib intsrtcxx.cpp
```

Coloque a DLL/SO em `poc-project-panama/native` (o código Java resolve esse caminho automaticamente).

---

## Exemplos — destaques e trechos ✨

### PanamaExample — `puts` 📣

* Aloca C string com `Arena.allocateFrom(...)`.
* Resolve `puts` via `Linker.nativeLinker().defaultLookup()`.
* Cria `downcallHandle` e invoca.

### DemoPrintf — `printf` variádico 📝

* Use `Linker.Option.firstVariadicArg(1)` (índice `0` é o `format`).
* O *method handle* de `printf` retorna **`int`** → com `invokeExact`, a assinatura deve **bater exatamente**.

### DemoStruct — `MemoryLayout` + `VarHandle` 🧩

* `StructLayout` com campos nomeados; `VarHandle` usa `(segment, long offset, value)` → passe `0L` quando o struct começa no início.

### DemoQsort — *downcall* + **upcall** (comparador) 🔁

* `qsort(void* base, size_t nmemb, size_t size, int (*compar)(const void*,const void*))`.
* No `upcallStub`, informe o **layout-alvo** dos `void*` do comparador com `ADDRESS.withTargetLayout(...)`.
* Use `linker.canonicalLayouts().get("size_t")` para portabilidade.

### DemoErrno — `captureCallState("errno")` 🚨

* Crie o handle com `Linker.Option.captureCallState("errno")`.
* Aloque `captureStateLayout()` e **passe esse segmento como 1º argumento** ao invocar.
* Leia `errno` via `VarHandle` do layout capturado.

---

## Benchmarks 📈

### SortBenchmarkCxx — Java vs C++ ⚖️

Compara:

* `Arrays.sort(int[])` (single-thread)
* `Arrays.parallelSort(int[])` (multi-thread)
* `std::sort` (DLL) e, se disponível, `std::sort(par)` (C++ `<execution>`)

Exemplo típico (5M ints, x64):

```
Java sort          : 280–300 ms
Java parallelSort  :  90–120 ms
Native std::sort   : 280–320 ms
OK: resultados iguais e ordenados.
```

**Leitura:** `parallelSort` costuma vencer. Para superar, use algoritmo diferente (veja `RadixSortBenchmark`).

**Executar:**

```bash
mvn -q clean compile exec:java \
  -Dexec.mainClass=SortBenchmarkCxx \
  -Dexec.jvmArgs="--enable-native-access=ALL-UNNAMED"
```

### FileIOBenchmark — I/O Java vs nativo 💾

* **Java:** `FileChannel` + `ByteBuffer` (direto/heap), blocos grandes.
* **Windows nativo:** Win32 (`CreateFileW`/`ReadFile`/`WriteFile`). Escrita com **OVERLAPPED + duplo buffer** (pipeline).
* **Linux/macOS nativo:** `stdio` com `setvbuf`, `ftruncate` e (Linux) `posix_fadvise`.

Exemplo (1 GiB, bloco 1 MiB) observado:

```
Java  write : ~800–850 ms
Java  read  : ~330–420 ms
Native write: ~1.0–1.2 s
Native read : ~230–350 ms
```

**Leitura:** com loop Java eficiente (evite `get()` por byte), Java empata/fica perto do nativo; leitura nativa pode ganhar levemente.
**Escrita:** Java via `FileChannel` é muito competitivo; ganhos nativos exigem modos mais agressivos (ex.: `NO_BUFFERING`/`O_DIRECT`, filas maiores etc.).

**Executar (1 GiB / 1 MiB):**

```bash
mvn -q exec:java \
  -Dexec.mainClass=FileIOBenchmark \
  -Dexec.jvmArgs="--enable-native-access=ALL-UNNAMED" \
  -Dexec.args="1024 1024"
```

> Parâmetros: `FileIOBenchmark <tamanhoMB> <blocoKB>`

### RadixSortBenchmark — parallelSort vs radix_sort (O(n)) 🚀

* `radix_sort_i32` (4 passes × 8 bits) respeita a **ordem de inteiros com sinal** via `x ^ 0x80000000`.
* Uma única chamada grande amortiza a fronteira Java↔nativo.

**Executar (ex.: 30 milhões):**

```bash
mvn -q exec:java \
  -Dexec.mainClass=RadixSortBenchmark \
  -Dexec.jvmArgs="--enable-native-access=ALL-UNNAMED" \
  -Dexec.args="30000000"
```

---

## Dicas de performance 💡

* **Amortize a fronteira**: prefira 1 chamada que processa milhões de itens a milhões de chamadas pequenas.
* **Evite upcalls** em hot-loops.
* **Buffers grandes** (2–8 MiB) para I/O; evite `ByteBuffer.get()` por byte (prefira acesso ao array ou cópia em bloco).
* **Paralelismo**: teste `Arrays.parallelSort`; no nativo, use `<execution>` ou paralelize o algoritmo (ex.: radix paralelo).
* Para números “finais”, rode fora do IntelliJ (sem `-javaagent`), fixe heap (`-Xms/-Xmx`) e reporte **mediana** de várias rodadas.

---

## Estrutura sugerida 🗂️

```
poc-project-panama/
├─ pom.xml
├─ .mvn/
│   └─ jvm.config            # (opcional) --enable-native-access=ALL-UNNAMED
├─ native/
│   └─ intsrtcxx.cpp         # DLL/SO com sort, I/O e radix sort
└─ src/main/java/
   ├─ PanamaExample.java     # puts
   ├─ DemoPrintf.java        # printf variádico
   ├─ DemoStruct.java        # struct + VarHandle
   ├─ DemoQsort.java         # qsort + upcall
   ├─ DemoErrno.java         # errno com captureCallState
   ├─ SortBenchmarkCxx.java  # Arrays.sort / parallelSort vs std::sort
   ├─ FileIOBenchmark.java   # I/O Java vs nativo
   └─ RadixSortBenchmark.java# parallelSort vs radix_sort nativo
```

---

## Notas de plataforma (Windows) 🪟

* Se `defaultLookup()` não resolver símbolos da sua DLL carregada manualmente, use `SymbolLookup.libraryLookup(path, arenaCompartilhado)` e **mantenha o `Arena.ofShared()` vivo** durante todo o processo.
* Confirme arquitetura **x64** da DLL:

  ```bat
  dumpbin /headers intsrt.dll | findstr /i machine   # deve mostrar 8664 (x64)
  ```

---

## Solução de problemas 🛠️

* **`Symbol not found: ...`**
  Verifique export com `dumpbin /exports intsrt.dll` (Windows) ou `nm -gD libintsrt.so` (Linux).
  Garanta `extern "C"` + `DLL_EXPORT` e o **nome exato** do símbolo.
* **`UnsatisfiedLinkError` / não encontra DLL**
  Aponte caminho absoluto em `libraryLookup(...)` e confirme arquitetura **x64**.
* **`Already closed` (FFM)**
  Não prenda `SymbolLookup.libraryLookup(..., arena)` a um `Arena` que será fechado.
  Use um **`Arena.ofShared()` estático** para manter endereços dos símbolos válidos.
* **Avisos “restricted method called”**
  Sempre rode com `--enable-native-access=ALL-UNNAMED`.
* **`exec-maven-plugin:java` não acha `main`**
  Ele **não suporta** `void main()` sem classe nomeada. Use `exec:exec` (source-file mode) ou um *wrapper* com `public static void main(String[] args)`.

---

## Trecho útil do `pom.xml` 🧩

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.11.0</version>
      <configuration>
        <release>25</release>
      </configuration>
    </plugin>

    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>exec-maven-plugin</artifactId>
      <version>3.5.1</version>
      <configuration>
        <mainClass>PanamaExample</mainClass>
        <jvmArgs>
          <jvmArg>--enable-native-access=ALL-UNNAMED</jvmArg>
        </jvmArgs>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---
