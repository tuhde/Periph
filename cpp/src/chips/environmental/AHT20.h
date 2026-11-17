#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief AHT20 temperature and humidity sensor — minimal interface.
 *
 * Provides temperature and humidity readings with no configuration beyond
 * the connection. Handles power-on wait, calibration check (soft reset and
 * calibration register init if needed) and measurement triggering automatically.
 * The constructor cannot report failure; AHT20Full::is_calibrated() exposes it.
 *
 * Default configuration (baked in at construction):
 * - Measurement triggered on every read() call (no continuous mode)
 * - 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
 * - No CRC verification (CRC check is Full-only)
 *
 * @param connection   Configured I²C connection pointing at the device (address 0x38).
 */
class AHT20Minimal {
public:
    AHT20Minimal(Connection& connection);

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
    static constexpr uint8_t CMD_STATUS       = 0x71;
    static constexpr uint8_t CMD_TRIGGER[3]  = { 0xAC, 0x33, 0x00 };
    static constexpr uint8_t CMD_SOFT_RESET   = 0xBA;
    static constexpr uint8_t CMD_CAL_INIT[3][3] = { { 0x1B, 0x00, 0x00 }, { 0x1C, 0x00, 0x00 }, { 0x1E, 0x00, 0x00 } };

    static constexpr uint8_t STATUS_BUSY = 0x80;
    static constexpr uint8_t STATUS_CAL  = 0x08;
    static constexpr uint8_t STATUS_READY = 0x18;

    Connection& _connection;

    uint8_t _read_status();
    bool    _ready();
    void    _calibrate();
    bool    _measure(uint8_t* buf, uint8_t length = 6);
    static void _raw(const uint8_t* buf, uint32_t& raw_rh, uint32_t& raw_t);
    static void _convert(uint32_t raw_rh, uint32_t raw_t, float& temperature_c, float& humidity_pct);
};

/** @brief AHT20 full interface — extends AHT20Minimal with CRC, raw values, status and dew point.
 *
 * @param connection   Configured I²C connection pointing at the device (address 0x38).
 */
class AHT20Full : public AHT20Minimal {
public:
    AHT20Full(Connection& connection);

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

    /** @brief Trigger a measurement, read 7 bytes and verify the CRC-8
     *  (polynomial 0x31, initial value 0xFF, over bytes 0 to 5, compared with byte 6).
     *  @param[out] temperature_c  Temperature in °C.
     *  @param[out] humidity_pct   Relative humidity in %RH.
     *  @param[out] crc_ok         true if the CRC matches.
     *  @return false on BUSY timeout. */
    bool read_with_crc(float& temperature_c, float& humidity_pct, bool& crc_ok);

    /** @brief CRC-8 (polynomial 0x31, initial value 0xFF).
     *  @param data    Bytes to checksum.
     *  @param length  Number of bytes.
     *  @return CRC-8 value. */
    static uint8_t crc8(const uint8_t* data, uint8_t length);

    /** @brief Read the status byte (bit 7 BUSY, bit 3 CAL).
     *  @return Status byte. */
    uint8_t read_status();

    /** @brief Check if the calibration bit is set in the status byte.
     *  @return true if the sensor reports calibration enabled. */
    bool is_calibrated();

    /** @brief Check if the busy bit is set in the status byte.
     *  @return true if a measurement is in progress. */
    bool is_busy();

    /** @brief Send soft reset, wait 20 ms, and re-run the calibration check. */
    void soft_reset();

    /** @brief Trigger a measurement and return the dew point (Magnus formula).
     *  @return Dew point in °C; -50 if humidity is 0 %RH. */
    float dew_point();
};
