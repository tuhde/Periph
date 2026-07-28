#pragma once
#include <driver/gpio.h>
#include "InputPin.h"

/** @brief InputPin for ESP-IDF, backed by gpio_install_isr_service() +
 * gpio_isr_handler_add().
 *
 * @param pin GPIO pin number (gpio_num_t); configured as input at construction.
 */
class InputPinESPIDF : public InputPin {
public:
    explicit InputPinESPIDF(gpio_num_t pin) : _pin(pin) {
        gpio_reset_pin(_pin);
        gpio_set_direction(_pin, GPIO_MODE_INPUT);
    }

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override {
        bool added = addHandler(_handlers, handler);
        if (added && !_armed) {
            gpio_int_type_t intrType = trigger == kRising ? GPIO_INTR_POSEDGE
                                      : trigger == kChange  ? GPIO_INTR_ANYEDGE
                                                            : GPIO_INTR_NEGEDGE;
            gpio_set_intr_type(_pin, intrType);
            static bool serviceInstalled = false;
            if (!serviceInstalled) {
                gpio_install_isr_service(0);
                serviceInstalled = true;
            }
            gpio_isr_handler_add(_pin, &InputPinESPIDF::isrTrampoline, this);
            _armed = true;
        }
        return added;
    }

    void offEdge(Handler handler) override {
        removeHandler(_handlers, handler);
    }

private:
    static void IRAM_ATTR isrTrampoline(void* arg) {
        InputPinESPIDF* self = static_cast<InputPinESPIDF*>(arg);
        dispatch(self->_handlers);
    }

    gpio_num_t _pin;
    Handler _handlers[kMaxHandlers] = {};
    bool _armed = false;
};
