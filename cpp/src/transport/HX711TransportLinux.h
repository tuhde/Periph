#pragma once
#ifdef __linux__
#include <stdint.h>

struct gpiod_line;

/** @brief HX711 GPIO bit-bang transport for Linux (wraps libgpiod lines).
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * The DOUT poll loop sleeps 1 ms between checks to avoid busy-waiting a
 * CPU core.
 *
 * @param dout   libgpiod line requested as input.
 * @param pd_sck libgpiod line requested as output.
 */
class HX711TransportLinux {
public:
    HX711TransportLinux(struct gpiod_line* dout, struct gpiod_line* pd_sck);
    ~HX711TransportLinux();

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     *
     *  @return true when DOUT is LOW (data ready).
     */
    bool is_ready();

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Polls DOUT (with 1 ms sleep between checks) until LOW (conversion ready),
     *  then sends exactly num_pulses pulses on PD_SCK, sampling DOUT at each
     *  falling edge (HIGH→LOW transition). Leaves PD_SCK LOW after the last
     *  pulse. The pulse count programs the channel and gain for the next
     *  conversion: 25 → Channel A Gain 128, 26 → Channel B Gain 32,
     *  27 → Channel A Gain 64.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value, or INT32_MIN on timeout/error.
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
};
#endif // __linux__
