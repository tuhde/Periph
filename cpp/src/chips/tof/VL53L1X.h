#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "VL53Base.h"

/** @brief VL53L1X long-distance Time-of-Flight laser-ranging sensor — minimal interface.
 *
 *  940 nm VCSEL emitter, 16×16 SPAD receiving array behind a lens and an
 *  embedded ranging microcontroller measuring absolute distance up to 4 m
 *  at up to 50 Hz. Two distance modes (short ~1.3 m, robust in sunlight;
 *  long ~3.6–4 m in the dark), a 15–500 ms timing budget and a programmable
 *  region of interest (4×4 to 16×16 SPADs). The datasheet has no register
 *  map: registers, the default configuration block and all sequences follow
 *  ST's Ultra Lite Driver (STSW-IMG009). Registers use a 16-bit index;
 *  multi-byte registers are big-endian.
 *
 *  Pin-to-pin compatible with the VL53L0X and shares its VL53Base (register
 *  access, polling, boot wait, interrupt delivery, re-addressing) and public
 *  API shape; I2C_ADDRESS and the SOURCE_* constants are inherited from it.
 *
 *  The constructor runs the full initialization sequence (boot wait,
 *  firmware boot poll, sensor ID check, ULD default configuration, 2V8 I/O,
 *  GPIO1 active low, settling ranging) and leaves the chip idle in long
 *  distance mode with a 100 ms timing budget; construct it only after the
 *  bus is started. It calls abort() if the sensor ID is not 0xEACC or an
 *  init poll times out (exceptions are disabled on every C++ target in this
 *  repo). If the connection has an enPin (XSHUT), it is driven high first.
 *
 *  Multiple sensors on one bus: all VL53L0X/VL53L1X sensors power up at
 *  0x29. Hold every sensor's XSHUT low (each Connection disabled), then for
 *  each sensor in turn enable its XSHUT, construct a driver on 0x29, call
 *  setAddress(new), and build the real driver on a Connection at the new
 *  address. The new address is volatile.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class VL53L1XMinimal : public VL53Base {
public:
    /** @brief Expected IDENTIFICATION__MODEL_ID + MODULE_TYPE word. */
    static constexpr uint16_t SENSOR_ID = 0xEACC;
    /** @brief IDENTIFICATION__MODEL_ID. */
    static constexpr uint8_t MODEL_ID = 0xEA;
    /** @brief IDENTIFICATION__MODULE_TYPE. */
    static constexpr uint8_t MODULE_TYPE = 0xCC;
    /** @brief Mapped range status meaning "range valid". */
    static constexpr uint8_t RANGE_STATUS_VALID = 0;
    /** @brief Returned by distance()/readContinuous() when a poll loop times out (500 ms). */
    static constexpr uint16_t TIMEOUT = 0xFFFF;

    explicit VL53L1XMinimal(Connection& connection);

    /** @brief Take one single-shot measurement.
     *
     *  Blocks for about one timing budget (100 ms by default). Returns the
     *  raw range even when the measurement is not valid; check rangeValid().
     *
     *  @return Distance in mm, or TIMEOUT if the measurement did not complete
     *          within 500 ms. */
    uint16_t distance();

    /** @brief Report whether the most recent measurement was valid.
     *  @return true iff the mapped range status was 0 (range valid). */
    bool rangeValid();

