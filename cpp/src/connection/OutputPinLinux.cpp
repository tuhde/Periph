#ifdef __linux__
#include "OutputPinLinux.h"
#include <gpiod.h>

void OutputPinLinux::set(bool high) {
    gpiod_line_set_value(_line, high ? 1 : 0);
}
#endif
