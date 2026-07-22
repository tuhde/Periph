#pragma once
#include <stdint.h>

class DHTxxTransport;

/** @brief DHT11 combined temperature and humidity sensor — minimal interface.
 *
 *  The DHT11 returns a 40-bit reading (humidity integer + decimal,
 *  temperature integer + decimal, checksum) over a single bidirectional
 *  data line. The driver accepts a `DHTxxTransport` instance that handles
 *  the underlying single-wire protocol; this class is responsible only
 *  for validating the frame and converting it to engineering units.
 *
 *  Default configuration (baked in at construction):
 *      - Single read attempt; valid() returns false on checksum mismatch
 *      - Caller responsible for respecting the ≥ 2 s sampling interval
 *
 *  @param transport Configured `DHTxxTransport` bound to the chip's DATA pin.
 */
class DHT11Minimal {
public:
    /** @brief Construct the driver.
     *  @param transport DHTxx transport instance.
     */
    explicit DHT11Minimal(DHTxxTransport& transport);

    /** @brief Read both temperature and humidity in a single transaction.
     *
     *  @param temperature Output: temperature in degrees Celsius.
     *  @param humidity    Output: humidity in %RH.
     *  @return `true` on success, `false` if the frame's checksum is invalid.
     */
    bool read(float& temperature, float& humidity);

    /** @brief Return whether the most recent `read` succeeded.
     *  @return `true` if the last `read` call produced a valid frame.
     */
    bool valid() const { return _valid; }

protected:
    DHTxxTransport& _transport;
    bool _valid = false;

    void _decode(const uint8_t* frame, float& temperature, float& humidity);
};

/** @brief DHT11 full interface — extends DHT11Minimal with retry, raw access, and convenience methods.
 *
 *  Adds a configurable-retry read, separate `read_temperature()` /
 *  `read_humidity()` accessors, and a `read_raw()` method that returns
 *  the unprocessed 5-byte frame.
 *
 *  @param transport   Configured `DHTxxTransport` bound to the chip's DATA pin.
 *  @param max_retries Default retry count for `read_retry` (default 3).
 */
class DHT11Full : public DHT11Minimal {
public:
    /** @brief Construct the driver.
     *  @param transport   DHTxx transport instance.
     *  @param max_retries Default retry count for `read_retry` (default 3).
     */
    DHT11Full(DHTxxTransport& transport, uint8_t max_retries = 3);

    /** @brief Read temperature in a single transaction.
     *  @return Temperature in degrees Celsius, or NaN on failure.
     */
    float read_temperature();

    /** @brief Read humidity in a single transaction.
     *  @return Humidity in %RH, or NaN on failure.
     */
    float read_humidity();

    /** @brief Read both values, retrying on checksum error.
     *
     *  @param max_retries Maximum number of read attempts (0 = use default).
     *  @param temperature Output: temperature in degrees Celsius.
     *  @param humidity    Output: humidity in %RH.
     *  @return `true` on success, `false` if all attempts fail.
     */
    bool read_retry(uint8_t max_retries, float& temperature, float& humidity);

    /** @brief Read the raw 5-byte frame.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return `true` on success, `false` if the frame's checksum is invalid.
     */
    bool read_raw(uint8_t* out);

    /** @brief Read the raw 5-byte frame using the default retry count.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return `true` on success, `false` if all attempts fail.
     */
    bool read_raw_with_retry(uint8_t* out);

private:
    uint8_t _max_retries;
};
