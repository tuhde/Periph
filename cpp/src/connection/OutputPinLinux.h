#pragma once
#ifdef __linux__
#include "OutputPin.h"
#include "GpiodLineLinux.h"

/** @brief OutputPin for Linux GCC, backed by a libgpiod v2 line request.
 *
 * @param chip_path GPIO chip device, e.g. "/dev/gpiochip0".
 * @param offset Line offset on that chip (as shown by gpioinfo).
 * @param initial_high Level driven as soon as the line is requested (default low).
 */
class OutputPinLinux : public OutputPin {
public:
    OutputPinLinux(const char* chip_path, unsigned int offset, bool initial_high = false)
        : _line(chip_path, offset, GpiodLineLinux::Mode::Output, initial_high, "periph-out") {}

    void set(bool high) override;

private:
    GpiodLineLinux _line;
};
#endif
