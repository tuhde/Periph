#pragma once
#include <stdint.h>
#include <math.h>

/** @brief DHT11 combined temperature and humidity sensor — minimal interface.
 *
 *  The DHT11 returns a 40-bit reading (humidity integer + decimal,
 *  temperature integer + decimal, checksum) over a single bidirectional
 *  data line. The driver accepts a DHTxx connection instance that handles
 *  the underlying single-wire protocol; this class is responsible only
 *  for validating the frame and converting it to engineering units.
 *
 *  DHTxx connections have no shared base class across platforms (each is a
 *  standalone custom-protocol implementation — see DHTxxConnection.h), so
 *  this driver is templated on the connection type, same as HX711Minimal.
 *
 *  Default configuration (baked in at construction):
 *      - Single read attempt; valid() returns false on checksum mismatch
 *      - Caller responsible for respecting the ≥ 2 s sampling interval
 *
 *  @tparam Connection DHTxx connection type (DHTxxConnection, DHTxxConnectionLinux,
 *                    DHTxxConnectionZephyr, DHTxxConnectionESPIDF, or DHTxxConnectionPicoSDK).
 */
template<typename Connection>
class DHT11Minimal {
public:
    /** @brief Construct the driver.
     *  @param connection Configured DHTxx connection bound to the chip's DATA pin.
     */
    explicit DHT11Minimal(Connection& connection) : _connection(connection) {}

    /** @brief Read both temperature and humidity in a single transaction.
     *
     *  @param temperature Output: temperature in degrees Celsius.
     *  @param humidity    Output: humidity in %RH.
     *  @return `true` on success, `false` if the frame's checksum is invalid.
     */
    bool read(float& temperature, float& humidity) {
        uint8_t frame[5];
        if (!_connection.read(frame)) {
            _valid = false;
            temperature = NAN;
            humidity    = NAN;
            return false;
        }
        _decode(frame, temperature, humidity);
        return _valid;
    }

    /** @brief Return whether the most recent `read` succeeded.
     *  @return `true` if the last `read` call produced a valid frame.
     */
    bool valid() const { return _valid; }

protected:
    Connection& _connection;
    bool _valid = false;

    void _decode(const uint8_t* frame, float& temperature, float& humidity) {
        uint8_t hum_int  = frame[0];
        uint8_t hum_dec  = frame[1];
        uint8_t temp_int = frame[2];
        uint8_t temp_dec = frame[3];
        uint8_t checksum = frame[4];
        uint8_t expected = (uint8_t)((hum_int + hum_dec + temp_int + temp_dec) & 0xFF);
        if (expected != checksum) {
            _valid = false;
            temperature = NAN;
            humidity    = NAN;
            return;
        }
        humidity = hum_int + hum_dec / 10.0f;
        int sign = (temp_dec & 0x80) ? -1 : 1;
        uint8_t temp_dec_value = (uint8_t)(temp_dec & 0x7F);
        temperature = sign * (temp_int + temp_dec_value / 10.0f);
        _valid = true;
    }
};

/** @brief DHT11 full interface — extends DHT11Minimal with retry, raw access, and convenience methods.
 *
 *  Adds a configurable-retry read, separate `read_temperature()` /
 *  `read_humidity()` accessors, and a `read_raw()` method that returns
 *  the unprocessed 5-byte frame.
 *
 *  @tparam Connection DHTxx connection type.
 */
template<typename Connection>
class DHT11Full : public DHT11Minimal<Connection> {
public:
    /** @brief Construct the driver.
     *  @param connection   Configured DHTxx connection bound to the chip's DATA pin.
     *  @param max_retries Default retry count for `read_retry` (default 3).
     */
    DHT11Full(Connection& connection, uint8_t max_retries = 3)
        : DHT11Minimal<Connection>(connection), _max_retries(max_retries) {}

    /** @brief Read temperature in a single transaction.
     *  @return Temperature in degrees Celsius, or NaN on failure.
     */
    float read_temperature() {
        float t, h;
        this->read(t, h);
        return t;
    }

    /** @brief Read humidity in a single transaction.
     *  @return Humidity in %RH, or NaN on failure.
     */
    float read_humidity() {
        float t, h;
        this->read(t, h);
        return h;
    }

    /** @brief Read both values, retrying on checksum error.
     *
     *  @param max_retries Maximum number of read attempts (0 = use default).
     *  @param temperature Output: temperature in degrees Celsius.
     *  @param humidity    Output: humidity in %RH.
     *  @return `true` on success, `false` if all attempts fail.
     */
    bool read_retry(uint8_t max_retries, float& temperature, float& humidity) {
        if (max_retries == 0) max_retries = _max_retries;
        for (uint8_t i = 0; i < max_retries; i++) {
            uint8_t frame[5];
            if (this->_connection.read(frame)) {
                this->_decode(frame, temperature, humidity);
                if (this->_valid) return true;
            }
        }
        return false;
    }

    /** @brief Read the raw 5-byte frame.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return `true` on success, `false` if the frame's checksum is invalid.
     */
    bool read_raw(uint8_t* out) {
        return this->_connection.read(out);
    }

    /** @brief Read the raw 5-byte frame using the default retry count.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return `true` on success, `false` if all attempts fail.
     */
    bool read_raw_with_retry(uint8_t* out) {
        for (uint8_t i = 0; i < _max_retries; i++) {
            if (this->_connection.read(out)) {
                uint8_t expected = (uint8_t)((out[0] + out[1] + out[2] + out[3]) & 0xFF);
                if (expected == out[4]) return true;
            }
        }
        return false;
    }

private:
    uint8_t _max_retries;
};