protected:
    static constexpr uint16_t REG_I2C_SLAVE_DEVICE_ADDRESS = 0x0001;
    static constexpr uint16_t REG_VHV_CONFIG_LOOP_BOUND    = 0x0008;
    static constexpr uint16_t REG_VHV_INIT                 = 0x000B;
    static constexpr uint16_t REG_XTALK_PLANE_OFFSET       = 0x0016;
    static constexpr uint16_t REG_XTALK_X_GRADIENT         = 0x0018;
    static constexpr uint16_t REG_XTALK_Y_GRADIENT         = 0x001A;
    static constexpr uint16_t REG_PART_TO_PART_OFFSET      = 0x001E;
    static constexpr uint16_t REG_MM_INNER_OFFSET          = 0x0020;
    static constexpr uint16_t REG_MM_OUTER_OFFSET          = 0x0022;
    static constexpr uint16_t REG_PAD_I2C_HV_EXTSUP        = 0x002E;
    static constexpr uint16_t REG_GPIO_EXTSUP_HV           = 0x002F;
    static constexpr uint16_t REG_GPIO_HV_MUX_CTRL         = 0x0030;
    static constexpr uint16_t REG_GPIO_TIO_HV_STATUS       = 0x0031;
    static constexpr uint16_t REG_INTERRUPT_CONFIG_GPIO    = 0x0046;
    static constexpr uint16_t REG_PHASECAL_TIMEOUT         = 0x004B;
    static constexpr uint16_t REG_RANGE_TIMEOUT_A          = 0x005E;
    static constexpr uint16_t REG_RANGE_VCSEL_PERIOD_A     = 0x0060;
    static constexpr uint16_t REG_RANGE_TIMEOUT_B          = 0x0061;
    static constexpr uint16_t REG_RANGE_VCSEL_PERIOD_B     = 0x0063;
    static constexpr uint16_t REG_SIGMA_THRESH             = 0x0064;
    static constexpr uint16_t REG_MIN_COUNT_RATE_RTN_LIMIT = 0x0066;
    static constexpr uint16_t REG_RANGE_VALID_PHASE_HIGH   = 0x0069;
    static constexpr uint16_t REG_INTERMEASUREMENT_PERIOD  = 0x006C;
    static constexpr uint16_t REG_THRESH_HIGH              = 0x0072;
    static constexpr uint16_t REG_THRESH_LOW               = 0x0074;
    static constexpr uint16_t REG_SD_WOI_SD0               = 0x0078;
    static constexpr uint16_t REG_SD_INITIAL_PHASE_SD0     = 0x007A;
    static constexpr uint16_t REG_ROI_CENTRE_SPAD          = 0x007F;
    static constexpr uint16_t REG_ROI_XY_SIZE              = 0x0080;
    static constexpr uint16_t REG_INTERRUPT_CLEAR          = 0x0086;
    static constexpr uint16_t REG_MODE_START               = 0x0087;
    static constexpr uint16_t REG_RESULT_RANGE_STATUS      = 0x0089;
    static constexpr uint16_t REG_OSC_CALIBRATE_VAL        = 0x00DE;
    static constexpr uint16_t REG_FIRMWARE_SYSTEM_STATUS   = 0x00E5;
    static constexpr uint16_t REG_MODEL_ID                 = 0x010F;
    static constexpr uint16_t REG_MODULE_TYPE              = 0x0110;
    static constexpr uint16_t REG_REVISION_ID              = 0x0111;
    static constexpr uint16_t REG_MODE_ROI_CENTRE_SPAD     = 0x013E;

    uint8_t _rangeStatus = 255;
    uint8_t _result[17] = {};

    bool _init();
    bool _dataReady();
    void _readResult();
    uint16_t _waitAndRead();
    uint16_t _resultWord(size_t i) const { return (uint16_t)((_result[i] << 8) | _result[i + 1]); }
    uint8_t _activeSource();
    uint8_t _pollInterruptStatus() override;
};

