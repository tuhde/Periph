#pragma once
#include <stdint.h>

/** @brief HX711 24-bit ADC — minimal interface.
 *
 * Reads signed 24-bit ADC values using Channel A, Gain 128. No configuration
 * beyond the transport is required. The first post-power-up conversion is
 * discarded during construction.
 *
 * @tparam Transport HX711 transport type (HX711Transport, HX711TransportLinux,
 *                   or HX711TransportZephyr).
 */
template<typename Transport>
class HX711Minimal {
public:
    /** @brief Initialize and discard the first post-power-up conversion.
     *  @param transport Reference to a configured HX711 transport.
     */
    explicit HX711Minimal(Transport& transport) : _transport(transport) {
        _transport.read_raw(25);
    }

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *
     *  Non-blocking.
     */
    bool is_ready() { return _transport.is_ready(); }

    /** @brief Block until data is ready and return a signed 24-bit ADC value.
     *
     *  Reads Channel A at Gain 128.
     *
     *  @return Signed 24-bit ADC value (-8 388 608 to +8 388 607).
     */
    int32_t read_raw() { return _transport.read_raw(25); }

protected:
    Transport& _transport;
};

/** @brief HX711 full interface — extends HX711Minimal with gain, tare, and calibration.
 *
 * Adds gain selection (Channel A Gain 128/64, Channel B Gain 32), multi-sample
 * averaging, tare offset capture, scale factor calibration, and power management.
 *
 * @tparam Transport HX711 transport type.
 */
template<typename Transport>
class HX711Full : public HX711Minimal<Transport> {
public:
    /** @brief Initialize with default gain 128, offset 0, and scale 1.0.
     *  @param transport Reference to a configured HX711 transport.
     */
    explicit HX711Full(Transport& transport)
        : HX711Minimal<Transport>(transport), _pulses(25), _offset(0), _scale(1.0f)
    {}

    /** @brief Block until data is ready and return a signed 24-bit ADC value.
     *
     *  Uses the currently selected channel and gain.
     */
    int32_t read_raw() { return this->_transport.read_raw(_pulses); }

    /** @brief Select the input channel and gain.
     *
     *  Issues one dummy read to apply the new gain before returning.
     *
     *  @param gain 128 (Channel A), 64 (Channel A), or 32 (Channel B).
     */
    void set_gain(uint8_t gain) {
        if      (gain == 128) _pulses = 25;
        else if (gain == 32)  _pulses = 26;
        else if (gain == 64)  _pulses = 27;
        else                  return;
        this->_transport.read_raw(_pulses);
    }

    /** @brief Return the average of multiple raw ADC readings.
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

    /** @brief Enter power-down mode (PD_SCK held HIGH for >60 µs). */
    void power_down() { this->_transport.power_down(); }

    /** @brief Exit power-down, reset chip, discard settling conversion.
     *
     *  Resets to Channel A, Gain 128 and discards the first post-reset conversion.
     */
    void power_up() {
        this->_transport.power_up();
        _pulses = 25;
        this->_transport.read_raw(25);
    }

private:
    uint8_t  _pulses;
    int32_t  _offset;
    float    _scale;
};
