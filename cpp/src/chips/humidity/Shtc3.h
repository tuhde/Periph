#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief SHTC3 humidity and temperature sensor (Sensirion) — minimal interface.
 *
 * Each read() runs a full measurement cycle: wake-up (0x3517), a normal-mode temperature-first
 * measurement without clock stretching (0x7866), a 13 ms wait, a 6-byte read with both CRC-8
 * bytes verified, and the sleep command (0xB098). A bad CRC is retried once.
 *
 * The device is command driven (no register pointer), so a plain Connection is used.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x70). The constructor does
 *        no bus access; init() performs the soft reset and the ID check.
 */
class SHTC3Minimal {
public:
    SHTC3Minimal(Connection& connection);

    /** @brief Wake, soft reset (0x805D), check the product ID, then sleep.
     *  @return true on success; false on a bus error, CRC mismatch or a wrong product code.
     */
    bool init();

    /** @brief Measure temperature and humidity.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH (clamped to 0-100).
     *  @return true on success; false if a bus transfer failed or a CRC did not match twice.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint16_t CMD_SLEEP   = 0xB098;
    static constexpr uint16_t CMD_WAKE    = 0x3517;
    static constexpr uint16_t CMD_RESET   = 0x805D;
    static constexpr uint16_t CMD_READ_ID = 0xEFC8;

    Connection& _connection;
    bool _low_power = false;
    bool _stretching = false;
    bool _sleep_between = true;
    bool _asleep = false;

    void _command(uint16_t code);
    void _wake();
    void _sleep();
    bool _measure(uint16_t& t_raw, uint16_t& rh_raw);
    static float _to_humidity(uint16_t code);
    static float _to_temperature(uint16_t code);
    static uint8_t _crc8(const uint8_t* data, uint8_t len);
};

/** @brief SHTC3 full interface — single values, raw codes, low-power mode, clock stretching, sleep control, ID, dew point.
 *
 * Extends SHTC3Minimal with single values, raw codes, the low-power measurement mode, the
 * clock-stretching command set, sleep control, soft reset, the product ID and the dew point.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x70).
 */
class SHTC3Full : public SHTC3Minimal {
public:
    SHTC3Full(Connection& connection);

    /** @brief Run one measurement cycle and return the temperature in °C; NAN on failure. */
    float read_temperature();

    /** @brief Run one measurement cycle and return the humidity in %RH (0-100); NAN on failure. */
    float read_humidity();

    /** @brief Measure and return the raw 16-bit codes.
     *  @return true on success.
     */
    bool read_raw(uint16_t& t_raw, uint16_t& rh_raw);

    /** @brief Use the low-power (0.7 ms, waits 1 ms) or the normal (10.8 ms, waits 13 ms) measurement commands. */
    void set_low_power(bool on);

    /** @brief Use the clock-stretching command set; the fixed wait is skipped (needs a controller that tolerates SCL held low). */
    void set_clock_stretching(bool on);

    /** @brief Choose whether a read ends with the sleep command (default on). */
    void set_sleep_between(bool on);

    /** @brief Send the wake-up command (0x3517) and wait 1 ms. */
    void wake();

    /** @brief Send the sleep command (0xB098). */
    void sleep();

    /** @brief Soft reset (0x805D), 1 ms wait; leaves the sensor idle. */
    void soft_reset();

    /** @brief Read the 16-bit ID word (command 0xEFC8, CRC verified); (id & 0x083F) == 0x0807 for the SHTC3.
     *  A sleeping sensor is woken for the read and put back to sleep.
     *  @return true on success; false on a bus error or CRC mismatch.
     */
    bool read_id(uint16_t& id);

    /** @brief Run one measurement and compute the dew point (Magnus formula) in °C; NAN on failure. */
    float dew_point();
};
