#pragma once
#include <driver/gpio.h>
#include "OutputPin.h"

/** @brief OutputPin for ESP-IDF, backed by gpio_set_level().
 *
 * @param pin GPIO pin number (gpio_num_t); configured as output at construction.
 */
class OutputPinESPIDF : public OutputPin {
public:
    explicit OutputPinESPIDF(gpio_num_t pin) : _pin(pin) {
        gpio_reset_pin(_pin);
        gpio_set_direction(_pin, GPIO_MODE_OUTPUT);
    }

    void set(bool high) override {
        gpio_set_level(_pin, high ? 1 : 0);
    }

private:
    gpio_num_t _pin;
};
