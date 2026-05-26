#pragma once
#include <Arduino.h>

/** @brief HX711 GPIO bit-bang transport for Arduino.
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * @param dout_pin   Arduino pin number for DOUT (input from chip).
 * @param pd_sck_pin Arduino pin number for PD_SCK (clock / power-down output).
 */
class HX711Transport {
public:
    HX711Transport(int dout_pin, int pd_sck_pin);

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     *
     *  @return true when DOUT is LOW (data ready).
     */
    bool is_ready();

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Polls DOUT until LOW (conversion ready), then sends exactly num_pulses
     *  pulses on PD_SCK, sampling DOUT at each falling edge (HIGH→LOW
     *  transition). Leaves PD_SCK LOW after the last pulse. The pulse count
     *  programs the channel and gain for the next conversion:
     *  25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
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

private:
    int _dout;
    int _sck;
};
