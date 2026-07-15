#pragma once
#include <Arduino.h>

/** @brief DHT11 pin adapter for Arduino.
 *
 *  Wraps an Arduino pin number. The adapter reconfigures the pin between
 *  OUTPUT (host-driven) and INPUT (high-impedance, pulled HIGH by an
 *  external 4.7 kΩ resistor) on every direction change.
 *
 *  @param pin  Arduino pin number.
 */
class DHT11PinArduino {
public:
    /** @brief Construct the adapter and configure the pin as INPUT.
     *  @param pin  Arduino pin number.
     */
    explicit DHT11PinArduino(uint8_t pin) : _pin(pin) {
        pinMode(_pin, INPUT);
    }

    /** @brief Configure the pin as output (driven by the host). */
    void set_output() { pinMode(_pin, OUTPUT); }

    /** @brief Configure the pin as input (high-impedance; pulled HIGH externally). */
    void set_input()  { pinMode(_pin, INPUT);  }

    /** @brief Drive the pin HIGH (``true``) or LOW (``false``). */
    void drive(bool high) { digitalWrite(_pin, high ? HIGH : LOW); }

    /** @brief Read the current logic level of the pin.
     *  @return ``true`` for HIGH, ``false`` for LOW.
     */
    bool read() { return digitalRead(_pin) == HIGH; }

private:
    uint8_t _pin;
};
