#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief AHT10 temperature and humidity sensor — minimal interface.
 *
 * Provides temperature and humidity readings with no configuration beyond
 * the connection. Handles power-on wait, initialization (0xE1 calibration enable),
 * calibration check and measurement triggering automatically.
 *
 * Default configuration (baked in at construction):
 * - Measurement triggered on every read() call (no continuous mode)
 * - 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
 * - No CRC (the AHT10 does not send one)
 *
 * @param connection   Configured I²C connection pointing at the device (address 0x38).
 */
class AHT10Minimal {
public:
    AHT10Minimal(Connection& connection);

    /** @brief Trigger a measurement and return temperature in degrees Celsius.
     *  @return Temperature in °C (-50 to 150 °C).
     */
    float temperature();

    /** @brief Trigger a measurement and return relative humidity.
     *  @return Relative humidity in %RH (0 to 100 %RH).
     */
    float humidity();

    /** @brief Trigger a measurement and decode both values.
     *  @param[out] temperature_c  Temperature in °C.
     *  @param[out] humidity_pct   Relative humidity in %RH.
     *  @return false if BUSY did not clear within the 200 ms guard (outputs untouched).
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t CMD_INIT[3]     = { 0xE1, 0x08, 0x00 };
    static constexpr uint8_t CMD_TRIGGER[3]  = { 0xAC, 0x33, 0x00 };
    static constexpr uint8_t CMD_SOFT_RESET   = 0xBA;

    static constexpr uint8_t STATUS_BUSY = 0x80;
    static constexpr uint8_t STATUS_CAL  = 0x08;

    Connection& _connection;

    uint8_t _read_status();
    void    _initialize();
    bool    _measure(uint8_t* buf);
    static void _raw(const uint8_t* buf, uint32_t& raw_rh, uint32_t& raw_t);
    static void _convert(uint32_t raw_rh, uint32_t raw_t, float& temperature_c, float& humidity_pct);
};

/** @brief AHT10 full interface — extends AHT10Minimal with raw values, status and dew point.
 *
 * @param connection   Configured I²C connection pointing at the device (address 0x38).
 */
class AHT10Full : public AHT10Minimal {
public:
    AHT10Full(Connection& connection);

    /** @brief Trigger a measurement and return temperature only.
     *  @return Temperature in °C. */
    float read_temperature();

    /** @brief Trigger a measurement and return humidity only.
     *  @return Relative humidity in %RH. */
    float read_humidity();

    /** @brief Trigger a measurement and return the raw 20-bit values.
     *  @param[out] raw_humidity     Raw humidity (0..1048575).
     *  @param[out] raw_temperature  Raw temperature (0..1048575).
     *  @return false on BUSY timeout. */
    bool read_raw(uint32_t& raw_humidity, uint32_t& raw_temperature);

    /** @brief Read the status byte (bit 7 BUSY, bits 6:5 MODE, bit 3 CAL).
     *  @return Status byte. */
    uint8_t read_status();

    /** @brief Check if the calibration bit is set in the status byte.
     *  @return true if the sensor reports calibration enabled. */
    bool is_calibrated();

    /** @brief Check if the busy bit is set in the status byte.
     *  @return true if a measurement is in progress. */
    bool is_busy();

    /** @brief Send soft reset, wait 20 ms, and re-run the init command. */
    void soft_reset();

    /** @brief Trigger a measurement and return the dew point (Magnus formula).
     *  @return Dew point in °C; -50 if humidity is 0 %RH. */
    float dew_point();
};
