#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief MCP4725 single-channel 12-bit voltage-output DAC — minimal interface.
 *
 * Provides simple voltage output as a fraction of V_DD with no configuration
 * beyond the transport. Uses Fast Write (2-byte) for DAC register updates.
 *
 * @param transport Configured I²C transport pointing at the device (0x60–0x61).
 */
class MCP4725Minimal {
public:
    explicit MCP4725Minimal(Transport& transport);

    /** @brief Set the DAC output as a fraction of V_DD.
     *  @param fraction Output voltage as a fraction of V_DD (0.0–1.0).
     */
    void set_voltage(float fraction);

    /** @brief Set the raw 12-bit DAC code directly.
     *  @param code Raw 12-bit DAC code (0–4095).
     */
    void set_raw(uint16_t code);

protected:
    static constexpr uint8_t CMD_FAST_WRITE = 0x00;

    Transport& _transport;

    void _fast_write(uint16_t code, uint8_t pd_mode);
};

/** @brief MCP4725 full interface — extends MCP4725Minimal with EEPROM, power-down, and read-back.
 *
 * Adds write-with-EEPROM persistence, power-down modes, General Call reset/wake,
 * and full register read-back of both DAC and EEPROM contents.
 *
 * @param transport Configured I²C transport pointing at the device (0x60–0x61).
 */
class MCP4725Full : public MCP4725Minimal {
public:
    static constexpr uint8_t PD_NORMAL  = 0;
    static constexpr uint8_t PD_1K_GND  = 1;
    static constexpr uint8_t PD_100K_GND = 2;
    static constexpr uint8_t PD_500K_GND = 3;

    explicit MCP4725Full(Transport& transport);

    /** @brief Set the DAC output and persist to EEPROM.
     *  @param fraction Output voltage as a fraction of V_DD (0.0–1.0).
     */
    void set_voltage_eeprom(float fraction);

    /** @brief Set the raw 12-bit DAC code and persist to EEPROM.
     *  @param code Raw 12-bit DAC code (0–4095).
     */
    void set_raw_eeprom(uint16_t code);

    /** @brief Read the current DAC register and EEPROM contents.
     *  @return struct with code, voltage_fraction, power_down, eeprom_code,
     *          eeprom_power_down, eeprom_ready.
     */
    struct ReadResult {
        uint16_t code;
        float    voltage_fraction;
        uint8_t  power_down;
        uint16_t eeprom_code;
        uint8_t  eeprom_power_down;
        bool     eeprom_ready;
    };
    ReadResult read();

    /** @brief Set the power-down mode and preserve the current DAC code.
     *  @param mode Power-down mode 0–3 (0 = normal, 1 = 1 kΩ to GND,
     *              2 = 100 kΩ to GND, 3 = 500 kΩ to GND).
     */
    void set_power_down(uint8_t mode);

    /** @brief Send General Call Wake-Up (0x00, 0x09) to clear power-down bits. */
    void wake_up();

    /** @brief Send General Call Reset (0x00, 0x06) to trigger internal POR. */
    void reset();

    /** @brief Check if the EEPROM write operation is complete.
     *  @return true when a pending EEPROM write has finished.
     */
    bool is_eeprom_ready();

private:
    static constexpr uint8_t CMD_WRITE_DAC_EEPROM = 0x60;
    static constexpr uint8_t ADDR_GENERAL_CALL    = 0x00;
    static constexpr uint8_t GC_RESET = 0x06;
    static constexpr uint8_t GC_WAKE  = 0x09;

    void _write_dac_eeprom(uint16_t code, uint8_t pd_mode);
    uint16_t _read_dac_code();
};