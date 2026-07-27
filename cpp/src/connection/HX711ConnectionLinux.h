#pragma once
#ifdef __linux__
#include <stdint.h>

struct gpiod_line;

/** @brief HX711 GPIO bit-bang connection for Linux (wraps libgpiod lines).
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * The DOUT poll loop sleeps 1 ms between checks to avoid busy-waiting a
 * CPU core.
 *
 * This is a custom protocol with no generic byte read/write, so it does not
 * extend the shared Connection base — it carries its own enabled flag
 * directly, gating read_raw().
 *
 * @param dout   libgpiod line requested as input.
 * @param pd_sck libgpiod line requested as output.
 */
class HX711ConnectionLinux {
public:
    HX711ConnectionLinux(struct gpiod_line* dout, struct gpiod_line* pd_sck);
    ~HX711ConnectionLinux();

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
    bool is_ready();

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Waits up to 1 second for DOUT to go LOW (conversion ready), then sends
     *  exactly num_pulses PD_SCK pulses and samples DOUT at each falling edge
     *  (HIGH→LOW transition). Leaves PD_SCK LOW after the last pulse. The
     *  pulse count programs the channel and gain for the next conversion:
     *  25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
     *  Returns 0 without touching the bus if this connection is disabled.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value, 0 if disabled, or INT32_MIN on timeout/error.
     */
    int32_t read_raw(uint8_t num_pulses = 25);

    /** @brief Enter power-down mode by holding PD_SCK HIGH for >60 µs. */
    void power_down();

    /** @brief Exit power-down mode and reset the chip.
     *
     *  Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
     *  conversion after power-up must be discarded.
     */
    void power_up();

    /** @brief Release both GPIO lines back to the kernel. */
    void close();

private:
    struct gpiod_line* _dout;
    struct gpiod_line* _sck;
    bool _enabled = true;
};
#endif
