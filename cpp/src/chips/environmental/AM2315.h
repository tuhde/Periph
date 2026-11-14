#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief AM2315 humidity and temperature sensor (Aosong) — minimal interface.
 *
 * The sensor speaks a Modbus-style protocol: every read is a wake-up (a write the sensor does not
 * acknowledge), a command write, a 2 ms wait and a read whose response carries a CRC-16. The sensor
 * measures after each communication and returns that result on the next one, so read() returns the
 * previous measurement; transactions are kept at least 2 s apart by waiting the remainder.
 *
 * Default configuration: the constructor does no bus access; the first read() waits until 2 s after
 * construction (power-up settling); one retry after a new wake on a CRC mismatch or bus error.
 *
 * @param connection Connection (I²C, standard mode, at most 100 kHz) pointing at the device (address 0x5C).
 */
class AM2315Minimal {
public:
    AM2315Minimal(Connection& connection);

    /** @brief Read temperature and humidity (registers 0x00-0x03).
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH.
     *  @return true on success; false on a bus error, a CRC mismatch (both after one retry) or a sensor
     *          error response (see last_error()).
     */
    bool read(float& temperature_c, float& humidity_pct);

    /** @brief Error response code (0x80-0x84) of the last transaction, or 0 if it had none. */
    uint8_t last_error() const { return _last_error; }

    /** @brief Override the minimum interval between transactions (default 2000 ms); intended for tests. */
    void set_min_interval_ms(uint32_t ms) { _interval_ms = ms; }

protected:
    static constexpr uint8_t FUNC_READ  = 0x03;
    static constexpr uint8_t FUNC_WRITE = 0x10;

    Connection& _connection;
    uint32_t _interval_ms = 2000;
    uint32_t _last = 0;
    uint8_t _last_error = 0;

    void _wait_interval();
    void _wake();
    bool _exchange(const uint8_t* frame, size_t frame_len, uint8_t* resp, size_t resp_len);
    bool _check(const uint8_t* resp, uint8_t function, size_t resp_len);
    bool _read_registers(uint8_t start, uint8_t count, uint8_t* out);
    static uint16_t _crc16(const uint8_t* data, size_t len);
    static float _to_temperature(uint16_t raw);
};

/** @brief AM2315 full interface — single values, raw words, fresh read, registers, user registers.
 *
 * Extends AM2315Minimal with temperature-only and humidity-only reads, raw words, the two-read
 * real-time value, arbitrary register read/write (CRC verified), device information, the user
 * registers and an explicit wake-up.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x5C).
 */
class AM2315Full : public AM2315Minimal {
public:
    AM2315Full(Connection& connection);

    /** @brief Read the temperature only (registers 0x02-0x03); °C, NAN on failure. */
    float read_temperature();

    /** @brief Read the humidity only (registers 0x00-0x01); %RH, NAN on failure. */
    float read_humidity();

    /** @brief Read the raw 16-bit words (the temperature word is sign + magnitude).
     *  @return true on success.
     */
    bool read_raw(uint16_t& humidity_raw, uint16_t& temperature_raw);

    /** @brief Read twice (at least 2 s apart) and return the second, real-time result.
     *  @return true on success.
     */
    bool read_fresh(float& temperature_c, float& humidity_pct);

    /** @brief Read @p count registers from @p start (function 0x03, CRC verified).
     *  @param start Register address (0x00-0x1F).
     *  @param count Number of registers, 1-10.
     *  @param[out] out Buffer of at least @p count bytes.
     *  @return false on invalid arguments or a failed transaction.
     */
    bool read_registers(uint8_t start, uint8_t count, uint8_t* out);

    /** @brief Write the user registers 0x10-0x13 (function 0x10 with CRC).
     *  @param start First register address (0x10-0x13).
     *  @param data  Bytes to write.
     *  @param len   1-4 bytes; start + len must not pass 0x14.
     *  @return false on invalid arguments or a failed transaction.
     */
    bool write_registers(uint8_t start, const uint8_t* data, uint8_t len);

    /** @brief Read the device information (registers 0x08-0x0E). @return true on success. */
    bool read_device_info(uint16_t& model, uint8_t& version, uint32_t& device_id);

    /** @brief Read the two 16-bit user registers (0x10-0x13). @return true on success. */
    bool read_user_registers(uint16_t& user1, uint16_t& user2);

    /** @brief Write the two 16-bit user registers (0x10-0x13). @return true on success. */
    bool write_user_registers(uint16_t user1, uint16_t user2);

    /** @brief Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait. */
    void wake();
};
