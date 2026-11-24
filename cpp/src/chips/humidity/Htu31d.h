#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief HTU31D humidity and temperature sensor (TE Connectivity) — minimal interface.
 *
 * Each read() starts one conversion at the highest resolution (command 0x5E, OSR 3 for humidity
 * and temperature), waits 25 ms, reads the temperature and humidity words (command 0x00) and
 * verifies both CRC-8 bytes. Humidity is clamped to 0-100 %RH.
 *
 * The device is command driven (no register pointer), so a plain Connection is used.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x40 or 0x41). The
 *        constructor does no bus access; init() performs the soft reset.
 */
class HTU31DMinimal {
public:
    HTU31DMinimal(Connection& connection);

    /** @brief Soft reset (0x1E) and wait 15 ms; heater off, highest resolution. */
    void init();

    /** @brief Measure temperature and humidity.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH (clamped to 0-100).
     *  @return true on success; false if a bus transfer failed or a CRC did not match.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t CMD_RESET       = 0x1E;
    static constexpr uint8_t CMD_HEATER_ON   = 0x04;
    static constexpr uint8_t CMD_HEATER_OFF  = 0x02;
    static constexpr uint8_t CMD_CONVERT     = 0x40;
    static constexpr uint8_t CMD_READ_TRH    = 0x00;
    static constexpr uint8_t CMD_READ_RH     = 0x10;
    static constexpr uint8_t CMD_READ_DIAG   = 0x08;
    static constexpr uint8_t CMD_READ_SERIAL = 0x0A;

    Connection& _connection;
    uint8_t _osr_rh = 3;
    uint8_t _osr_t  = 3;

    void _command(uint8_t code);
    void _start();
    unsigned _wait_ms() const;
    bool _read_words(uint8_t command, uint8_t count, uint16_t* words);
    bool _measure(uint8_t command, uint8_t count, uint16_t* words);
    static float _to_humidity(uint16_t code);
    static float _to_temperature(uint16_t code);
    static uint8_t _crc8(const uint8_t* data, uint8_t len);
};

/** @brief HTU31D full interface — oversampling, non-blocking conversion, heater, diagnostics, dew point.
 *
 * Extends HTU31DMinimal with single values, raw codes, separate oversampling settings for
 * humidity and temperature, the on-chip heater, the diagnostic byte, the serial number, soft
 * reset and the dew point.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x40 or 0x41).
 */
class HTU31DFull : public HTU31DMinimal {
public:
    static constexpr uint8_t DIAG_NVM_ERR     = 0x80;
    static constexpr uint8_t DIAG_RH_OVERRUN  = 0x40;
    static constexpr uint8_t DIAG_RH_HIGH_ERR = 0x20;
    static constexpr uint8_t DIAG_RH_LOW_ERR  = 0x10;
    static constexpr uint8_t DIAG_T_OVERRUN   = 0x08;
    static constexpr uint8_t DIAG_T_HIGH_ERR  = 0x04;
    static constexpr uint8_t DIAG_T_LOW_ERR   = 0x02;
    static constexpr uint8_t DIAG_HEATER_ON   = 0x01;

    HTU31DFull(Connection& connection);

    /** @brief Run one humidity measurement (command 0x10 after the conversion); %RH 0-100, NAN on failure. */
    float read_humidity();

    /** @brief Run one temperature measurement (the read stops after the temperature word); °C, NAN on failure. */
    float read_temperature();

    /** @brief Measure and return the raw 16-bit codes.
     *  @return true on success.
     */
    bool read_raw(uint16_t& t_raw, uint16_t& rh_raw);

    /** @brief Select the oversampling of the following measurements.
     *  @param osr_rh Humidity oversampling 0-3.
     *  @param osr_t  Temperature oversampling 0-3.
     *  @return false if either value is not 0-3.
     */
    bool set_resolution(uint8_t osr_rh, uint8_t osr_t);

    /** @brief Read back the oversampling setting. */
    void get_resolution(uint8_t& osr_rh, uint8_t& osr_t);

    /** @brief Typical conversion time of one measurement at the current oversampling, in seconds. */
    float conversion_time();

    /** @brief Write the conversion command only (non-blocking use); wait conversion_time() plus margin before read_result(). */
    void start_conversion();

    /** @brief Read the result of the last conversion (command 0x00).
     *  @return true on success.
     */
    bool read_result(float& temperature_c, float& humidity_pct);

    /** @brief Switch the on-chip heater on (0x04) or off (0x02). */
    void set_heater(bool on);

    /** @brief True if the diagnostic byte reports the heater on. */
    bool is_heater_on();

    /** @brief Read the diagnostic byte (command 0x08); test it against the DIAG_* masks. */
    uint8_t diagnostic();

    /** @brief Read the 24-bit serial number (command 0x0A, CRC verified).
     *  @return true on success; false on a bus error or CRC mismatch.
     */
    bool serial_number(uint32_t& serial);

    /** @brief Soft reset (0x1E) and wait 15 ms; heater off, oversampling back to OSR 3 / 3. */
    void software_reset();

    /** @brief Run one measurement and compute the dew point (Magnus formula) in °C; NAN on failure. */
    float dew_point();
};
