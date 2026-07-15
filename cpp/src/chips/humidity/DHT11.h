#pragma once
#include <stdint.h>

#if !defined(ARDUINO) && !defined(__ZEPHYR__)
#include <time.h>
#endif

/** @brief DHT11 temperature and humidity sensor (ASAIR) — minimal interface.
 *
 *  Performs a full DHT11 protocol transaction (host start signal, sensor
 *  response, 40-bit data frame, checksum verification) and returns
 *  temperature and humidity. Single read attempt; reports failure via the
 *  return value of ::read() rather than throwing (no exceptions in Arduino /
 *  embedded C++).
 *
 *  The driver is templated on a pin adapter that exposes a uniform interface
 *  for switching the DATA line between output (host-driven) and input
 *  (high-impedance, pulled HIGH by an external 4.7 kΩ resistor). See
 *  ::DHT11PinArduino, ::DHT11PinLinux, and ::DHT11PinZephyr for platform
 *  adapters.
 *
 *  Callers must respect the 2-second minimum sampling interval between reads;
 *  the driver does not enforce this automatically.
 *
 *  @tparam Pin  DHT11 pin adapter type.
 */
template<typename Pin>
class DHT11Minimal {
public:
    /** @brief Initialise the driver.
     *
     *  Stores the pin adapter reference; the pin is reconfigured on every
     *  call to ::read().
     *
     *  @param pin  Reference to a configured DHT11 pin adapter.
     */
    explicit DHT11Minimal(Pin& pin) : _pin(pin) {}

    /** @brief Perform a full protocol read.
     *
     *  Issues the host start signal, samples the sensor response, decodes
     *  40 bits, and verifies the checksum.
     *
     *  @param[out] temperature_c  Temperature in °C on success; unchanged on failure.
     *  @param[out] humidity_rh    Relative humidity in %RH on success; unchanged on failure.
     *  @return true on success, false on sensor timeout or checksum mismatch.
     */
    bool read(float& temperature_c, float& humidity_rh) {
        uint8_t b[5];
        if (!_read_frame(b)) return false;
        int8_t  sign     = (b[3] & 0x80) ? -1 : 1;
        uint8_t temp_dec = b[3] & 0x7F;
        temperature_c = sign * (b[2] + temp_dec * 0.1f);
        humidity_rh   = b[0] + b[1] * 0.1f;
        return true;
    }

protected:
    /** @brief Wait until the line is LOW, with a timeout in microseconds. */
    bool _wait_low(uint32_t timeout_us) {
        uint32_t start = _micros();
        while (_pin.read()) {
            if ((uint32_t)(_micros() - start) > timeout_us) return false;
        }
        return true;
    }

    /** @brief Wait until the line is HIGH, with a timeout in microseconds. */
    bool _wait_high(uint32_t timeout_us) {
        uint32_t start = _micros();
        while (!_pin.read()) {
            if ((uint32_t)(_micros() - start) > timeout_us) return false;
        }
        return true;
    }

    /** @brief Measure the duration of a HIGH pulse in microseconds (capped at 100 µs). */
    uint32_t _measure_high() {
        uint32_t start = _micros();
        while (_pin.read()) {
            if ((uint32_t)(_micros() - start) > 100) break;
        }
        return (uint32_t)(_micros() - start);
    }

    bool _read_frame(uint8_t* b) {
        _pin.set_output();
        _pin.drive(false);
        _delay_ms(20);
        _pin.set_input();
        _delay_us(30);
        if (!_wait_low(200))  return false;
        if (!_wait_high(200)) return false;
        if (!_wait_low(200))  return false;
        uint64_t bits = 0;
        for (uint8_t i = 0; i < 40; i++) {
            if (!_wait_high(200)) return false;
            uint32_t high_us = _measure_high();
            bits = (bits << 1) | ((high_us > 40) ? 1ULL : 0ULL);
        }
        for (uint8_t i = 0; i < 5; i++) {
            b[i] = (bits >> (8 * (4 - i))) & 0xFF;
        }
        uint8_t checksum = (uint8_t)(b[0] + b[1] + b[2] + b[3]);
        return checksum == b[4];
    }

    static uint32_t _micros() {
#ifdef ARDUINO
        return (uint32_t)micros();
#elif defined(__ZEPHYR__)
        return (uint32_t)((uint64_t)k_cycle_get_32() * 1000000ULL / sys_clock_hw_cycles_per_sec());
#else
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return (uint32_t)(ts.tv_sec * 1000000UL + ts.tv_nsec / 1000UL);
#endif
    }

    static void _delay_ms(uint32_t ms) {
#ifdef ARDUINO
        delay(ms);
#elif defined(__ZEPHYR__)
        k_msleep((int32_t)ms);
#else
        struct timespec ts;
        ts.tv_sec  = ms / 1000;
        ts.tv_nsec = (long)(ms % 1000) * 1000000L;
        nanosleep(&ts, nullptr);
#endif
    }

    static void _delay_us(uint32_t us) {
#ifdef ARDUINO
        delayMicroseconds(us);
#elif defined(__ZEPHYR__)
        k_usleep((int32_t)us);
#else
        struct timespec ts;
        ts.tv_sec  = us / 1000000;
        ts.tv_nsec = (long)(us % 1000000) * 1000L;
        nanosleep(&ts, nullptr);
#endif
    }

    Pin& _pin;
};

/** @brief DHT11 full interface — extends DHT11Minimal with retry and raw access.
 *
 *  Adds separate temperature/humidity accessors, automatic retry on checksum
 *  failure, and access to the raw 5-byte frame.
 *
 *  @tparam Pin  DHT11 pin adapter type.
 */
template<typename Pin>
class DHT11Full : public DHT11Minimal<Pin> {
public:
    /** @brief Initialise the driver.
     *  @param pin  Reference to a configured DHT11 pin adapter.
     */
    explicit DHT11Full(Pin& pin) : DHT11Minimal<Pin>(pin) {}

    /** @brief Read temperature in °C.
     *  @return Temperature; on failure, returns the last successful value (0.0 if none).
     */
    float read_temperature() { return _t; }

    /** @brief Read humidity in %RH.
     *  @return Humidity; on failure, returns the last successful value (0.0 if none).
     */
    float read_humidity() { return _h; }

    /** @brief Read with automatic retry on checksum/timeout failure.
     *
     *  @param max_retries  Maximum number of attempts (default 3).
     *  @param[out] temperature_c  Temperature in °C on success; unchanged on failure.
     *  @param[out] humidity_rh    Relative humidity in %RH on success; unchanged on failure.
     *  @return true on success; false if all attempts failed.
     */
    bool read_retry(float& temperature_c, float& humidity_rh, uint8_t max_retries = 3) {
        for (uint8_t i = 0; i < max_retries; i++) {
            if (this->read(temperature_c, humidity_rh)) return true;
        }
        return false;
    }

    /** @brief Read the raw 5-byte frame without interpretation.
     *
     *  @param[out] b  5-byte buffer to receive ``[hum_int, hum_dec, temp_int, temp_dec, checksum]``.
     *  @return true on success; false on sensor timeout or checksum mismatch.
     */
    bool read_raw(uint8_t* b) { return this->_read_frame(b); }

private:
    float _t = 0.0f;
    float _h = 0.0f;
};
