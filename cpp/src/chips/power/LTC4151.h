#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

/** @brief LTC4151 7–80 V high-side current and voltage monitor — minimal interface.
 *
 * Provides supply voltage, load current and power with one call each. The chip
 * scans SENSE, VIN and ADIN continuously after power-up, so no configuration is
 * needed beyond the connection and the sense resistor; the constructor makes no
 * bus access.
 *
 * Default configuration (chip power-on state, not rewritten):
 * - CONTROL = 0x0C: continuous scan, page read/write and stuck-bus timer enabled
 * - 20 mΩ sense resistor (full-scale current 4.096 A)
 *
 * @param connection RegisterConnection (I²C or SMBus) pointing at the device (0x67–0x6F).
 * @param r_sense    Sense resistor in ohms (default 0.02; values <= 0 fall back to 0.02).
 */
class LTC4151Minimal {
public:
    explicit LTC4151Minimal(RegisterConnection& connection, float r_sense = 0.02f);

    /** @brief Read the supply voltage at VIN.
     *  @return VIN in volts (code × 25 mV).
     */
    float voltage();

    /** @brief Read the load current through the sense resistor.
     *  @return Current in amperes (sense voltage / r_sense).
     */
    float current();

    /** @brief Read the load power from one consistent block read of SENSE and VIN.
     *  @return Power in watts (VIN × current).
     */
    float power();

protected:
    static constexpr uint8_t REG_SENSE   = 0x00;
    static constexpr uint8_t REG_VIN     = 0x02;
    static constexpr uint8_t REG_ADIN    = 0x04;
    static constexpr uint8_t REG_CONTROL = 0x06;

    static constexpr float LSB_SENSE = 20e-6f;
    static constexpr float LSB_VIN   = 0.025f;
    static constexpr float LSB_ADIN  = 0.0005f;

    static uint16_t _code(uint8_t high, uint8_t low) {
        return (uint16_t)(((uint16_t)high << 4) | (low >> 4));
    }

    RegisterConnection& _connection;
    float _r_sense;
};

/** @brief LTC4151 full interface — extends LTC4151Minimal with all channels, snapshot mode and CONTROL access.
 *
 * Channel constants (pass to read_raw, snapshot, is_busy): SENSE = 0, VIN = 1, ADIN = 2.
 *
 * @param connection RegisterConnection (I²C or SMBus) pointing at the device (0x67–0x6F).
 * @param r_sense    Sense resistor in ohms (default 0.02; values <= 0 fall back to 0.02).
 */
class LTC4151Full : public LTC4151Minimal {
public:
    static constexpr uint8_t SENSE = 0, VIN = 1, ADIN = 2;

    /** @brief Result of read_all(). */
    struct Readings {
        float vin;       ///< Supply voltage in volts.
        float sense_v;   ///< Sense voltage in volts.
        float current;   ///< Load current in amperes.
        float power;     ///< Load power in watts.
        float adin;      ///< ADIN pin voltage in volts.
    };

    explicit LTC4151Full(RegisterConnection& connection, float r_sense = 0.02f);

    /** @brief Read the voltage across the sense resistor.
     *  @return SENSE+ − SENSE− in volts (code × 20 µV).
     */
    float sense_voltage();

    /** @brief Read the ADIN pin voltage.
     *  @return ADIN in volts (code × 0.5 mV).
     */
    float adin_voltage();

    /** @brief Read the supply voltage at VIN.
     *  @return VIN in volts (code × 25 mV).
     */
    float voltage();

    /** @brief Read the load current through the sense resistor.
     *  @return Current in amperes (sense voltage / r_sense).
     */
    float current();

    /** @brief Read the load power from one consistent block read of SENSE and VIN.
     *  @return Power in watts (VIN × current).
     */
    float power();

    /** @brief Read all three channels from one block read of registers 0x00–0x05.
     *  @param[out] out Receives VIN, sense voltage, current, power and ADIN.
     */
    void read_all(Readings& out);

    /** @brief Read the 12-bit code of a channel.
     *  @param channel SENSE (0), VIN (1) or ADIN (2).
     *  @return 12-bit conversion code, 0–4095 (0 for an invalid channel).
     */
    uint16_t read_raw(uint8_t channel);

    /** @brief Set the sense resistor value used for current and power.
     *  @param ohms Sense resistor in ohms; must be greater than 0.
     *  @return true if accepted, false if @p ohms <= 0 (value unchanged).
     */
    bool set_sense_resistor(float ohms);

    /** @brief Get the sense resistor value.
     *  @return Sense resistor in ohms.
     */
    float get_sense_resistor() const { return _r_sense; }

    /** @brief Run one snapshot conversion of a channel, then return to continuous scan.
     *
     *  Clears the snapshot bit first, then writes the channel with the snapshot bit
     *  set, polls the channel's BUSY bit (timeout 100 ms), reads the result and
     *  restores continuous scan.
     *  @param channel SENSE (0), VIN (1) or ADIN (2).
     *  @param[out] value Converted value in volts (sense voltage, VIN or ADIN).
     *  @return true on success; false on an invalid channel or a 100 ms timeout.
     */
    bool snapshot(uint8_t channel, float& value);

    /** @brief Switch to continuous scan mode (snapshot bit cleared). */
    void set_continuous();

    /** @brief Read the snapshot bit of CONTROL.
     *  @return true if the chip is in snapshot mode.
     */
    bool is_snapshot_mode();

    /** @brief Read the BUSY bit of a channel (snapshot mode).
     *  @param channel SENSE (0), VIN (1) or ADIN (2).
     *  @return true while that channel is being converted.
     */
    bool is_busy(uint8_t channel);

    /** @brief Enable or disable the stuck-bus reset timer (CONTROL.G2, read-modify-write).
     *  @param enabled true to enable (power-on default).
     */
    void set_stuck_bus_timer(bool enabled);

    /** @brief Enable or disable I²C page read/write (CONTROL.G3, read-modify-write).
     *
     *  When disabled the chip only supports byte/word transfers, so this driver
     *  then reads each register separately.
     *  @param enabled true to enable (power-on default).
     */
    void set_page_rw(bool enabled);

    /** @brief Read the CONTROL register.
     *  @return Raw CONTROL value (power-on default 0x0C).
     */
    uint8_t read_control();

    /** @brief Write the CONTROL register. Test mode (bit 4) is always masked to 0.
     *  @param value Raw CONTROL value.
     */
    void write_control(uint8_t value);

    /** @brief Convert an ADIN/VIN code pair to temperature (datasheet NTC example circuit).
     *
     *  Valid for roughly 20–60 °C and only for the datasheet circuit.
     *  @param adin_code 12-bit ADIN code.
     *  @param vin_code  12-bit VIN code (non-zero).
     *  @return Temperature in °C, 58.82 × (adin_code / vin_code − 0.1066).
     */
    static float temperature_from_adin(uint16_t adin_code, uint16_t vin_code);

private:
    static constexpr uint8_t CTRL_SNAPSHOT  = 0x80;
    static constexpr uint8_t CTRL_TEST      = 0x10;
    static constexpr uint8_t CTRL_PAGE_RW   = 0x08;
    static constexpr uint8_t CTRL_STUCK_BUS = 0x04;

    bool _page_rw;

    void _read_block(uint8_t reg, uint8_t* buf, uint8_t len);
    uint8_t _read_control_raw();
    void _write_control_raw(uint8_t value);
};
