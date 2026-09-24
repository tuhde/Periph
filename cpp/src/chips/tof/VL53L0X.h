#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "VL53Base.h"

/** @brief VL53L0X Time-of-Flight laser-ranging sensor — minimal interface.
 *
 *  940 nm VCSEL emitter, SPAD receiving array and an embedded ranging
 *  microcontroller measuring absolute distance up to ~2 m, largely
 *  independent of target reflectance. The datasheet has no register map:
 *  registers, the tuning table and the init/calibration sequences follow
 *  ST's STSW-IMG005 API (the same derivation as Pololu's VL53L0X library).
 *  Multi-byte registers are big-endian.
 *
 *  The constructor runs the full initialization sequence (boot wait, model
 *  ID check, 2V8 I/O mode, reference SPADs, default tuning, GPIO1 = new
 *  sample ready active low, ~33 ms timing budget, VHV + phase reference
 *  calibration) and leaves the chip idle; construct it only after the bus
 *  is started. It calls abort() if the model ID is not 0xEE or an init poll
 *  times out (exceptions are disabled on every C++ target in this repo).
 *  If the connection has an enPin (XSHUT), it is driven high first.
 *
 *  Multiple sensors on one bus: all power up at 0x29. Hold every sensor's
 *  XSHUT low (each Connection disabled), then for each sensor in turn enable
 *  its XSHUT, construct a driver on 0x29, call setAddress(new), and build
 *  the real driver on a Connection at the new address. The new address is
 *  volatile — it reverts to 0x29 on power-up or an XSHUT low pulse.
 *
 *  Register access, polling, boot wait, interrupt delivery and
 *  re-addressing come from the shared VL53Base (specs/tof/_vl53_base.md);
 *  I2C_ADDRESS and the SOURCE_* constants are inherited from it.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class VL53L0XMinimal : public VL53Base {
public:
    /** @brief Expected IDENTIFICATION_MODEL_ID. */
    static constexpr uint8_t MODEL_ID = 0xEE;
    /** @brief Device range status meaning "range complete — valid". */
    static constexpr uint8_t RANGE_STATUS_VALID = 11;
    /** @brief Returned by distance()/readContinuous() when a poll loop times out (500 ms). */
    static constexpr uint16_t TIMEOUT = 0xFFFF;

    explicit VL53L0XMinimal(Connection& connection);

    /** @brief Take one single-shot measurement.
     *
     *  Blocks for about one timing budget (33 ms by default). Returns the
     *  raw range even when the measurement is not valid — typically 8190 or
     *  8191 with no target in range; check rangeValid().
     *
     *  @return Distance in mm, or TIMEOUT if the measurement did not start
     *          or complete within 500 ms. */
    uint16_t distance();

    /** @brief Report whether the most recent measurement was valid.
     *  @return true iff the device range status was 11 (range complete). */
    bool rangeValid();

