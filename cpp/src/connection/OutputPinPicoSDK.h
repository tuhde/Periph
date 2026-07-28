#pragma once
#include <hardware/gpio.h>
#include "OutputPin.h"

/** @brief OutputPin for Raspberry Pi Pico SDK, backed by gpio_put().
 *
 * @param pin GPIO pin number; configured as output at construction.
 */
class OutputPinPicoSDK : public OutputPin {
public:
    explicit OutputPinPicoSDK(uint pin) : _pin(pin) {
        gpio_init(_pin);
        gpio_set_dir(_pin, GPIO_OUT);
    }

    void set(bool high) override {
        gpio_put(_pin, high ? 1 : 0);
    }

private:
    uint _pin;
};
