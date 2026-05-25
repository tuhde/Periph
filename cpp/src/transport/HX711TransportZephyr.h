#pragma once
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>

/** @brief HX711 GPIO bit-bang transport for Zephyr RTOS.
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each rising edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * prj.conf must enable CONFIG_GPIO=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 *
 * @param dout   gpio_dt_spec for the DOUT pin (GPIO_INPUT).
 * @param pd_sck gpio_dt_spec for the PD_SCK pin (GPIO_OUTPUT_LOW).
 */
class HX711TransportZephyr {
public:
    HX711TransportZephyr(const struct gpio_dt_spec& dout,
                         const struct gpio_dt_spec& pd_sck)
        : _dout(dout), _sck(pd_sck)
    {
        gpio_pin_configure_dt(&_dout, GPIO_INPUT);
        gpio_pin_configure_dt(&_sck,  GPIO_OUTPUT_LOW);
    }

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     *
     *  @return true when DOUT is LOW (data ready).
     */
    bool is_ready() {
        return gpio_pin_get_dt(&_dout) == 0;
    }

    /** @brief Block until data is ready, then clock out a conversion.
     *
     *  Sends exactly num_pulses rising edges on PD_SCK and samples DOUT on
     *  each one. The pulse count programs the channel and gain for the next
     *  conversion: 25 → Channel A Gain 128, 26 → Channel B Gain 32,
     *  27 → Channel A Gain 64.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value.
     */
    int32_t read_raw(uint8_t num_pulses = 25) {
        while (gpio_pin_get_dt(&_dout) != 0) {}
        uint32_t raw = 0;
        for (uint8_t i = 0; i < num_pulses; i++) {
            gpio_pin_set_dt(&_sck, 1);
            raw = (raw << 1) | static_cast<uint32_t>(gpio_pin_get_dt(&_dout));
            gpio_pin_set_dt(&_sck, 0);
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
};
