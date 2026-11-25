#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief HS30xx humidity and temperature sensor (Renesas HS3001 / HS3002 / HS3003) — minimal interface.
 *
 * The three parts share one protocol and differ only in accuracy class and housing, so
 * HS3001Minimal, HS3002Minimal and HS3003Minimal are aliases of this class.
 *
 * Each read() sends a measurement request (an address-only write), waits 40 ms, fetches four
 * bytes and converts them. If the status bits report stale data it waits 10 ms and fetches once
 * more.
 *
 * Default configuration (factory): 14-bit humidity and temperature resolution, fixed 40 ms
 * wait after the request, no NVM access.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x44). The constructor
 *        does no bus access: bytes written within 10 ms of power-up could be taken as NVM
 *        programming commands.
 */
class HS30xxMinimal {
public:
    HS30xxMinimal(Connection& connection);

    /** @brief Measure temperature and humidity.
     *  @param[out] temperature_c Temperature in degrees Celsius (-40 to 125).
     *  @param[out] humidity_pct  Relative humidity in %RH (0 to 100).
     *  @return true on success; false on a bus error or if the data are still stale after one retry.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    Connection& _connection;
    unsigned _wait_ms = 40;

    bool _fetch_raw(uint8_t* buf, uint8_t len);
    bool _fetch(uint8_t* buf, uint8_t len);
    static uint16_t _raw_humidity(const uint8_t* d);
    static uint16_t _raw_temperature(const uint8_t* d);
    static float _to_humidity(uint16_t raw);
    static float _to_temperature(uint16_t raw);
};

/** @brief HS30xx full interface — raw values, wait override, NVM resolution and ID, dew point.
 *
 * Extends HS30xxMinimal. The NVM methods (resolution, ID) need the sensor's programming mode,
 * which can only be entered within 10 ms after power-up. They therefore take a `power_cycle`
 * function that cuts and restores the sensor supply (e.g. via a MOSFET or a GPIO powering the
 * sensor); with nullptr they return false without touching the bus.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x44).
 */
class HS30xxFull : public HS30xxMinimal {
public:
    HS30xxFull(Connection& connection);

    /** @brief Measure temperature only (four-byte fetch); °C, NAN on failure. */
    float read_temperature();

    /** @brief Measure humidity only (two-byte fetch); %RH, NAN on failure. */
    float read_humidity();

    /** @brief Measure and return the raw 14-bit values and the status field without a stale check.
     *  @param[out] status 0 = valid, 1 = stale.
     *  @return true on success.
     */
    bool read_raw(uint16_t& humidity_raw, uint16_t& temperature_raw, uint8_t& status);

    /** @brief Override the wait between measurement request and data fetch (default 40 ms).
     *  Lower resolutions convert faster: 0.1 ms + RH_time/2 + T_time/2 (datasheet).
     *  @param ms Wait in milliseconds, at least 1.
     */
    void set_measurement_wait(unsigned ms);

    /** @brief Send the enter-programming-mode command (0xA0 0x00 0x00); only valid within 10 ms after power-up. */
    void enter_programming_mode();

    /** @brief Send the leave-programming-mode command (0x80 0x00 0x00). */
    void exit_programming_mode();

    /** @brief Read one 16-bit NVM register (programming mode only).
     *  @param reg NVM read address (0x06, 0x11, 0x1E or 0x1F).
     *  @param[out] value Register value.
     *  @return false on a bus error or if the response status byte is not 0x81.
     */
    bool read_nvm(uint8_t reg, uint16_t& value);

    /** @brief Read the NVM resolution settings (power-cycles the sensor).
     *  @param[out] humidity_bits 8, 10, 12 or 14.
     *  @param[out] temperature_bits 8, 10, 12 or 14.
     *  @param power_cycle Function that cuts and restores the sensor supply.
     *  @return false if power_cycle is nullptr or an NVM read failed.
     */
    bool get_resolution(uint8_t& humidity_bits, uint8_t& temperature_bits, void (*power_cycle)());

    /** @brief Write the NVM resolution settings (power-cycles the sensor; bits 11:10 only).
     *  Each register is read, bits 11:10 replaced and the whole word written back, then the
     *  driver waits 14 ms for the NVM commit.
     *  @param humidity_bits 8, 10, 12 or 14, or 0 to leave unchanged.
     *  @param temperature_bits 8, 10, 12 or 14, or 0 to leave unchanged.
     *  @param power_cycle Function that cuts and restores the sensor supply.
     *  @return false for an unsupported value (before any bus access), a missing power_cycle or a failed NVM read.
     */
    bool set_resolution(uint8_t humidity_bits, uint8_t temperature_bits, void (*power_cycle)());

    /** @brief Read the 32-bit sensor ID from NVM (power-cycles the sensor).
     *  @return false if power_cycle is nullptr or an NVM read failed.
     */
    bool read_id(uint32_t& id, void (*power_cycle)());

    /** @brief Measure and compute the dew point in °C (Magnus formula); -273.15 at 0 %RH, NAN on failure. */
    float dew_point();

private:
    bool _enter_programming(void (*power_cycle)());
    void _write_nvm(uint8_t reg, uint16_t value);
};

/** @brief HS3001 — same protocol as the HS30xx family; alias of HS30xxMinimal. */
using HS3001Minimal = HS30xxMinimal;
/** @brief HS3002 — alias of HS30xxMinimal. */
using HS3002Minimal = HS30xxMinimal;
/** @brief HS3003 — alias of HS30xxMinimal. */
using HS3003Minimal = HS30xxMinimal;
/** @brief HS3001 — alias of HS30xxFull. */
using HS3001Full = HS30xxFull;
/** @brief HS3002 — alias of HS30xxFull. */
using HS3002Full = HS30xxFull;
/** @brief HS3003 — alias of HS30xxFull. */
using HS3003Full = HS30xxFull;
