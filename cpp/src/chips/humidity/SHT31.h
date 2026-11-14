#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief SHT31 temperature and humidity sensor (Sensirion) — minimal interface.
 *
 * Each read() runs one single-shot measurement at high repeatability with clock
 * stretching disabled and CRC checking on. The heater stays off.
 *
 * Default configuration (baked in at construction):
 * - Soft reset (0x30A2) with 1 ms waits before and after
 * - Single shot, high repeatability (0x2400), fixed 15 ms wait; if the read header is
 *   still NACKed the driver keeps polling for up to 20 ms more
 * - Both CRC-8 bytes verified; one retry of the whole measurement on a mismatch
 *
 * @param connection Connection (I²C) pointing at the device (address 0x44 with ADDR low,
 *        0x45 with ADDR high). The constructor does not report errors; read() returns
 *        false when the chip does not answer.
 */
class SHT31Minimal {
public:
    SHT31Minimal(Connection& connection);

    /** @brief Run one single-shot measurement.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH.
     *  @return true on success; false if the chip did not answer or the CRC failed twice.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint16_t CMD_SOFT_RESET   = 0x30A2;
    static constexpr uint16_t CMD_HEATER_ON    = 0x306D;
    static constexpr uint16_t CMD_HEATER_OFF   = 0x3066;
    static constexpr uint16_t CMD_READ_STATUS  = 0xF32D;
    static constexpr uint16_t CMD_CLEAR_STATUS = 0x3041;
    static constexpr uint16_t CMD_FETCH        = 0xE000;
    static constexpr uint16_t CMD_BREAK        = 0x3093;
    static constexpr uint16_t CMD_ART          = 0x2B32;

    Connection& _connection;
    uint8_t _repeatability = 2;   // 0 low, 1 medium, 2 high
    bool    _stretch = false;

    void _command(uint16_t code);
    bool _single_shot(uint16_t& raw_t, uint16_t& raw_rh);
    static bool _decode(const uint8_t* b, uint16_t& raw_t, uint16_t& raw_rh);
    static void _convert(uint16_t raw_t, uint16_t raw_rh, float& t, float& rh);
    static uint8_t _crc8(const uint8_t* data, uint8_t len);
};

/** @brief SHT31 full interface — repeatability, clock stretching, periodic mode, heater and status.
 *
 * Extends SHT31Minimal with single values and raw words, the three repeatability levels,
 * the clock-stretching command set, periodic measurements (0.5-10 mps plus ART), the
 * heater, the status register, soft reset and the dew point.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x44 or 0x45).
 */
class SHT31Full : public SHT31Minimal {
public:
    static constexpr uint8_t REPEATABILITY_LOW    = 0;
    static constexpr uint8_t REPEATABILITY_MEDIUM = 1;
    static constexpr uint8_t REPEATABILITY_HIGH   = 2;

    SHT31Full(Connection& connection);

    /** @brief Run one measurement and return the temperature in °C (NAN on failure). */
    float read_temperature();

    /** @brief Run one measurement and return the relative humidity in %RH (NAN on failure). */
    float read_humidity();

    /** @brief Run one measurement and return the raw 16-bit words.
     *  @return true on success.
     */
    bool read_raw(uint16_t& raw_temperature, uint16_t& raw_humidity);

    /** @brief Select the repeatability for later single-shot reads (measurement time 4 / 6 / 15 ms).
     *  @param level REPEATABILITY_LOW, REPEATABILITY_MEDIUM or REPEATABILITY_HIGH.
     *  @return false if @p level is not one of them.
     */
    bool set_repeatability(uint8_t level);

    /** @brief Use the clock-stretching command set for single-shot reads (the bus layer must support it). */
    void set_clock_stretching(bool on);

    /** @brief Start periodic measurements; read results with fetch().
     *  @param rate         Measurements per second: 0.5, 1, 2, 4 or 10 (ignored with @p art).
     *  @param repeatability REPEATABILITY_* level.
     *  @param art          true for the accelerated response time mode (4 mps, command 0x2B32).
     *  @return false for an unsupported rate or repeatability.
     */
    bool start_periodic(float rate, uint8_t repeatability = REPEATABILITY_HIGH, bool art = false);

    /** @brief Read the latest periodic result (command 0xE000).
     *  @return true with the values set; false if no new data is available (read header NACKed) or the CRC failed.
     */
    bool fetch(float& temperature_c, float& humidity_pct);

    /** @brief Stop periodic mode (break command 0x3093) and wait 1 ms. */
    void stop_periodic();

    /** @brief Switch the on-chip heater (about 5-25 mW) on or off. */
    void set_heater(bool on);

    /** @brief Read the status register (command 0xF32D, CRC verified).
     *  @param[out] status 16-bit status, e.g. 0x8010 after reset; bit 13 = heater on.
     *  @return true on success.
     */
    bool read_status(uint16_t& status);

    /** @brief Clear the status register flags (command 0x3041). */
    void clear_status();

    /** @brief Soft reset (0x30A2), wait 1 ms; leaves periodic mode and returns to single shot. */
    void soft_reset();

    /** @brief Run one measurement and compute the dew point in °C (Magnus formula); NAN on failure. */
    float dew_point();
};
