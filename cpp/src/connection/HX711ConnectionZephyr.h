#pragma once
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>

/** @brief HX711 GPIO bit-bang connection for Zephyr RTOS.
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * prj.conf must enable CONFIG_GPIO=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 *
 * This is a custom protocol with no generic byte read/write, so it does not
 * extend the shared Connection base — it carries its own enabled flag
 * directly, gating read_raw().
 *
 * @param dout   gpio_dt_spec for the DOUT pin (GPIO_INPUT).
 * @param pd_sck gpio_dt_spec for the PD_SCK pin (GPIO_OUTPUT_LOW).
 */
class HX711ConnectionZephyr {
public:
    HX711ConnectionZephyr(const struct gpio_dt_spec& dout,
                          const struct gpio_dt_spec& pd_sck)
        : _dout(dout), _sck(pd_sck)
    {
        gpio_pin_configure_dt(&_dout, GPIO_INPUT);
        gpio_pin_configure_dt(&_sck,  GPIO_OUTPUT_LOW);
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
        return gpio_pin_get_dt(&_dout) == 0;
    }

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Polls DOUT until LOW (conversion ready), then sends exactly num_pulses
     *  pulses on PD_SCK, sampling DOUT at each falling edge (HIGH→LOW
     *  transition). Leaves PD_SCK LOW after the last pulse. The pulse count
     *  programs the channel and gain for the next conversion:
     *  25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
     *  Returns 0 without touching the bus if this connection is disabled.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value, 0 if disabled, or INT32_MIN on timeout/error.
     */
    int32_t read_raw(uint8_t num_pulses = 25) {
        if (!_enabled) return 0;
        if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
            return INT32_MIN;
        int64_t deadline = k_uptime_get() + 1000LL;
        while (gpio_pin_get_dt(&_dout) != 0) {
            if (k_uptime_get() >= deadline) return INT32_MIN;
        }
        uint32_t raw = 0;
        for (uint8_t i = 0; i < num_pulses; i++) {
            gpio_pin_set_dt(&_sck, 1);
            k_usleep(1);
            gpio_pin_set_dt(&_sck, 0);
            k_usleep(1);
            raw = (raw << 1) | static_cast<uint32_t>(gpio_pin_get_dt(&_dout));
        }
        raw >>= num_pulses - 24;
        if (raw & 0x800000u)
            return static_cast<int32_t>(raw) - 0x1000000;
        return static_cast<int32_t>(raw);
    }

    /** @brief Enter power-down mode by holding PD_SCK HIGH for >60 µs. */
    void power_down() {
        gpio_pin_set_dt(&_sck, 1);
        k_usleep(65);
    }

    /** @brief Exit power-down mode and reset the chip.
     *
     *  Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
     *  conversion after power-up must be discarded.
     */
    void power_up() {
        gpio_pin_set_dt(&_sck, 0);
    }

private:
    const struct gpio_dt_spec _dout;
    const struct gpio_dt_spec _sck;
    bool _enabled = true;
};
