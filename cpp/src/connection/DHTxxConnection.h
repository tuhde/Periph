#pragma once
#include <Arduino.h>

/** @brief DHTxx single-wire connection for Arduino.
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 *  Direction switching uses `pinMode`; timing uses `delayMicroseconds()` for
 *  the start pulse and `micros()` with busy-wait for pulse-width measurement.
 *
 *  This is a custom protocol with no generic byte read/write, so it does not
 *  extend the shared Connection base — it carries its own enabled flag
 *  directly, gating read().
 *
 *  @param data_pin Arduino pin number for the DATA line.
 */
class DHTxxConnection {
public:
    virtual ~DHTxxConnection() {}

    /** @brief Construct the connection and configure the pin as input.
     *  @param data_pin Arduino pin number for the DATA line.
     */
    explicit DHTxxConnection(uint8_t data_pin);

    /** @brief Resume reads. */
    void enable() { _enabled = true; }
    /** @brief Gate read(); it returns false without touching the bus while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     *  Returns the 5 bytes `[hum_int, hum_dec, temp_int, temp_dec, checksum]`
     *  on success. Writes the frame into `out` (must point to at least 5 bytes)
     *  and returns `true`. On timeout or framing error, returns `false` and
     *  leaves `out` unchanged. Returns `false` without touching the bus if
     *  this connection is disabled.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return     `true` on success, `false` on timeout/framing error/disabled.
     */
    virtual bool read(uint8_t* out);

    /** @brief Release the pin. No-op on Arduino; provided for interface consistency. */
    void close();

private:
    uint8_t _pin;
    bool    _enabled = true;

    void    _drive_low();
    void    _release_bus();
    int32_t _measure_pulse(uint8_t level, uint32_t timeout_us);

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
