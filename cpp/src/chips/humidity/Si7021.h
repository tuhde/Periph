#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief Si7021 humidity and temperature sensor (Silicon Labs) — minimal interface.
 *
 * Each read() runs one relative-humidity measurement without hold master (the read header is
 * polled until the chip ACKs, timeout 50 ms), verifies the CRC and then fetches the temperature
 * of that same measurement with command 0xE0.
 *
 * Default configuration (power-on, nothing is written at construction):
 * - 12-bit RH / 14-bit temperature, heater off
 * - No-hold-master measurement (0xF5), polling the NACKed read header
 * - CRC-8 verified on the RH result; relative humidity clamped to 0-100 %RH
 *
 * @param connection Connection (I²C) pointing at the device (address 0x40). The constructor
 *        does no bus access; read() returns false when the chip does not answer.
 */
class Si7021Minimal {
public:
    Si7021Minimal(Connection& connection);

    /** @brief Run one RH measurement and read the temperature of the same conversion.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH (0-100).
     *  @return true on success; false if the chip did not answer within 50 ms or the CRC failed.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t CMD_RH_HOLD      = 0xE5;
    static constexpr uint8_t CMD_RH_POLL      = 0xF5;
    static constexpr uint8_t CMD_T_HOLD       = 0xE3;
    static constexpr uint8_t CMD_T_POLL       = 0xF3;
    static constexpr uint8_t CMD_T_PREVIOUS   = 0xE0;
    static constexpr uint8_t CMD_RESET        = 0xFE;
    static constexpr uint8_t CMD_WRITE_USER   = 0xE6;
    static constexpr uint8_t CMD_READ_USER    = 0xE7;

    Connection& _connection;
    bool _stretch = false;

    void _command(uint8_t code);
    bool _measure(uint8_t hold_cmd, uint8_t poll_cmd, uint16_t& code);
    bool _previous_code(uint16_t& code);
    static float _to_humidity(uint16_t code);
    static float _to_temperature(uint16_t code);
    static uint8_t _crc8(const uint8_t* data, uint8_t len);
};

/** @brief Si7021 full interface — resolution, heater, hold master, ID and dew point.
 *
 * Extends Si7021Minimal with single values and raw codes, the four resolution pairs, the
 * on-chip heater, the raw user register, clock stretching (hold master), software reset,
 * electronic serial number, firmware revision, conversion time and the dew point.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x40).
 */
class Si7021Full : public Si7021Minimal {
public:
    Si7021Full(Connection& connection);

    /** @brief Run one RH measurement and return the relative humidity in %RH (NAN on failure). */
    float read_humidity();

    /** @brief Run a fresh temperature measurement (0xF3, or 0xE3 with clock stretching); °C, NAN on failure. */
    float read_temperature();

    /** @brief Temperature of the last RH measurement (0xE0, no new conversion, no CRC); °C, NAN on failure. */
    float read_temperature_previous();

    /** @brief Run one RH measurement and return the raw 16-bit code (status bits included).
     *  @return true on success.
     */
    bool read_raw_humidity(uint16_t& code);

    /** @brief Run one temperature measurement and return the raw 16-bit code.
     *  @return true on success.
     */
    bool read_raw_temperature(uint16_t& code);

    /** @brief Select the measurement resolution (read-modify-write of RES1:RES0).
     *  @param rh_bits          RH resolution in bits.
     *  @param temperature_bits Temperature resolution in bits.
     *  @return false if the pair is not one of (12, 14), (8, 12), (10, 13), (11, 11).
     */
    bool set_resolution(uint8_t rh_bits, uint8_t temperature_bits);

    /** @brief Read the configured resolution. */
    void get_resolution(uint8_t& rh_bits, uint8_t& temperature_bits);

    /** @brief Switch the on-chip heater (HTRE, about 3.1 mA) on or off. */
    void set_heater(bool on);

    /** @brief True if the on-chip heater is enabled. */
    bool is_heater_on();

    /** @brief True if the supply voltage is low (VDDS set, VDD below about 1.9 V). */
    bool vdd_low();

    /** @brief Read USER_REG_1 (command 0xE7); 0x3A after reset. */
    uint8_t read_user_register();

    /** @brief Write USER_REG_1 (command 0xE6); the reserved bits and VDDS keep their current value. */
    void write_user_register(uint8_t value);

    /** @brief Use the hold-master commands (0xE5 / 0xE3) instead of polling (the bus layer must support clock stretching). */
    void set_clock_stretching(bool on);

    /** @brief Software reset (0xFE) and wait 15 ms; the user register returns to 0x3A. */
    void software_reset();

    /** @brief Read the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0). */
    uint64_t serial_number();

    /** @brief Read SNB_3, the device type byte (0x15 = Si7021, 0x14 = Si7020, 0x0D = Si7013). */
    uint8_t device_type();

    /** @brief Read the firmware revision (command 0x84 0xB8); 0xFF = firmware 1.0. */
    uint8_t firmware_revision();

    /** @brief Typical RH + temperature conversion time at the current resolution, in seconds. */
    float conversion_time();

    /** @brief Run one measurement and compute the dew point in °C (Magnus formula); NAN on failure. */
    float dew_point();
};
