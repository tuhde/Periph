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
        _setOk(gpio_pin_configure_dt(&_pin, GPIO_INPUT) == 0);
        gpio_init_callback(&_cb, &InputPinZephyr::isrTrampoline, BIT(_pin.pin));
    }

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override {
        bool added = addHandler(_handlers, handler);
        if (added && !_armed) {
            gpio_flags_t flags = trigger == kRising ? GPIO_INT_EDGE_RISING
                                : trigger == kChange  ? GPIO_INT_EDGE_BOTH
                                                      : GPIO_INT_EDGE_FALLING;
            bool good = gpio_pin_interrupt_configure_dt(&_pin, flags) == 0;
            good = good && gpio_add_callback(_pin.port, &_cb) == 0;
            _setOk(good);
            _armed = good;   // retry on the next onEdge() if arming failed
        }
        return added;
    }

    void offEdge(Handler handler) override {
        removeHandler(_handlers, handler);
    }

    /** @brief Raw level via gpio_pin_get_raw() (ignores the devicetree active-low flag). */
    bool isHigh() override {
        int v = gpio_pin_get_raw(_pin.port, _pin.pin);
        _setOk(v >= 0);
        return v > 0;
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
