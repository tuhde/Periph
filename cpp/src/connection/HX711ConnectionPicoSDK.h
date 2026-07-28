#pragma once
#include <hardware/gpio.h>
#include <pico/time.h>
#include <climits>

/** @brief HX711 GPIO bit-bang connection for the Raspberry Pi Pico SDK.
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * Bare-metal `pico-sdk` — no Arduino core, no RTOS. The connection owns
 * the GPIO directions (set in the constructor via `gpio_set_dir()`) and
 * drives them directly with `gpio_put()`.
 *
 * Timing: the only platform-specific sleeps are the per-edge 1 µs
 * `busy_wait_us` used to meet the 200 ns T3/T4 minimums on bare RP2040
 * silicon (whose `gpio_put` cycle is well under 100 ns, but the spec
 * requires at least 200 ns), and the 1 ms `sleep_ms` between DOUT polls
 * during the 1 s ready-wait so we don't spin a core.
 *
 * This is a custom protocol with no generic byte read/write, so it does not
 * extend the shared Connection base — it carries its own enabled flag
 * directly, gating read_raw().
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project. Link against `hardware_gpio` and
 * `pico_time`.
 *
 * @param dout   GPIO pin number for the DOUT line (data input).
 * @param pd_sck GPIO pin number for the PD_SCK line (clock / power-down).
 */
class HX711ConnectionPicoSDK {
public:
    HX711ConnectionPicoSDK(uint dout, uint pd_sck)
        : _dout(dout), _sck(pd_sck)
    {
        gpio_init(_dout);
        gpio_set_dir(_dout, GPIO_IN);
        gpio_init(_sck);
        gpio_set_dir(_sck, GPIO_OUT);
        gpio_put(_sck, 0);  // PD_SCK idle LOW
    }

    /** @brief Resume conversions. */
    void enable() { _enabled = true; }
    /** @brief Gate read_raw(); it returns 0 without touching the bus while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     *
     *  @return true when DOUT is LOW (data ready).
     */
    bool is_ready() {
        return gpio_get(_dout) == 0;
    }

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Polls DOUT until LOW (conversion ready), then sends exactly
     *  @p num_pulses pulses on PD_SCK, sampling DOUT at each falling
     *  edge (HIGH→LOW transition). Leaves PD_SCK LOW after the last
     *  pulse. The pulse count programs the channel and gain for the
     *  next conversion:
     *  25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
     *  Returns 0 without touching the bus if this connection is disabled.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value, 0 if disabled, or
     *                    INT32_MIN on timeout or invalid pulse count.
     */
    int32_t read_raw(uint8_t num_pulses = 25) {
        if (!_enabled) return 0;
        if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
            return INT32_MIN;
        absolute_time_t deadline = make_timeout_time_ms(1000);
        while (gpio_get(_dout) != 0) {
            if (absolute_time_diff_us(get_absolute_time(), deadline) <= 0)
                return INT32_MIN;
            sleep_ms(1);
        }
        uint32_t raw = 0;
        for (uint8_t i = 0; i < num_pulses; i++) {
            gpio_put(_sck, 1);
            busy_wait_us(1);
            gpio_put(_sck, 0);
            busy_wait_us(1);
            raw = (raw << 1) | static_cast<uint32_t>(gpio_get(_dout));
        }
        raw >>= num_pulses - 24;
        if (raw & 0x800000u)
            return static_cast<int32_t>(raw) - 0x1000000;
        return static_cast<int32_t>(raw);
    }

    /** @brief Enter power-down mode by holding PD_SCK HIGH for >60 µs. */
    void power_down() {
        gpio_put(_sck, 1);
        sleep_us(65);
    }

    /** @brief Exit power-down mode and reset the chip.
     *
     *  Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The
     *  first conversion after power-up must be discarded.
     */
    void power_up() {
        gpio_put(_sck, 0);
    }

private:
    uint _dout;
    uint _sck;
    bool _enabled = true;
};
