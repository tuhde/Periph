#pragma once
#include <driver/gpio.h>
#include <esp_timer.h>
#include <esp_rom_sys.h>

/** @brief DHTxx single-wire transport for ESP-IDF.
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 *  Direction switching uses `gpio_set_direction`; timing uses
 *  `esp_rom_delay_us()` for the start pulse and `esp_timer_get_time()` for
 *  pulse-width measurement.
 *
 *  The start pulse drives the line LOW for 20 ms, then releases it. Pulse-width
 *  decoding uses a busy-poll on `gpio_get_level()` timed with
 *  `esp_timer_get_time()`. On ESP32 the GPIO read latency is well below 1 µs,
 *  so the 40 µs bit-threshold is reliably distinguishable from the 26–28 µs
 *  "0" pulse.
 *
 *  Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 *  The consuming project must link `driver` and `esp_timer`.
 *
 *  @param data_pin GPIO pin number (`gpio_num_t`) for the DATA line.
 */
class DHTxxTransportESPIDF {
public:
    explicit DHTxxTransportESPIDF(gpio_num_t data_pin) : _pin(data_pin) {
        gpio_reset_pin(_pin);
        gpio_set_direction(_pin, GPIO_MODE_INPUT);
    }

    /** @brief Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return     `true` on success, `false` on timeout/framing error.
     */
    bool read(uint8_t* out) {
        // Drive low for 20 ms start pulse then release
        gpio_set_direction(_pin, GPIO_MODE_OUTPUT);
        gpio_set_level(_pin, 0);
        esp_rom_delay_us(_START_LOW_MS * 1000);
        gpio_set_direction(_pin, GPIO_MODE_INPUT);

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
    void close() { gpio_set_direction(_pin, GPIO_MODE_INPUT); }

private:
    gpio_num_t _pin;

    int32_t _measure_pulse(int level, uint32_t timeout_us) {
        int64_t start = esp_timer_get_time();
        while (gpio_get_level(_pin) != level) {
            if (esp_timer_get_time() - start > (int64_t)timeout_us) return -1;
        }
        int64_t pulse_start = esp_timer_get_time();
        while (gpio_get_level(_pin) == level) {
            if (esp_timer_get_time() - pulse_start > (int64_t)timeout_us) return -1;
        }
        return (int32_t)(esp_timer_get_time() - pulse_start);
    }

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
