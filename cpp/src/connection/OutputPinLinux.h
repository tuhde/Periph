#pragma once
#ifdef __linux__
#include "OutputPin.h"

struct gpiod_line;

/** @brief OutputPin for Linux GCC, backed by an already-requested libgpiod line.
 *
 * @param line libgpiod line requested as output (gpiod_line_request_output()).
 */
class OutputPinLinux : public OutputPin {
public:
    explicit OutputPinLinux(struct gpiod_line* line) : _line(line) {}

    void set(bool high) override;

private:
    struct gpiod_line* _line;
};
#endif
