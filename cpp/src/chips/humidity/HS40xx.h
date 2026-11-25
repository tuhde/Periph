#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"

/** @brief HS40xx humidity and temperature sensor (Renesas HS4001 / HS4003) — minimal interface.
 *
 * HS4001 and HS4003 share one protocol, so HS4001Minimal and HS4003Minimal are aliases of this
 * class (HS4002 and the HS401x variants differ only physically and get no separate class).
 *
 * Each read() sends the no-hold humidity + temperature command (0xF5), waits 3 ms, reads five
 * bytes (retrying while the sensor NACKs the read header, for up to 20 ms), verifies the CRC-8
 * and converts. A CRC mismatch is retried once.
 *
 * Default configuration (power-on): 14-bit resolution, one-shot no-hold measurements, CRC
 * checked, periodic mode and alerts off.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x54). The constructor
 *        does no bus access.
 */
class HS40xxMinimal {
public:
    HS40xxMinimal(Connection& connection);

    /** @brief Measure temperature and humidity.
     *  @param[out] temperature_c Temperature in degrees Celsius (-40 to 125).
     *  @param[out] humidity_pct  Relative humidity in %RH (0 to 100).
     *  @return true on success; false on a bus error, read timeout or a CRC mismatch after one retry.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    Connection& _connection;

    static uint8_t _crc8(const uint8_t* data, uint8_t len);
    bool _read_frame(uint8_t* buf, uint8_t len);
    bool _measure(uint8_t cmd, uint8_t* buf, uint8_t len, bool hold);
    static uint16_t _raw_humidity(const uint8_t* d);
    static uint16_t _raw_temperature(const uint8_t* d);
    static float _to_humidity(uint16_t raw);
    static float _to_temperature(uint16_t raw);
};

/** @brief HS40xx full interface — single values, hold mode, resolution, periodic mode, alerts, ID.
 *
 * Extends HS40xxMinimal. Registers are volatile: they return to their defaults whenever the
 * supply is cut or after soft_reset().
 *
 * @param connection Connection (I²C) pointing at the device (address 0x54).
 */
class HS40xxFull : public HS40xxMinimal {
public:
    static constexpr uint8_t SOURCE_RH_LOW    = 0x01;
    static constexpr uint8_t SOURCE_RH_HIGH   = 0x02;
    static constexpr uint8_t SOURCE_TEMP_LOW  = 0x04;
    static constexpr uint8_t SOURCE_TEMP_HIGH = 0x08;

    HS40xxFull(Connection& connection);

    /** @brief Measure temperature only (no-hold 0xF3, 3 bytes); °C, NAN on failure. */
    float read_temperature();

    /** @brief Measure humidity (uses 0xF5); %RH, NAN on failure. */
    float read_humidity();

    /** @brief Measure with the hold command 0xE5 (SCL stretched until done; needs SCL >= 200 kHz).
     *  @return true on success.
     */
    bool read_hold(float& temperature_c, float& humidity_pct);

    /** @brief Measure and return the raw 14-bit values (CRC verified). @return true on success. */
    bool read_raw(uint16_t& humidity_raw, uint16_t& temperature_raw);

    /** @brief Read one configuration register (0xA7 + address, then a one-byte read). */
    bool read_register(uint8_t reg, uint8_t& value);

    /** @brief Write one configuration register (0xA6 + address + data). */
    bool write_register(uint8_t reg, uint8_t value);

    /** @brief Set the resolutions (register 0x00, read-modify-write).
     *  @param humidity_bits 8, 10, 12 or 14, or 0 to leave unchanged.
     *  @param temperature_bits 8, 10, 12 or 14, or 0 to leave unchanged.
     *  @return false for an unsupported value (before any bus access) or a bus error.
     */
    bool set_resolution(uint8_t humidity_bits, uint8_t temperature_bits);

    /** @brief Read the resolutions (each 8, 10, 12 or 14). */
    bool get_resolution(uint8_t& humidity_bits, uint8_t& temperature_bits);

    /** @brief Start periodic mode.
     *  @param period_s 0.5, 1 or 2.5 seconds.
     *  @return false for an unsupported period or a bus error.
     */
    bool start_periodic(float period_s);

    /** @brief Leave periodic mode (command 0x30). */
    bool stop_periodic();

    /** @brief Fetch the latest periodic result (CRC verified).
     *  @param[out] status SOURCE_* bitmask of the active alerts (0 when alerts are off).
     *  @return true on success.
     */
    bool read_periodic(float& temperature_c, float& humidity_pct, uint8_t& status);

    /** @brief Program alert thresholds; pass NAN to leave one unchanged.
     *  RH values in %RH, temperatures in °C.
     */
    bool set_alert_thresholds(float rh_high = NAN_F, float rh_high_reset = NAN_F,
                              float rh_low = NAN_F, float rh_low_reset = NAN_F,
                              float temp_high = NAN_F, float temp_high_reset = NAN_F,
                              float temp_low = NAN_F, float temp_low_reset = NAN_F);

    /** @brief Set the ALERT pin polarity (register 0x02 bit 0). */
    bool set_alert_polarity(bool active_high);

    /** @brief Enable alert source(s) (register 0x03). */
    bool enable_interrupt(uint8_t source);

    /** @brief Disable alert source(s) (register 0x03). */
    bool disable_interrupt(uint8_t source);

    /** @brief Fetch the latest periodic result and return the alert status bitmask (0 on failure or if no alert is enabled). */
    uint8_t poll_interrupt();

    /** @brief Subscribe to the ALERT pin.
     *  @param callback Called with the status bitmask on each ALERT edge.
     *  @param intPin Optional InputPin overriding connection.intPin(); without a pin nothing is registered.
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from the ALERT pin. */
    void off_interrupt();

    /** @brief Read the 32-bit sensor ID (0xD7, 4 bytes MSB first). */
    bool read_id(uint32_t& id);

    /** @brief Stop periodic mode, soft reset (0xFE) and wait 500 ms. */
    bool soft_reset();

    /** @brief Measure and compute the dew point in °C (Magnus formula); -273.15 at 0 %RH, NAN on failure. */
    float dew_point();

private:
    static constexpr float NAN_F = __builtin_nanf("");
    bool _modify(uint8_t reg, uint8_t mask, uint8_t bits);
    static uint16_t _rh_word(float rh);
    static uint16_t _temp_word(float t);
    bool _write_threshold(uint8_t low_reg, uint16_t word);
    static void _edge_trampoline();

    bool _alerts_enabled = false;
    bool _active_high = true;
    void (*_callback)(uint8_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static HS40xxFull* _active_instance;
};

/** @brief HS4001 — same protocol as the HS40xx family; alias of HS40xxMinimal. */
using HS4001Minimal = HS40xxMinimal;
/** @brief HS4003 — alias of HS40xxMinimal. */
using HS4003Minimal = HS40xxMinimal;
/** @brief HS4001 — alias of HS40xxFull. */
using HS4001Full = HS40xxFull;
/** @brief HS4003 — alias of HS40xxFull. */
using HS4003Full = HS40xxFull;
