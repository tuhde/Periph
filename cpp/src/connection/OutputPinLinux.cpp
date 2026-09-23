#ifdef __linux__
#include "OutputPinLinux.h"

void OutputPinLinux::set(bool high) {
    _line.set(high);
}
#endif
