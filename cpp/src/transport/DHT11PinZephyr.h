#pragma once
#ifdef __ZEPHYR__
#include <zephyr/drivers/gpio.h>

/** @brief DHT11 pin adapter for Zephyr RTOS.
 *
 *  Wraps a single ``gpio_dt_spec``. The adapter reconfigures the pin between
 *  GPIO_OUTPUT and GPIO_INPUT on every direction change. A 4.7 kΩ external
 *  pull-up to VCC is required on the DATA line.
 *
 *  prj.conf must enable ``CONFIG_GPIO=y``.
 *
 *  @param spec  gpio_dt_spec for the DATA line.
 */
class DHT11PinZephyr {
public:
    /** @brief Construct the adapter and configure the pin as GPIO_INPUT.
     *  @param spec  gpio_dt_spec for the DATA line.
     */
    explicit DHT11PinZephyr(const struct gpio_dt_spec& spec) : _spec(spec) {
        gpio_pin_configure_dt(&_spec, GPIO_INPUT);
    }

    /** @brief Configure the pin as output (host drives the bus). */
    void set_output() { gpio_pin_configure_dt(&_spec, GPIO_OUTPUT_LOW); }

    /** @brief Configure the pin as input (host listens; pull-up holds HIGH). */
    void set_input()  { gpio_pin_configure_dt(&_spec, GPIO_INPUT);       }

    /** @brief Drive the pin HIGH (``true``) or LOW (``false``). */
    void drive(bool high) { gpio_pin_set_dt(&_spec, high ? 1 : 0); }

    /** @brief Read the current logic level of the pin.
     *  @return ``true`` for HIGH, ``false`` for LOW.
     */
    bool read() { return gpio_pin_get_dt(&_spec) == 1; }

private:
    const struct gpio_dt_spec _spec;
};
#endif
