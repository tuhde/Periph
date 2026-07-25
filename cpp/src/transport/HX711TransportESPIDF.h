#pragma once
#include <stdint.h>
#include <climits>
#include <driver/gpio.h>
#include <esp_timer.h>
#include <esp_rom_sys.h>

/** @brief HX711 GPIO bit-bang transport for ESP-IDF.
 *
 * Direct port of the Zephyr bit-bang loop onto `driver/gpio.h`.
 * Implements the 2-wire bit-bang protocol used exclusively by the
 * HX711 24-bit ADC: DOUT is sampled on each falling edge of PD_SCK;
 * the pulse count selects the channel and gain for the next
 * conversion.
 *
 * No explicit delay is needed between clock edges — GPIO call
 * overhead exceeds the 0.2 µs T3/T4 minimums, the same reasoning as
 * the Arduino HX711 transport. There is no scheduler to yield to on
 * bare-metal ESP-IDF tasks pinned to the polling loop, so the
 * DOUT-ready wait in `read_raw` is a tight `gpio_get_level()` poll
 * loop, timed against the 1 s timeout via `esp_timer_get_time()`. Use
 * `esp_rom_delay_us()` rather than `vTaskDelay()` for any sub-tick
 * delay — `vTaskDelay()`'s minimum granularity is one FreeRTOS tick
 * (typically 1–10 ms), far coarser than the 50 µs T3 ceiling this
 * protocol requires.
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must link `driver` and `esp_timer`.
 *
 * @param dout   GPIO pin number (`gpio_num_t`) for the DOUT line
 *               (data input).
 * @param pd_sck GPIO pin number (`gpio_num_t`) for the PD_SCK line
 *               (clock / power-down).
 */
class HX711TransportESPIDF {
public:
    HX711TransportESPIDF(gpio_num_t dout, gpio_num_t pd_sck)
        : _dout(dout), _sck(pd_sck)
    {
        gpio_reset_pin(_dout);
        gpio_set_direction(_dout, GPIO_MODE_INPUT);
        gpio_reset_pin(_sck);
        gpio_set_direction(_sck, GPIO_MODE_OUTPUT);
        gpio_set_level(_sck, 0);  // PD_SCK idle LOW
    }

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     *
     *  @return true when DOUT is LOW (data ready).
     */
    bool is_ready() {
        return gpio_get_level(_dout) == 0;
    }

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Polls DOUT until LOW (conversion ready), then sends exactly
     *  @p num_pulses pulses on PD_SCK, sampling DOUT at each falling
     *  edge (HIGH→LOW transition). Leaves PD_SCK LOW after the last
     *  pulse. The pulse count programs the channel and gain for the
     *  next conversion:
     *  25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value, or INT32_MIN on
     *                    timeout or invalid pulse count.
     */
    int32_t read_raw(uint8_t num_pulses = 25) {
        if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
            return INT32_MIN;
        int64_t deadline = static_cast<int64_t>(esp_timer_get_time()) + 1000000LL;
        while (gpio_get_level(_dout) != 0) {
            if (static_cast<int64_t>(esp_timer_get_time()) >= deadline) return INT32_MIN;
        }
        uint32_t raw = 0;
        for (uint8_t i = 0; i < num_pulses; i++) {
            gpio_set_level(_sck, 1);
            esp_rom_delay_us(1);
            gpio_set_level(_sck, 0);
            esp_rom_delay_us(1);
            raw = (raw << 1) | static_cast<uint32_t>(gpio_get_level(_dout));
        }
        raw >>= num_pulses - 24;
        if (raw & 0x800000u)
            return static_cast<int32_t>(raw) - 0x1000000;
        return static_cast<int32_t>(raw);
    }

    /** @brief Enter power-down mode by holding PD_SCK HIGH for >60 µs. */
    void power_down() {
        gpio_set_level(_sck, 1);
        esp_rom_delay_us(65);
    }

    /** @brief Exit power-down mode and reset the chip.
     *
     *  Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The
     *  first conversion after power-up must be discarded.
     */
    void power_up() {
        gpio_set_level(_sck, 0);
    }

private:
    gpio_num_t _dout;
    gpio_num_t _sck;
};
