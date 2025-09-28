// native/intsrtcxx.cpp
#include <algorithm>
#include <cstddef>

extern "C" {
#ifdef _WIN32
__declspec(dllexport)
#endif
void sort_ints_cxx(int* base, std::size_t n) {
    std::sort(base, base + n);
}
}
