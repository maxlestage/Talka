// Sous Linux seulement : la bibliothèque Observation de Swift 6.1/6.2
// réclame ce symbole sans que la bibliothèque partagée le fournisse
// (il n'existe que dans la version statique). Rien de tout cela sur iPhone.
#include <cstdio>
#include <cstdlib>
#include <cstdarg>

namespace swift {
namespace threading {
[[noreturn]] void fatal(const char *msg, ...) {
  va_list args;
  va_start(args, msg);
  std::vfprintf(stderr, msg, args);
  va_end(args);
  std::abort();
}
} // namespace threading
} // namespace swift
