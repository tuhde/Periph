#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief VL6180X proximity and ambient-light sensor (STMicroelectronics) — minimal interface.
 *
 *  VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS)
 *  behind one aperture. Measures absolute distance 0–100 mm (8-bit result in
 *  mm) and ambient light (16-bit count, converted to lux). Registers use a
 *  16-bit big-endian index; 16-bit results and thresholds are MSB first.
 *
 *  init() waits 1 ms for boot, then — only while SYSTEM__FRESH_OUT_OF_RESET is
 *  1 — applies the mandatory private-register settings (ST AN4545, not
 *  documented in the datasheet), then the recommended settings (averaging
 *  period 48, max convergence 49 ms, ALS gain 1, 100 ms ALS integration,
 *  new-sample interrupt configuration). read_range() then takes one
 *  single-shot measurement per call.
 *
 *  @param connection RegisterConnection (I²C, regBytes = 2) pointing at the
 *         device (address 0x29). The constructor does no bus access.
 */
class VL6180XMinimal {
public:
    /** @brief Default 7-bit I²C address. */
    static constexpr uint8_t I2C_ADDRESS = 0x29;
    /** @brief Expected IDENTIFICATION__MODEL_ID. */
    static constexpr uint8_t MODEL_ID = 0xB4;
    /** @brief Interrupt source: range event. */
    static constexpr uint8_t SOURCE_RANGE = 0x01;
    /** @brief Interrupt source: ALS event. */
    static constexpr uint8_t SOURCE_ALS = 0x02;
    /** @brief Interrupt source: error event. */
    static constexpr uint8_t SOURCE_ERROR = 0x04;
    /** @brief Interrupt mode: value below the low threshold. */
    static constexpr uint8_t MODE_LEVEL_LOW = 1;
    /** @brief Interrupt mode: value above the high threshold. */
    static constexpr uint8_t MODE_LEVEL_HIGH = 2;
    /** @brief Interrupt mode: value outside the threshold window. */
    static constexpr uint8_t MODE_OUT_OF_WINDOW = 3;
    /** @brief Interrupt mode: new sample ready (default). */
    static constexpr uint8_t MODE_NEW_SAMPLE_READY = 4;
    /** @brief Value of last_error() when a measurement timed out. */
    static constexpr uint8_t ERROR_TIMEOUT = 0xFF;

    explicit VL6180XMinimal(RegisterConnection& connection);

    /** @brief Run the initialization sequence (boot wait, private + recommended settings).
     *  @return false if a bus transfer failed. */
    bool init();

    /** @brief Take one single-shot range measurement.
     *  @param[out] mm Distance in mm (0–255; reliable up to about 100 mm).
     *  @return true on success; false on a timeout (100 ms) or a non-zero
     *          range error code — see last_error(). */
    bool read_range(uint8_t& mm);

