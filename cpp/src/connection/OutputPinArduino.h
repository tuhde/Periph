#pragma once
#include <Arduino.h>
#include "OutputPin.h"

/** @brief OutputPin for Arduino, backed by digitalWrite().
 *
 * @param pin Arduino GPIO pin number; configured as OUTPUT at construction.
 */
class OutputPinArduino : public OutputPin {
public:
    explicit OutputPinArduino(uint8_t pin) : _pin(pin) {
        pinMode(_pin, OUTPUT);
    }

    void set(bool high) override {
        digitalWrite(_pin, high ? HIGH : LOW);
    }

private:
    uint8_t _pin;
};
