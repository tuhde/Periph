#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief AHT21 temperature and humidity sensor — minimal interface.
 *
 * Provides temperature and humidity readings with no configuration beyond
 * the transport. Handles power-on initialization, calibration check, and
 * measurement triggering automatically.
 *
 * Default configuration (baked in at construction):
 * - Measurement triggered on every read() call (no continuous mode)
 * - 80 ms fixed wait after trigger (no busy-polling)
 * - No CRC verification (reduces complexity; CRC check is Full-only)
 *
 * @param transport   Configured I²C transport pointing at the device (address 0x38).
 */
class AHT21Minimal {
public:
    AHT21Minimal(Transport& transport);

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
     */
    void read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t CMD_TRIGGER[3]  = { 0xAC, 0x33, 0x00 };
    static constexpr uint8_t CMD_SOFT_RESET   = 0xBA;
    static constexpr uint8_t CMD_CAL_INIT_1[3] = { 0x1B, 0x00, 0x00 };
    static constexpr uint8_t CMD_CAL_INIT_2[3] = { 0x1C, 0x00, 0x00 };
    static constexpr uint8_t CMD_CAL_INIT_3[3] = { 0x1E, 0x00, 0x00 };

    static constexpr uint8_t STATUS_BUSY = 0x80;
    static constexpr uint8_t STATUS_CAL  = 0x08;

    Transport& _transport;

    uint8_t _read_status();
    void    _read_raw(uint8_t* buf, uint8_t len);
    void    _decode(const uint8_t* buf, float& temperature_c, float& humidity_pct);
};

/** @brief AHT21 full interface — extends AHT21Minimal with CRC and status support.
 *
 * Adds CRC-8 verification, explicit soft reset, calibration status inspection,
 * and individual temperature/humidity readings.
 *
 * @param transport   Configured I²C transport pointing at the device (address 0x38).
 */
class AHT21Full : public AHT21Minimal {
public:
    AHT21Full(Transport& transport);

    /** @brief Trigger a measurement, read 7 bytes, and verify CRC-8.
     *  @param[out] temperature_c  Temperature in °C.
     *  @param[out] humidity_pct   Relative humidity in %RH.
     *  @return true if CRC-8 verification passed.
     */
    bool read_with_crc(float& temperature_c, float& humidity_pct);

    /** @brief Send the soft reset command and wait 20 ms for recovery. */
    void soft_reset();

    /** @brief Check if the calibration bit is set in the status byte.
     *  @return true if the sensor reports calibration enabled.
     */
    bool is_calibrated();

    /** @brief Check if the busy bit is set in the status byte.
     *  @return true if a measurement is in progress.
     */
    bool is_busy();

private:
    static uint8_t _crc8(const uint8_t* data, uint8_t len);
};
