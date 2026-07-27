#pragma once
#include <zephyr/drivers/gpio.h>
#include "OutputPin.h"

/** @brief OutputPin for Zephyr RTOS, backed by a gpio_dt_spec.
 *
 * prj.conf must enable CONFIG_GPIO=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 *
 * @param pin gpio_dt_spec configured GPIO_OUTPUT (any initial level).
 */
class OutputPinZephyr : public OutputPin {
public:
    explicit OutputPinZephyr(const struct gpio_dt_spec& pin) : _pin(pin) {
        gpio_pin_configure_dt(&_pin, GPIO_OUTPUT);
    }

    void set(bool high) override {
        gpio_pin_set_dt(&_pin, high ? 1 : 0);
    }

private:
    struct gpio_dt_spec _pin;
};
