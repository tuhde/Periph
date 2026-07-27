#pragma once
#include <hardware/gpio.h>
#include <pico/time.h>

/** @brief DHTxx single-wire connection for Raspberry Pi Pico SDK.
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 *  Direction switching uses `gpio_set_dir`; timing uses `sleep_us()` for the
 *  start pulse and `time_us_32()` for pulse-width measurement.
 *
 *  This is a custom protocol with no generic byte read/write, so it does not
 *  extend the shared Connection base — it carries its own enabled flag
 *  directly, gating read().
 *
 *  @param data_pin Pico GPIO pin number for the DATA line.
 */
class DHTxxConnectionPicoSDK {
public:
    explicit DHTxxConnectionPicoSDK(uint data_pin) : _pin(data_pin) {
        gpio_init(_pin);
        gpio_set_dir(_pin, GPIO_IN);
    }

    /** @brief Resume reads. */
    void enable() { _enabled = true; }
    /** @brief Gate read(); it returns false without touching the bus while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return     `true` on success, `false` on timeout/framing error/disabled.
     */
    bool read(uint8_t* out) {
        if (!_enabled) return false;
        // Drive low for 20 ms start pulse then release
        gpio_set_dir(_pin, GPIO_OUT);
        gpio_put(_pin, 0);
        sleep_us(_START_LOW_MS * 1000);
        gpio_set_dir(_pin, GPIO_IN);

        // Sensor response: pull low ~80 µs, then high ~80 µs
        if (_measure_pulse(0, _RESPONSE_TIMEOUT_US) < 0) return false;
        if (_measure_pulse(1, _RESPONSE_TIMEOUT_US) < 0) return false;

        for (uint8_t byte_idx = 0; byte_idx < 5; byte_idx++) {
            uint8_t byte = 0;
            for (uint8_t bit_idx = 0; bit_idx < 8; bit_idx++) {
                if (_measure_pulse(0, _BIT_TIMEOUT_US) < 0) return false;
                int32_t high = _measure_pulse(1, _BIT_TIMEOUT_US);
                if (high < 0) return false;
                byte = (byte << 1) | (high > (int32_t)_BIT_THRESHOLD_US ? 1 : 0);
            }
            out[byte_idx] = byte;
        }
        return true;
    }

    /** @brief Release the pin back to input. */
    void close() { gpio_set_dir(_pin, GPIO_IN); }

private:
    uint _pin;
    bool _enabled = true;

    int32_t _measure_pulse(int level, uint32_t timeout_us) {
        uint32_t start = time_us_32();
        while ((int)gpio_get(_pin) != level) {
            if (time_us_32() - start > timeout_us) return -1;
        }
        uint32_t pulse_start = time_us_32();
        while ((int)gpio_get(_pin) == level) {
            if (time_us_32() - pulse_start > timeout_us) return -1;
        }
        return (int32_t)(time_us_32() - pulse_start);
    }

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
