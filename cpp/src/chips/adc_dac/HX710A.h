#pragma once
#include <stdint.h>

/** @brief HX710A 24-bit ADC — minimal interface.
 *
 * Reads signed 24-bit ADC values from the single differential input
 * (INN/INP) at the chip's fixed Gain 128, 10 SPS default. No configuration
 * beyond the connection is required. The first post-power-up conversion is
 * discarded during construction.
 *
 * @tparam Connection HX711 connection type (HX711Connection, HX711ConnectionLinux,
 *                   or HX711ConnectionZephyr) — the HX710A reuses the HX711 transport.
 */
template<typename Connection>
class HX710AMinimal {
public:
    /** @brief Initialize and discard the first post-power-up conversion.
     *  @param connection Reference to a configured HX711 connection.
     */
    explicit HX710AMinimal(Connection& connection) : _connection(connection) {
        _connection.read_raw(25);
    }

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     */
    bool is_ready() { return _connection.is_ready(); }

    /** @brief Block until data is ready and return a signed 24-bit ADC value.
     *
     *  Reads the differential input at Gain 128, 10 SPS.
     *
     *  @return Signed 24-bit ADC value (-8 388 608 to +8 388 607).
     */
    int32_t read_raw() { return _connection.read_raw(25); }

protected:
    Connection& _connection;
};

/** @brief HX710A full interface — extends HX710AMinimal with rate selection,
 *  tare, calibration, temperature, and power management.
 *
 * The HX710A has no software-selectable gain and no second input channel —
 * unlike the HX711, only the output rate (10 or 40 SPS) is selectable for
 * the differential-input reading.
 *
 * @tparam Connection HX711 connection type.
 */
template<typename Connection>
class HX710AFull : public HX710AMinimal<Connection> {
public:
    /** @brief Initialize with default 10 SPS rate, offset 0, and scale 1.0.
     *  @param connection Reference to a configured HX711 connection.
     */
    explicit HX710AFull(Connection& connection)
        : HX710AMinimal<Connection>(connection), _pulses(25), _offset(0), _scale(1.0f)
    {}

    /** @brief Block until data is ready and return a signed 24-bit ADC value.
     *
     *  Uses the currently selected output rate.
     */
    int32_t read_raw() { return this->_connection.read_raw(_pulses); }

    /** @brief Select the differential-input output rate.
     *
     *  Issues one dummy read to apply the new rate before returning.
     *
     *  @param rate 10 or 40 (samples per second); ignored if neither.
     */
    void set_rate(uint8_t rate) {
        if      (rate == 10) _pulses = 25;
        else if (rate == 40) _pulses = 27;
        else                 return;
        this->_connection.read_raw(_pulses);
    }

    /** @brief Return the average of multiple raw differential-input readings.
     *  @param times Number of readings to average (default 10).
     *  @return Average signed 24-bit ADC value.
     */
    int32_t read_average(uint8_t times = 10) {
        int64_t total = 0;
        for (uint8_t i = 0; i < times; i++)
            total += read_raw();
        return static_cast<int32_t>(total / times);
    }

    /** @brief Capture the current average reading as the zero offset.
     *  @param times Number of readings to average (default 10).
     */
    void tare(uint8_t times = 10) { _offset = read_average(times); }

    /** @brief Return the stored tare offset.
     *  @return Offset captured by the last tare() call.
     */
    int32_t get_offset() const { return _offset; }

    /** @brief Set the calibration scale factor.
     *
     *  Calibrate: factor = (read_average() - offset) / known_weight.
     *
     *  @param factor Scale factor (ADC counts per unit weight).
     */
    void set_scale(float factor) { _scale = factor; }

    /** @brief Return the current calibration scale factor. */
    float get_scale() const { return _scale; }

    /** @brief Return the calibrated weight in the units defined by the scale factor.
     *
     *  Computes (read_average(times) - offset) / scale.
     *
     *  @param times Number of readings to average (default 1).
     *  @return Calibrated weight value.
     */
    float read_weight(uint8_t times = 1) {
        return static_cast<float>(read_average(times) - _offset) / _scale;
    }

    /** @brief Block until data is ready and return the raw on-chip temperature code.
     *
     *  Clocks 26 pulses (temperature channel, 40 Hz). This is **not** a
     *  calibrated degrees-Celsius value — the datasheet gives only a typical
     *  resolution of ~20.4 LSB/°C and states offset and gain vary
     *  significantly chip-to-chip. Use for relative drift tracking, or
     *  calibrate against a reference thermometer for absolute readings.
     *
     *  @return Signed 24-bit raw ADC code from the temperature sensor.
     */
    int32_t read_temperature_raw() { return this->_connection.read_raw(26); }

    /** @brief Enter power-down mode (PD_SCK held HIGH for >60 µs). */
    void power_down() { this->_connection.power_down(); }

    /** @brief Exit power-down, reset chip, discard settling conversion.
     *
     *  Resets to the differential input at Gain 128, 10 SPS and discards
     *  the first post-reset conversion.
     */
    void power_up() {
        this->_connection.power_up();
        _pulses = 25;
        this->_connection.read_raw(25);
    }

private:
    uint8_t  _pulses;
    int32_t  _offset;
    float    _scale;
};
