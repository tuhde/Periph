#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"

/** @brief PCF8591 8-bit quad ADC + DAC — minimal interface.
 *
 * Provides single-ended reads of the four analog inputs in 4 single-ended
 * mode (AIP=00). No configuration beyond the transport is required. Each
 * read transaction returns 5 bytes: the first is the previous conversion
 * result and must be discarded; the next four are fresh channel samples.
 *
 * @param transport Configured I²C transport pointing at the device (0x48–0x4F).
 */
class PCF8591Minimal {
public:
    static constexpr uint8_t NUM_CHANNELS = 4;
    static constexpr uint8_t CONTROL_DEFAULT = 0x00;  // AIP=00, AOE=0, AI=0, CHN=0

    /** @brief Construct the driver and store the transport.
     *  @param transport Configured I²C transport pointing at the device.
     */
    explicit PCF8591Minimal(Transport& transport);

    /** @brief Read a single channel as an unsigned 8-bit value.
     *
     *  Uses single-shot conversion: writes the control byte selecting the
     *  channel, then reads 2 bytes (discarding the stale first byte).
     *
     *  @param channel Channel number 0–3. Clamped to the valid range.
     *  @return Raw 8-bit value (0–255).
     */
    uint8_t read_channel(uint8_t channel);

    /** @brief Read all four channels as unsigned 8-bit values.
     *
     *  Uses auto-increment (AI=1) to read all four channels in one
     *  transaction. Reads 5 bytes and discards the stale first byte.
     *
     *  @param out Output buffer; must be at least 4 bytes. Filled with
     *             [ch0, ch1, ch2, ch3].
     */
    void read_all(uint8_t* out);

protected:
    Transport& _transport;
};

/** @brief PCF8591 full interface — extends PCF8591Minimal with differential, voltage, and DAC output.
 *
 * Adds analog input mode selection (single-ended, differential, mixed),
 * auto-increment, DAC enable/disable, raw and voltage-calibrated ADC reads,
 * and signed differential reads.
 *
 * @param transport Configured I²C transport pointing at the device (0x48–0x4F).
 */
class PCF8591Full : public PCF8591Minimal {
public:
    static constexpr uint8_t MODE_4_SINGLE_ENDED  = 0;  // 4 single-ended inputs (AIN0–AIN3)
    static constexpr uint8_t MODE_3_DIFFERENTIAL = 1;  // 3 differential inputs (vs AIN3)
    static constexpr uint8_t MODE_MIXED          = 2;  // AIN0/1 single-ended, AIN2-AIN3 differential
    static constexpr uint8_t MODE_2_DIFFERENTIAL = 3;  // 2 differential inputs

    /** @brief Construct the full driver and store the transport.
     *  @param transport Configured I²C transport pointing at the device.
     */
    explicit PCF8591Full(Transport& transport);

    /** @brief Set the analog input mode, auto-increment, and DAC enable.
     *  @param input_mode Analog input programming 0–3 (see MODE_* constants).
     *  @param auto_increment If true, AI=1 — channel increments after each conversion.
     *  @param dac_enabled If true, AOE=1 — AOUT is active; AOUT returns to
     *                     high-impedance when false.
     */
    void configure(uint8_t input_mode, bool auto_increment, bool dac_enabled);

    /** @brief Read a single channel and convert to voltage.
     *  @param channel Channel number 0–3.
     *  @param vref Reference voltage in volts.
     *  @param vagnd Analog ground voltage in volts.
     *  @return Channel voltage in volts.
     */
    float read_channel_voltage(uint8_t channel, float vref, float vagnd);

    /** @brief Read all four channels and convert each to voltage.
     *  @param out Output buffer; must be at least 4 floats. Filled with
     *             [ch0, ch1, ch2, ch3] voltages.
     *  @param vref Reference voltage in volts.
     *  @param vagnd Analog ground voltage in volts.
     */
    void read_all_voltage(float* out, float vref, float vagnd);

    /** @brief Read a differential channel as a signed value.
     *
     *  The chip must be configured in a differential mode (input_mode 1, 2,
     *  or 3). The result is interpreted as a signed 8-bit two's complement
     *  number.
     *
     *  @param channel Differential channel index (0–2 for 3-diff mode, 0–1
     *                  for 2-diff and mixed modes).
     *  @return Signed 8-bit value (-128 to 127).
     */
    int8_t read_differential(uint8_t channel);

    /** @brief Enable the DAC and write a raw 8-bit value.
     *
     *  Sets the AOE bit so AOUT becomes active, then writes the DAC value
     *  in the byte following the control byte.
     *
     *  @param value Raw 8-bit DAC value (0–255). Output voltage is
     *               V_AGND + value × (V_REF − V_AGND) / 256.
     */
    void set_dac(uint8_t value);

    /** @brief Enable the DAC and set the output as a fraction of (VREF−VAGND).
     *  @param voltage_fraction Output level as a fraction of (VREF−VAGND)
     *                          (0.0 = V_AGND, 1.0 = V_REF). Clamped to [0.0, 1.0].
     */
    void set_dac_voltage(float voltage_fraction);

    /** @brief Disable the DAC output; AOUT returns to high-impedance. */
    void disable_dac();

private:
    uint8_t _control;
    uint8_t _input_mode;
    bool    _dac_enabled;
    bool    _auto_increment;
    uint8_t _last_channel;

    int8_t _read_signed_byte(uint8_t ctrl);
};