    /** @brief Range error code of the last measurement (0 = valid, 1–15 datasheet codes,
     *         ERROR_TIMEOUT if no result arrived). */
    uint8_t last_error() const { return _lastError; }

protected:
    static constexpr uint16_t REG_MODEL_ID              = 0x000;
    static constexpr uint16_t REG_MODEL_REV_MAJOR       = 0x001;
    static constexpr uint16_t REG_MODEL_REV_MINOR       = 0x002;
    static constexpr uint16_t REG_MODE_GPIO1            = 0x011;
    static constexpr uint16_t REG_HISTORY_CTRL          = 0x012;
    static constexpr uint16_t REG_INTERRUPT_CONFIG      = 0x014;
    static constexpr uint16_t REG_INTERRUPT_CLEAR       = 0x015;
    static constexpr uint16_t REG_FRESH_OUT_OF_RESET    = 0x016;
    static constexpr uint16_t REG_GROUPED_PARAM_HOLD    = 0x017;
    static constexpr uint16_t REG_RANGE_START           = 0x018;
    static constexpr uint16_t REG_RANGE_THRESH_HIGH     = 0x019;
    static constexpr uint16_t REG_RANGE_THRESH_LOW      = 0x01A;
    static constexpr uint16_t REG_RANGE_INTERMEASUREMENT = 0x01B;
    static constexpr uint16_t REG_RANGE_MAX_CONVERGENCE = 0x01C;
    static constexpr uint16_t REG_RANGE_CROSSTALK_RATE  = 0x01E;
    static constexpr uint16_t REG_RANGE_OFFSET          = 0x024;
    static constexpr uint16_t REG_VHV_RECALIBRATE       = 0x02E;
    static constexpr uint16_t REG_VHV_REPEAT_RATE       = 0x031;
    static constexpr uint16_t REG_ALS_START             = 0x038;
    static constexpr uint16_t REG_ALS_THRESH_HIGH       = 0x03A;
    static constexpr uint16_t REG_ALS_THRESH_LOW        = 0x03C;
    static constexpr uint16_t REG_ALS_INTERMEASUREMENT  = 0x03E;
    static constexpr uint16_t REG_ALS_GAIN              = 0x03F;
    static constexpr uint16_t REG_ALS_INTEGRATION       = 0x040;
    static constexpr uint16_t REG_RANGE_STATUS          = 0x04D;
    static constexpr uint16_t REG_INTERRUPT_STATUS      = 0x04F;
    static constexpr uint16_t REG_ALS_VAL               = 0x050;
    static constexpr uint16_t REG_HISTORY_BUFFER        = 0x052;
    static constexpr uint16_t REG_RANGE_VAL             = 0x062;
    static constexpr uint16_t REG_AVERAGING_PERIOD      = 0x10A;
    static constexpr uint16_t REG_RESULT_SCALER         = 0x120;
    static constexpr uint16_t REG_I2C_ADDRESS           = 0x212;
    static constexpr uint16_t REG_INTERLEAVED_ENABLE    = 0x2A3;

    RegisterConnection& _conn;
    uint8_t _gainCode = 6;
    uint16_t _integrationMs = 100;
    uint8_t _lastError = 0;

    uint8_t _rd8(uint16_t reg);
    void _wr8(uint16_t reg, uint8_t value);
    uint16_t _rd16(uint16_t reg);
    void _wr16(uint16_t reg, uint16_t value);
    /** @brief Poll the interrupt status until (status >> shift) & 7 == 4, at most @p timeoutMs. */
    bool _waitSample(uint8_t shift, uint32_t timeoutMs);
    bool _measureRange(uint8_t& mm, uint8_t& error);
    bool _readRangeResult(uint8_t& mm, uint8_t& error);
    static void _delayMs(uint32_t ms);
};

/** @brief VL6180X full interface — extends Minimal with ALS (lux), continuous and
 *  interleaved modes, thresholds, gain / integration / scaler, convergence and
 *  averaging, range offset and crosstalk, VHV recalibration, address change,
 *  identification, the history buffer and the Level-2 interrupt API.
 *
 *  @param connection RegisterConnection (I²C, regBytes = 2) pointing at the device.
 */
class VL6180XFull : public VL6180XMinimal {
public:
    explicit VL6180XFull(RegisterConnection& connection) : VL6180XMinimal(connection) {}

    /** @brief Take one single-shot range measurement without failing on range errors.
     *  @param[out] mm Distance in mm.
     *  @param[out] error Range error code (0 = valid, 1–15 datasheet codes).
     *  @return false only on a timeout (100 ms). */
    bool read_range_status(uint8_t& mm, uint8_t& error);

    /** @brief Start continuous ranging.
     *  @param period_ms Period in ms, 10–2550 in 10 ms steps.
     *  @return false if period_ms is out of range. */
    bool start_continuous_range(uint16_t period_ms);

    /** @brief Stop continuous ranging (the start/stop bit toggles the running mode). */
    void stop_continuous_range();

    /** @brief Wait for the next continuous-mode range sample and read it.
     *  @param[out] mm Distance in mm.
     *  @return false on a timeout or a non-zero range error code (see last_error()). */
    bool read_range_continuous(uint8_t& mm);

    /** @brief Take one single-shot ALS measurement.
     *  @param[out] raw 16-bit ALS count.
     *  @return false on a timeout (integration time + 100 ms). */
    bool read_als_raw(uint16_t& raw);

    /** @brief Take one single-shot ALS measurement and convert it to lux
     *  (0.32 · count / gain · 100 / integration_ms, actual gain of the current code).
     *  @return Illuminance in lux (without cover glass); NAN on a timeout. */
    float read_lux();

