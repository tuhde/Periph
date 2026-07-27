#pragma once
#include <hardware/gpio.h>
#include "InputPin.h"

/** @brief InputPin for Raspberry Pi Pico SDK, backed by
 * gpio_set_irq_enabled_with_callback().
 *
 * The Pico SDK callback is chip-wide (one callback for every GPIO IRQ,
 * dispatched with the triggering pin number), so this class keeps a small
 * static pin->instance registry — at most kMaxInstances InputPinPicoSDK
 * objects may be active concurrently.
 *
 * @param pin GPIO pin number; configured as input at construction.
 */
class InputPinPicoSDK : public InputPin {
public:
    explicit InputPinPicoSDK(uint pin) : _pin(pin) {
        gpio_init(_pin);
        gpio_set_dir(_pin, GPIO_IN);
    }

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override {
        bool added = addHandler(_handlers, handler);
        if (added && !_armed) {
            uint32_t events = trigger == kRising ? GPIO_IRQ_EDGE_RISE
                             : trigger == kChange  ? (GPIO_IRQ_EDGE_RISE | GPIO_IRQ_EDGE_FALL)
                                                   : GPIO_IRQ_EDGE_FALL;
            registerInstance(_pin, this);
            gpio_set_irq_enabled_with_callback(_pin, events, true, &InputPinPicoSDK::isrTrampoline);
            _armed = true;
        }
        return added;
    }

    void offEdge(Handler handler) override {
        removeHandler(_handlers, handler);
    }

private:
    static constexpr uint8_t kMaxInstances = 8;

    static void registerInstance(uint pin, InputPinPicoSDK* self) {
        for (uint8_t i = 0; i < kMaxInstances; ++i) {
            if (_instances[i] == nullptr) {
                _instances[i] = self;
                _instancePins[i] = pin;
                return;
            }
        }
    }

    static void isrTrampoline(uint gpio, uint32_t /*events*/) {
        for (uint8_t i = 0; i < kMaxInstances; ++i) {
            if (_instances[i] != nullptr && _instancePins[i] == gpio) {
                dispatch(_instances[i]->_handlers);
            }
        }
    }

    static InputPinPicoSDK* _instances[kMaxInstances];
    static uint _instancePins[kMaxInstances];

    uint _pin;
    Handler _handlers[kMaxHandlers] = {};
    bool _armed = false;
};

inline InputPinPicoSDK* InputPinPicoSDK::_instances[InputPinPicoSDK::kMaxInstances] = {};
inline uint InputPinPicoSDK::_instancePins[InputPinPicoSDK::kMaxInstances] = {};