protected:
    static constexpr uint8_t REG_SYSRANGE_START            = 0x00;
    static constexpr uint8_t REG_SYSTEM_SEQUENCE_CONFIG    = 0x01;
    static constexpr uint8_t REG_SYSTEM_INTERMEASUREMENT   = 0x04;
    static constexpr uint8_t REG_SYSTEM_INTERRUPT_CONFIG   = 0x0A;
    static constexpr uint8_t REG_SYSTEM_INTERRUPT_CLEAR    = 0x0B;
    static constexpr uint8_t REG_SYSTEM_THRESH_HIGH        = 0x0C;
    static constexpr uint8_t REG_SYSTEM_THRESH_LOW         = 0x0E;
    static constexpr uint8_t REG_RESULT_INTERRUPT_STATUS   = 0x13;
    static constexpr uint8_t REG_RESULT_RANGE_STATUS       = 0x14;
    static constexpr uint8_t REG_CROSSTALK_COMPENSATION    = 0x20;
    static constexpr uint8_t REG_PART_TO_PART_RANGE_OFFSET = 0x28;
    static constexpr uint8_t REG_PHASECAL_CONFIG_TIMEOUT   = 0x30;
    static constexpr uint8_t REG_GLOBAL_CONFIG_VCSEL_WIDTH = 0x32;
    static constexpr uint8_t REG_FINAL_MIN_COUNT_RATE_RTN  = 0x44;
    static constexpr uint8_t REG_MSRC_CONFIG_TIMEOUT       = 0x46;
    static constexpr uint8_t REG_FINAL_VALID_PHASE_LOW     = 0x47;
    static constexpr uint8_t REG_FINAL_VALID_PHASE_HIGH    = 0x48;
    static constexpr uint8_t REG_DYNAMIC_SPAD_NUM_REQ      = 0x4E;
    static constexpr uint8_t REG_DYNAMIC_SPAD_START_OFFSET = 0x4F;
    static constexpr uint8_t REG_PRE_RANGE_VCSEL_PERIOD    = 0x50;
    static constexpr uint8_t REG_PRE_RANGE_TIMEOUT         = 0x51;
    static constexpr uint8_t REG_PRE_VALID_PHASE_LOW       = 0x56;
    static constexpr uint8_t REG_PRE_VALID_PHASE_HIGH      = 0x57;
    static constexpr uint8_t REG_MSRC_CONFIG_CONTROL       = 0x60;
    static constexpr uint8_t REG_FINAL_RANGE_VCSEL_PERIOD  = 0x70;
    static constexpr uint8_t REG_FINAL_RANGE_TIMEOUT       = 0x71;
    static constexpr uint8_t REG_POWER_FORCE               = 0x80;
    static constexpr uint8_t REG_GPIO_HV_MUX_ACTIVE_HIGH   = 0x84;
    static constexpr uint8_t REG_I2C_MODE                  = 0x88;
    static constexpr uint8_t REG_VHV_PAD_EXTSUP_HV         = 0x89;
    static constexpr uint8_t REG_I2C_SLAVE_DEVICE_ADDRESS  = 0x8A;
    static constexpr uint8_t REG_STOP_VARIABLE             = 0x91;
    static constexpr uint8_t REG_SPAD_ENABLES_REF_0        = 0xB0;
    static constexpr uint8_t REG_REF_EN_START_SELECT       = 0xB6;
    static constexpr uint8_t REG_MODEL_ID                  = 0xC0;
    static constexpr uint8_t REG_REVISION_ID               = 0xC2;
    static constexpr uint8_t REG_OSC_CALIBRATE_VAL         = 0xF8;
    static constexpr uint8_t REG_PAGE_SELECT               = 0xFF;

    static constexpr uint8_t SEQ_TCC         = 0x10;
    static constexpr uint8_t SEQ_DSS         = 0x08;
    static constexpr uint8_t SEQ_MSRC        = 0x04;
    static constexpr uint8_t SEQ_PRE_RANGE   = 0x40;
    static constexpr uint8_t SEQ_FINAL_RANGE = 0x80;
    static constexpr uint8_t SEQ_OPERATING   = 0xE8;

    /** @brief Sequence-step timeouts read from the registers. */
    struct StepTimeouts {
        uint16_t prePclks;
        uint32_t msrcUs;
        uint32_t preMclks;
        uint32_t preUs;
        uint16_t finalPclks;
        uint32_t finalUs;
    };

    uint8_t _stopVariable = 0;
    uint8_t _rangeStatus = 0;
    uint32_t _timingBudgetUs = 0;
    uint8_t _result[12] = {};

    void _wr(uint8_t reg, uint8_t value) { _wr8(reg, value); }
    uint8_t _rd(uint8_t reg) { return _rd8(reg); }
    bool _wait(uint8_t reg, uint8_t mask, bool untilSet);
    uint8_t _pollInterruptStatus() override;

    bool _init();
    bool _spadInfo(uint8_t& count, bool& isAperture);
    bool _singleRefCalibration(uint8_t vhvInit);
    bool _refCalibration();
    StepTimeouts _stepTimeouts(uint8_t enables);
    uint32_t _fixedOverheadUs(uint8_t enables, const StepTimeouts& t);
    uint32_t _getTimingBudget();
    bool _setTimingBudget(uint32_t budgetUs);
    void _stopVariablePreamble();
    void _readResult();
    uint16_t _waitAndRead();

    static uint16_t _decodeVcsel(uint8_t reg) { return (uint16_t)((reg + 1) << 1); }
    static uint8_t _encodeVcsel(uint16_t pclks) { return (uint8_t)((pclks >> 1) - 1); }
    static uint32_t _macroPeriodNs(uint16_t pclks) { return ((uint32_t)2304 * pclks * 1655 + 500) / 1000; }
    static uint32_t _mclksToUs(uint32_t mclks, uint16_t pclks);
    static uint32_t _usToMclks(uint32_t us, uint16_t pclks);
    static uint32_t _decodeTimeout(uint16_t reg);
    static uint16_t _encodeTimeout(uint32_t mclks);
};