    /** @brief Start continuous ALS measurements.
     *  @param period_ms Period in ms, 10–2550 in 10 ms steps.
     *  @return false if period_ms is out of range. */
    bool start_continuous_als(uint16_t period_ms);

    /** @brief Stop continuous ALS measurements. */
    void stop_continuous_als();

    /** @brief Enable or disable interleaved ALS + range mode (started with start_continuous_als()). */
    void set_interleaved(bool on);

    /** @brief Set the ALS analogue gain code 0–7 (0 = 20, 1 = 10, 2 = 5, 3 = 2.5, 4 = 1.67, 5 = 1.25, 6 = 1, 7 = 40).
     *  @return false if code is out of range. */
    bool set_als_gain(uint8_t code);

    /** @brief Set the ALS integration time, 1–512 ms.
     *  @return false if out of range. */
    bool set_als_integration_time(uint16_t ms);

    /** @brief Set the ALS result scaler (FIRMWARE__RESULT_SCALER), 1–16.
     *  @return false if out of range. */
    bool set_als_scaler(uint8_t scaler);

    /** @brief Set the range interrupt thresholds in mm (grouped-parameter-hold protocol).
     *  @return false unless low <= high. */
    bool set_range_thresholds(uint8_t low, uint8_t high);

    /** @brief Set the ALS interrupt thresholds in counts (grouped-parameter-hold protocol).
     *  @return false unless low <= high. */
    bool set_als_thresholds(uint16_t low, uint16_t high);

    /** @brief Set the maximum convergence time, 1–63 ms.
     *  @return false if out of range. */
    bool set_max_convergence_time(uint8_t ms);

    /** @brief Set the ranging averaging sample period (about 64.5 µs per unit; 48 is the default). */
    void set_averaging_period(uint8_t units);

    /** @brief Set the part-to-part range offset in mm (signed, two's complement on the chip). */
    void set_range_offset(int8_t mm);

    /** @brief Read the part-to-part range offset in mm. */
    int8_t get_range_offset();

    /** @brief Set the crosstalk compensation rate in Mcps (stored as 9.7 fixed point).
     *  @return false if out of range. */
    bool set_crosstalk_rate(float rate);

    /** @brief Start a VHV recalibration and wait for it to finish.
     *  @return false if the start bit did not clear within 100 ms. */
    bool recalibrate_vhv();

    /** @brief Change the chip's I²C address (volatile); the Connection must be re-created at the new address.
     *  @return false if address is outside 0x08–0x77. */
    bool set_address(uint8_t address);

    /** @brief Read the model ID (0xB4). */
    uint8_t model_id();

    /** @brief Read the model revision (bits 2:0 of each revision register). */
    void revision(uint8_t& major, uint8_t& minor);

    /** @brief Read the eight-entry history buffer (enable it through the history control register first). */
    void read_history(uint16_t out[8]);

    /** @brief Select the interrupt mode of the range or ALS source.
     *  @param source SOURCE_RANGE or SOURCE_ALS.
     *  @param mode MODE_LEVEL_LOW, MODE_LEVEL_HIGH, MODE_OUT_OF_WINDOW or MODE_NEW_SAMPLE_READY.
     *  @return false if source or mode is invalid. */
    bool enable_interrupt(uint8_t source, uint8_t mode);

    /** @brief Disable the interrupt of the range or ALS source (mode 0).
     *  @return false if source is invalid. */
    bool disable_interrupt(uint8_t source);

    /** @brief Read the interrupt status and clear all pending sources.
     *  @return Bit mask of SOURCE_RANGE, SOURCE_ALS, SOURCE_ERROR (0 if none). */
    uint8_t poll_interrupt();

    /** @brief Subscribe to GPIO1 falling edges (pending sources are cleared before the callback runs).
     *  @param callback Called with the SOURCE_* bit mask.
     *  @param intPin Optional pin overriding connection.intPin(); without any pin nothing is delivered (use poll_interrupt()). */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from GPIO1 edges. */
    void off_interrupt();

private:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static VL6180XFull* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
    bool _setInterruptMode(uint8_t source, uint8_t mode);
};
