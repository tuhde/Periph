#pragma once
#include <Arduino.h>

/** @brief DHTxx single-wire transport for Arduino.
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 *  Direction switching uses `pinMode`; timing uses `delayMicroseconds()` for
 *  the start pulse and `micros()` with busy-wait for pulse-width measurement.
 *
 *  @param data_pin Arduino pin number for the DATA line.
 */
class DHTxxTransport {
public:
    virtual ~DHTxxTransport() {}

    /** @brief Construct the transport and configure the pin as input.
     *  @param data_pin Arduino pin number for the DATA line.
     */
    explicit DHTxxTransport(uint8_t data_pin);

    /** @brief Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     *  Returns the 5 bytes `[hum_int, hum_dec, temp_int, temp_dec, checksum]`
     *  on success. Writes the frame into `out` (must point to at least 5 bytes)
     *  and returns `true`. On timeout or framing error, returns `false` and
     *  leaves `out` unchanged.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return     `true` on success, `false` on timeout/framing error.
     */
    virtual bool read(uint8_t* out);

    /** @brief Release the pin. No-op on Arduino; provided for interface consistency. */
    void close();

private:
    uint8_t _pin;

    void    _drive_low();
    void    _release_bus();
    int32_t _measure_pulse(uint8_t level, uint32_t timeout_us);

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