/** @brief VL53L1X full interface — extends Minimal with timed continuous
 *  ranging, the full measurement record, distance mode, timing budget,
 *  inter-measurement period, signal and sigma thresholds, region of
 *  interest, offset and crosstalk compensation with calibration helpers,
 *  temperature update, address change, distance thresholds,
 *  identification, and the Level-2 interrupt API.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class VL53L1XFull : public VL53L1XMinimal {
public:
    /** @brief Distance mode for setDistanceMode() / distanceMode(). */
    enum class DistanceMode : uint8_t {
        Short,    ///< ~1.3 m, robust against ambient light
        Long,     ///< up to 4 m in the dark (default)
        Unknown,  ///< returned by distanceMode() for an unrecognised register value
    };

    /** @brief Decoded result block, as returned by readMeasurement(). */
    struct Measurement {
        uint16_t distanceMm;       ///< Range in mm
        uint8_t rangeStatus;       ///< Mapped range status (0 = valid, 255 = no update)
        float signalRateMcps;      ///< Return signal rate in MCPS
        float ambientRateMcps;     ///< Ambient rate in MCPS
        float effectiveSpadCount;  ///< Effective SPAD return count
    };

    explicit VL53L1XFull(Connection& connection);

    /** @brief Start timed continuous ranging.
     *  @param periodMs Inter-measurement period in ms, 0–60000. 0 (and any value
     *                  below the timing budget) runs at the timing budget, i.e.
     *                  back-to-back — the chip requires period ≥ budget.
     *  @return false (no write) if periodMs > 60000. */
    bool startContinuous(uint32_t periodMs = 0);

    /** @brief Stop continuous ranging. Does not wait for a running measurement. */
    void stopContinuous();

    /** @brief Wait for the next continuous-mode result and read it.
     *  @return Distance in mm (check rangeValid()), or TIMEOUT after 500 ms. */
    uint16_t readContinuous();

    /** @brief Report whether a measurement is pending (non-blocking).
     *  @return true if GPIO__TIO_HV_STATUS shows the GPIO1 line asserted. */
    bool dataReady();

    /** @brief Read the full result block and clear the interrupt (non-blocking).
     *  @return Decoded measurement record. */
    Measurement readMeasurement();

    /** @brief Mapped range status of the most recent measurement.
     *  @return 0 = valid, 1 = sigma fail, 2 = signal fail, 4 = out of bounds,
     *          7 = wrap-around, 255 = no update. */
    uint8_t rangeStatus();

    /** @brief Set the per-measurement timing budget (ULD table values only).
     *  @param budgetUs 15000 (short mode only), 20000, 33000, 50000, 100000,
     *                  200000 or 500000.
     *  @return false (no write) if not in the table for the current distance mode. */
    bool setTimingBudget(uint32_t budgetUs);

    /** @brief Decode the timing budget from RANGE_CONFIG__TIMEOUT_MACROP_A.
     *  @return Budget in µs, or 0 if the register holds no table value. */
    uint32_t timingBudget();

    /** @brief Select short or long distance mode, keeping the timing budget
     *  (100 ms if the current budget is unknown).
     *  @param mode Short or Long.
     *  @return false (no write) for Unknown, or switching to Long at a 15 ms budget. */
    bool setDistanceMode(DistanceMode mode);

    /** @brief Read the current distance mode.
     *  @return Short, Long, or Unknown. */
    DistanceMode distanceMode();

    /** @brief Set the continuous-mode inter-measurement period; should be ≥ the
     *  timing budget (startContinuous() enforces this).
     *  @param periodMs Period in ms, 1–60000.
     *  @return false (no write) if out of range. */
    bool setInterMeasurement(uint32_t periodMs);

    /** @brief Read the continuous-mode inter-measurement period.
     *  @return Period in ms (0 if the oscillator calibration reads 0). */
    uint32_t interMeasurement();

    /** @brief Set the minimum return signal rate for a valid result.
     *  @param limitMcps Limit in MCPS, 0 to 511.99 (default 1.0).
     *  @return false (no write) if out of range. */
    bool setSignalRateLimit(float limitMcps);

    /** @brief Read the minimum return signal rate.
     *  @return Limit in MCPS. */
    float signalRateLimit();

    /** @brief Set the maximum estimated standard deviation for a valid result.
     *  @param sigmaMm Threshold in mm, 0–16383 (default 90).
     *  @return false (no write) if out of range. */
    bool setSigmaThreshold(uint16_t sigmaMm);

    /** @brief Read the sigma threshold.
     *  @return Threshold in mm. */
    uint16_t sigmaThreshold();

    /** @brief Set the receiving region-of-interest size; sizes above 10 SPADs
     *  re-centre the ROI on SPAD 199 (array centre).
     *  @param width  ROI width in SPADs, 4–16.
     *  @param height ROI height in SPADs, 4–16.
     *  @return false (no write) if out of range. */
    bool setRoi(uint8_t width, uint8_t height);

    /** @brief Read the region-of-interest size.
     *  @param width  Receives the width in SPADs.
     *  @param height Receives the height in SPADs. */
    void roi(uint8_t& width, uint8_t& height);

    /** @brief Move the region of interest to a centre SPAD (ST UM2555 numbering,
     *  199 = array centre). The caller keeps the ROI inside the array.
     *  @param spad SPAD number 0–255. */
    void setRoiCenter(uint8_t spad);

    /** @brief Read the region-of-interest centre SPAD.
     *  @return SPAD number. */
    uint8_t roiCenter();

    /** @brief Read the factory-measured optical-centre SPAD from NVM.
     *  @return SPAD number; pass to setRoiCenter() to align the ROI with the lens. */
    uint8_t opticalCenter();

    /** @brief Override the part-to-part range offset (volatile).
     *  @param offsetMm Offset in mm, −1024.0 to 1023.75 (0.25 mm steps).
     *  @return false (no write) if out of range. */
    bool setOffset(float offsetMm);

    /** @brief Read the part-to-part range offset.
     *  @return Offset in mm. */
    float offset();

    /** @brief Set the per-SPAD crosstalk compensation rate (volatile).
     *  @param rateMcps 0 disables; otherwise 0 < rate < 0.128 MCPS.
     *  @return false (no write) if out of range. */
    bool setCrosstalkCompensation(float rateMcps);

    /** @brief Read the per-SPAD crosstalk compensation rate.
     *  @return Rate in MCPS. */
    float crosstalkCompensation();

    /** @brief Measure and apply the range offset against a target at a known
     *  distance (ULD CalibrateOffset; ST recommends 88 % white at 140 mm).
     *
     *  Ranges 50 times with the offset zeroed; must not be called while
     *  ranging. Store the result and re-apply it with setOffset() after each
     *  power-up.
     *
     *  @param targetMm True target distance in mm.
     *  @param offsetMm Receives the applied offset in mm (target − mean distance).
     *  @return false on a result timeout or an out-of-range offset. */
    bool calibrateOffset(uint16_t targetMm, float& offsetMm);

    /** @brief Measure and apply crosstalk compensation for a cover glass (ULD
     *  CalibrateXtalk; ST uses a 17 % grey target where the sensor starts to
     *  under-range).
     *
     *  Ranges 50 times with compensation off; must not be called while
     *  ranging. Store the result and re-apply it with
     *  setCrosstalkCompensation() after each power-up.
     *
     *  @param targetMm True target distance in mm (> 0).
     *  @param rateMcps Receives the applied per-SPAD rate in MCPS (0–0.127).
     *  @return false for targetMm == 0 or a result timeout. */
    bool calibrateCrosstalk(uint16_t targetMm, float& rateMcps);

    /** @brief Run the temperature update (ULD StartTemperatureUpdate).
     *
     *  Call in software standby (not while ranging), after the temperature
     *  changes by more than about 8 °C.
     *
     *  @return false if the update ranging timed out. */
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
     *  @param lowMm  Low threshold in mm.
     *  @param highMm High threshold in mm, lowMm ≤ highMm.
     *  @return false (no write) if highMm < lowMm. */
    bool setInterruptThresholds(uint16_t lowMm, uint16_t highMm);

    /** @brief Read the distance thresholds.
     *  @param lowMm  Receives the low threshold in mm.
     *  @param highMm Receives the high threshold in mm. */
    void interruptThresholds(uint16_t& lowMm, uint16_t& highMm);

    /** @brief Read IDENTIFICATION__MODEL_ID.
     *  @return 0xEA. */
    uint8_t modelId();

    /** @brief Read IDENTIFICATION__MODULE_TYPE.
     *  @return 0xCC. */
    uint8_t moduleType();

    /** @brief Read IDENTIFICATION__REVISION_ID (mask revision).
     *  @return 0x10. */
    uint8_t revisionId();

    /** @brief Select the GPIO1 interrupt source (replaces the active one).
     *
     *  With a threshold source active, dataReady()/readContinuous() only see
     *  a pending result when the threshold condition is met.
     *
     *  @param source One of the SOURCE_* constants (1–5).
     *  @return false (no write) if source is not 1–5. */
    bool enableInterrupt(uint8_t source);

    /** @brief Revert to SOURCE_NEW_SAMPLE_READY if @p source is the active
     *  threshold source. The chip has no disabled state, so disabling
     *  SOURCE_NEW_SAMPLE_READY is a no-op; use offInterrupt() to stop callbacks.
     *  @param source One of the SOURCE_* constants. */
    void disableInterrupt(uint8_t source);

    /** @brief Read and clear a pending interrupt.
     *  @return The active SOURCE_* value if GPIO1 is asserted, else 0. */
    uint8_t pollInterrupt();

    /** @brief Subscribe to GPIO1 falling edges (active low).
     *
     *  Requires an InputPin — either @p intPin or connection.intPin(). The
     *  driver clears the interrupt before invoking the callback.
     *
     *  @param callback Called with the active SOURCE_* value.
     *  @param intPin   Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

protected:
    bool _collect(void (*accumulate)(const uint8_t* result, void* ctx), void* ctx);
};