/** @brief VL53L0X full interface — extends Minimal with continuous and timed
 *  ranging, the full measurement record, timing budget, signal-rate limit,
 *  VCSEL pulse periods, ranging profiles, offset and crosstalk compensation,
 *  reference recalibration, address change, distance thresholds,
 *  identification, and the Level-2 interrupt API.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class VL53L0XFull : public VL53L0XMinimal {
public:
    /** @brief VCSEL period type for setVcselPulsePeriod() / vcselPulsePeriod(). */
    enum class VcselPeriodType : uint8_t {
        PreRange,    ///< Pre-range: 12, 14, 16 or 18 PCLKs
        FinalRange,  ///< Final-range: 8, 10, 12 or 14 PCLKs
    };

    /** @brief Ranging profile for setProfile(). */
    enum class Profile : uint8_t {
        Default,       ///< 0.25 MCPS, 14/10 PCLKs, 33 ms
        LongRange,     ///< 0.10 MCPS, 18/14 PCLKs, 33 ms (dark conditions)
        HighSpeed,     ///< 0.25 MCPS, 14/10 PCLKs, 20 ms
        HighAccuracy,  ///< 0.25 MCPS, 14/10 PCLKs, 200 ms
    };

    /** @brief Decoded result block, as returned by readMeasurement(). */
    struct Measurement {
        uint16_t distanceMm;       ///< Range in mm
        uint8_t rangeStatus;       ///< Device range status 0–15 (11 = valid)
        float signalRateMcps;      ///< Return signal rate in MCPS
        float ambientRateMcps;     ///< Ambient rate in MCPS
        float effectiveSpadCount;  ///< Effective SPAD return count
    };

    explicit VL53L0XFull(Connection& connection);

    /** @brief Start continuous ranging.
     *  @param periodMs 0 for back-to-back mode; otherwise timed mode with this
     *                  inter-measurement period in ms (should be ≥ the timing budget). */
    void startContinuous(uint32_t periodMs = 0);

    /** @brief Stop continuous ranging. Does not wait for a running measurement. */
    void stopContinuous();

    /** @brief Wait for the next continuous-mode result and read it.
     *  @return Distance in mm (check rangeValid()), or TIMEOUT after 500 ms. */
    uint16_t readContinuous();

    /** @brief Report whether a measurement is pending (non-blocking).
     *  @return true if RESULT_INTERRUPT_STATUS bits 2:0 are non-zero. */
    bool dataReady();

    /** @brief Read the full result block and clear the interrupt (non-blocking).
     *  @return Decoded measurement record. */
    Measurement readMeasurement();

    /** @brief Device range status of the most recent measurement.
     *  @return 0–15; 11 = valid, 4 = no target (MSRC). */
    uint8_t rangeStatus();

    /** @brief Set the per-measurement timing budget.
     *  @param budgetUs Budget in µs, ≥ 20000.
     *  @return false (no write) if below 20000 µs or the enabled steps' overhead. */
    bool setTimingBudget(uint32_t budgetUs);

    /** @brief Compute the timing budget from the current registers.
     *  @return Budget in µs. */
    uint32_t timingBudget();

    /** @brief Set the final-range return signal-rate limit; lower values extend
     *  range but admit noisier readings.
     *  @param limitMcps Limit in MCPS, 0 to 511.99.
     *  @return false (no write) if out of range. */
    bool setSignalRateLimit(float limitMcps);

    /** @brief Read the final-range return signal-rate limit.
     *  @return Limit in MCPS. */
    float signalRateLimit();

    /** @brief Set a VCSEL pulse period, then re-apply the timing budget and redo
     *  the phase reference calibration.
     *  @param type  Pre-range or final-range.
     *  @param pclks Pre-range 12, 14, 16 or 18; final-range 8, 10, 12 or 14.
     *  @return false for an invalid period (no write) or a calibration timeout. */
    bool setVcselPulsePeriod(VcselPeriodType type, uint8_t pclks);

    /** @brief Read a VCSEL pulse period.
     *  @param type Pre-range or final-range.
     *  @return Period in PCLKs. */
    uint8_t vcselPulsePeriod(VcselPeriodType type);

    /** @brief Apply a ranging profile: signal-rate limit, VCSEL periods (pre
     *  first), then timing budget.
     *  @param profile Profile to apply.
     *  @return false if a step failed (calibration timeout). */
    bool setProfile(Profile profile);

    /** @brief Override the part-to-part range offset (volatile).
     *  @param offsetMm Offset in mm, −512.0 to 511.75 (0.25 mm steps).
     *  @return false (no write) if out of range. */
    bool setOffset(float offsetMm);

    /** @brief Read the part-to-part range offset.
     *  @return Offset in mm. */
    float offset();

    /** @brief Set the crosstalk compensation peak rate (volatile).
     *  @param rateMcps 0 disables; otherwise 0 < rate < 8.0 MCPS, from the
     *                  host's own cover-glass calibration.
     *  @return false (no write) if out of range. */
    bool setCrosstalkCompensation(float rateMcps);

    /** @brief Re-run the VHV and phase reference calibrations.
     *
     *  Call in software standby (not while continuous ranging), and after
     *  the die temperature changes by more than 8 °C.
     *
     *  @return false if a calibration timed out. */
    bool recalibrate();

    /** @brief Change the chip's I²C address (volatile).
     *
     *  The chip answers on the new address immediately; this driver instance
     *  becomes unusable. Construct a new Connection at the new address and a
     *  new driver.
     *
     *  @param address New 7-bit address, 0x08–0x77.
     *  @return false (no write) if out of range. */
    bool setAddress(uint8_t address);

    /** @brief Set the distance thresholds used by the threshold interrupt sources.
     *  @param lowMm  Low threshold in mm (2 mm resolution).
     *  @param highMm High threshold in mm, lowMm ≤ highMm ≤ 8190.
     *  @return false (no write) if out of range. */
    bool setInterruptThresholds(uint16_t lowMm, uint16_t highMm);

    /** @brief Read the distance thresholds.
     *  @param lowMm  Receives the low threshold in mm.
     *  @param highMm Receives the high threshold in mm. */
    void interruptThresholds(uint16_t& lowMm, uint16_t& highMm);

    /** @brief Read IDENTIFICATION_MODEL_ID.
     *  @return 0xEE. */
    uint8_t modelId();

    /** @brief Read IDENTIFICATION_REVISION_ID.
     *  @return Revision ID (0x10 on current silicon). */
    uint8_t revisionId();

    /** @brief Select the GPIO1 interrupt source (replaces the active one).
     *
     *  With a threshold source active, dataReady()/readContinuous() only see
     *  a pending status when the threshold condition is met.
     *
     *  @param source One of the SOURCE_* constants (SOURCE_IN_WINDOW is not supported).
     *  @return false (no write) if source is not 1–4. */
    bool enableInterrupt(uint8_t source);

    /** @brief Disable GPIO1 interrupts if source is the active one.
     *  @param source One of the SOURCE_* constants. */
    void disableInterrupt(uint8_t source);

    /** @brief Read and clear the pending interrupt status.
     *  @return The SOURCE_* value that fired, or 0 if nothing is pending. */
    uint8_t pollInterrupt();

    /** @brief Subscribe to GPIO1 falling edges (active low).
     *
     *  Requires an InputPin — either @p intPin or connection.intPin(). The
     *  driver reads and clears the status before invoking the callback.
     *
     *  @param callback Called with the SOURCE_* value that fired.
     *  @param intPin   Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();
};
