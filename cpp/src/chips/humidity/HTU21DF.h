#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief HTU21D-F humidity and temperature sensor (Measurement Specialties) — minimal interface.
 *
 * Each read() triggers one temperature (0xF3) and one humidity (0xF5) measurement without hold
 * master, polls the NACKed read header until the chip ACKs (timeout 100 ms) and verifies the
 * CRC of both results (one retry on a mismatch).
 *
 * Default configuration (power-on): 12-bit RH / 14-bit temperature, heater off, no hold master,
 * CRC checked.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x40). The constructor
 *        does no bus access; init() performs the soft reset.
 */
class HTU21DFMinimal {
public:
    HTU21DFMinimal(Connection& connection);

    /** @brief Wait 15 ms for power-up, soft reset (0xFE) and wait 15 ms. */
    void init();

    /** @brief Measure temperature and humidity.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH (not clamped).
     *  @return true on success; false if the chip did not answer within 100 ms or a CRC failed twice.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t CMD_T_HOLD     = 0xE3;
    static constexpr uint8_t CMD_RH_HOLD    = 0xE5;
    static constexpr uint8_t CMD_T_POLL     = 0xF3;
    static constexpr uint8_t CMD_RH_POLL    = 0xF5;
    static constexpr uint8_t CMD_WRITE_USER = 0xE6;
    static constexpr uint8_t CMD_READ_USER  = 0xE7;
    static constexpr uint8_t CMD_RESET      = 0xFE;

    Connection& _connection;
    bool _hold = false;

    void _command(uint8_t code);
    bool _measure_once(uint8_t hold_cmd, uint8_t poll_cmd, uint8_t* buf);
    bool _measure(uint8_t hold_cmd, uint8_t poll_cmd, uint16_t& code);
    static float _to_humidity(uint16_t code);
    static float _to_temperature(uint16_t code);
    static uint8_t _crc8(const uint8_t* data, uint8_t len);
};

/** @brief HTU21D-F full interface — resolution, heater, hold master, compensation, dew point.
 *
 * Extends HTU21DFMinimal with single values, raw codes, the four resolution settings, the
 * on-chip heater, the end-of-battery flag, the raw user register, soft reset, hold-master mode,
 * temperature-compensated humidity and the dew point.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x40).
 */
class HTU21DFFull : public HTU21DFMinimal {
public:
    HTU21DFFull(Connection& connection);

    /** @brief Run one temperature measurement; °C, NAN on failure. */
    float read_temperature();

    /** @brief Run one humidity measurement; %RH (not clamped), NAN on failure. */
    float read_humidity();

    /** @brief Humidity corrected for temperature (-0.15 %RH/°C relative to 25 °C); NAN on failure. */
    float read_humidity_compensated();

    /** @brief Measure and return the raw 16-bit codes (status bits cleared).
     *  @return true on success.
     */
    bool read_raw(uint16_t& temperature_raw, uint16_t& humidity_raw);

    /** @brief Select the resolution (read-modify-write of the RES bits).
     *  @param res 0 = 12/14 bit RH/T, 1 = 8/12, 2 = 10/13, 3 = 11/11.
     *  @return false if res is not 0-3.
     */
    bool set_resolution(uint8_t res);

    /** @brief Read the resolution setting (0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11). */
    uint8_t get_resolution();

    /** @brief Switch the on-chip heater on or off (read-modify-write). */
    void set_heater(bool on);

    /** @brief True if the on-chip heater is enabled. */
    bool heater();

    /** @brief True if the end-of-battery flag is set (VDD below about 2.25 V). */
    bool battery_low();

    /** @brief Read the user register (command 0xE7); 0x02 after reset. */
    uint8_t read_user_register();

    /** @brief Soft reset (0xFE) and wait 15 ms; the heater bit survives. */
    void soft_reset();

    /** @brief Use the hold-master commands (0xE3 / 0xE5) instead of polling (the bus layer must support clock stretching). */
    void set_hold_master(bool on);

    /** @brief Run one measurement and compute the dew point in °C; NAN on failure. */
    float dew_point();
};
