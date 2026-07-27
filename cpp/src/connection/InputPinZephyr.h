#pragma once
#include <zephyr/drivers/gpio.h>
#include <zephyr/sys/util.h>
#include "InputPin.h"

/** @brief InputPin for Zephyr RTOS, backed by gpio_add_callback().
 *
 * prj.conf must enable CONFIG_GPIO=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 *
 * @param pin gpio_dt_spec for the INT line.
 */
class InputPinZephyr : public InputPin {
public:
    explicit InputPinZephyr(const struct gpio_dt_spec& pin) : _pin(pin) {
        gpio_pin_configure_dt(&_pin, GPIO_INPUT);
        gpio_init_callback(&_cb, &InputPinZephyr::isrTrampoline, BIT(_pin.pin));
    }

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override {
        bool added = addHandler(_handlers, handler);
        if (added && !_armed) {
            gpio_flags_t flags = trigger == kRising ? GPIO_INT_EDGE_RISING
                                : trigger == kChange  ? GPIO_INT_EDGE_BOTH
                                                      : GPIO_INT_EDGE_FALLING;
            gpio_pin_interrupt_configure_dt(&_pin, flags);
            gpio_add_callback(_pin.port, &_cb);
            _armed = true;
        }
        return added;
    }

    void offEdge(Handler handler) override {
        removeHandler(_handlers, handler);
    }

private:
    static void isrTrampoline(const struct device*, struct gpio_callback* cb, gpio_port_pins_t) {
        InputPinZephyr* self = CONTAINER_OF(cb, InputPinZephyr, _cb);
        dispatch(self->_handlers);
    }

    struct gpio_dt_spec _pin;
    struct gpio_callback _cb {};
    Handler _handlers[kMaxHandlers] = {};
    bool _armed = false;
};
