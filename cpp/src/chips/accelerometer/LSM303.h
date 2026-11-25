#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief LSM303 3-axis accelerometer + magnetometer e-compass — minimal interface.
 *
 *  The LSM303DLHC combines a three-axis MEMS accelerometer and a three-axis
 *  magnetometer behind **two independent I²C slave addresses**: the
 *  accelerometer at 0x19 and the magnetometer at 0x1E. The driver therefore
 *  takes two connections, which must sit on the same bus.
 *
 *  Default configuration (written by init()):
 *  - Accelerometer: ±2 g, 100 Hz, high-resolution (12-bit), all three axes
 *    enabled, BDU set, high-pass filter bypassed, FIFO bypassed, all
 *    interrupt routing cleared
 *  - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
 *    temperature sensor off
 *
 *  The magnetometer output order is X, Z, Y (not X, Y, Z) and its byte order
 *  is high byte first — the opposite of the accelerometer.
 *
 *  @param accelConnection RegisterConnection (I²C) bound to 0x19.
 *  @param magConnection   RegisterConnection (I²C) bound to 0x1E.
 */
class LSM303Minimal {
public:
    /** @brief Fixed I²C address of the accelerometer slave. */
    static constexpr uint8_t I2C_ADDRESS_ACCEL = 0x19;
    /** @brief Fixed I²C address of the magnetometer slave. */
    static constexpr uint8_t I2C_ADDRESS_MAG = 0x1E;

    /** @brief Accelerometer operating mode: low-power, 8-bit output. */
    static constexpr uint8_t MODE_LOW_POWER = 0;
    /** @brief Accelerometer operating mode: normal, 10-bit output. */
    static constexpr uint8_t MODE_NORMAL = 1;
    /** @brief Accelerometer operating mode: high-resolution, 12-bit output. */
    static constexpr uint8_t MODE_HIGH_RESOLUTION = 2;

    LSM303Minimal(RegisterConnection& accelConnection, RegisterConnection& magConnection);

    /** @brief Run the datasheet initialization sequence.
     *
     *  Verifies the magnetometer identity (0x48 0x34 0x33), puts the
     *  accelerometer control registers into a known state, starts both dies
     *  and waits for the first samples.
     */
    void init();

    /** @brief Read the acceleration.
     *
     *  Burst-reads the six output registers so all three axes belong to the
     *  same sample.
     *
     *  @param[out] x X acceleration in *g*.
     *  @param[out] y Y acceleration in *g*.
     *  @param[out] z Z acceleration in *g*.
     */
    void readAcceleration(float& x, float& y, float& z);

    /** @brief Read the magnetic flux density.
     *
     *  Burst-reads the six output registers (X, Z, Y on the wire) and
     *  reorders them to X, Y, Z.
     *
     *  @param[out] x X magnetic flux density in µT.
     *  @param[out] y Y magnetic flux density in µT.
     *  @param[out] z Z magnetic flux density in µT.
     */
    void readMagnetic(float& x, float& y, float& z);

protected:
    // Accelerometer registers.
    static constexpr uint8_t REG_CTRL1_A        = 0x20;
    static constexpr uint8_t REG_CTRL2_A        = 0x21;
    static constexpr uint8_t REG_CTRL3_A        = 0x22;
    static constexpr uint8_t REG_CTRL4_A        = 0x23;
    static constexpr uint8_t REG_CTRL5_A        = 0x24;
    static constexpr uint8_t REG_CTRL6_A        = 0x25;
    static constexpr uint8_t REG_REFERENCE_A    = 0x26;
    static constexpr uint8_t REG_STATUS_A       = 0x27;
    static constexpr uint8_t REG_OUT_X_L_A      = 0x28;
    static constexpr uint8_t REG_FIFO_CTRL_A    = 0x2E;
    static constexpr uint8_t REG_FIFO_SRC_A     = 0x2F;
    static constexpr uint8_t REG_INT1_CFG_A     = 0x30;
    static constexpr uint8_t REG_INT1_SOURCE_A  = 0x31;
    static constexpr uint8_t REG_INT1_THS_A     = 0x32;
    static constexpr uint8_t REG_INT1_DURATION_A = 0x33;
    static constexpr uint8_t REG_INT2_CFG_A     = 0x34;
    static constexpr uint8_t REG_INT2_SOURCE_A  = 0x35;
    static constexpr uint8_t REG_INT2_THS_A     = 0x36;
    static constexpr uint8_t REG_INT2_DURATION_A = 0x37;
    static constexpr uint8_t REG_CLICK_CFG_A    = 0x38;
    static constexpr uint8_t REG_CLICK_SRC_A    = 0x39;
    static constexpr uint8_t REG_CLICK_THS_A    = 0x3A;
    static constexpr uint8_t REG_TIME_LIMIT_A   = 0x3B;
    static constexpr uint8_t REG_TIME_LATENCY_A = 0x3C;
    static constexpr uint8_t REG_TIME_WINDOW_A  = 0x3D;

    // Magnetometer registers.
    static constexpr uint8_t REG_CRA_M          = 0x00;
    static constexpr uint8_t REG_CRB_M          = 0x01;
    static constexpr uint8_t REG_MR_M           = 0x02;
    static constexpr uint8_t REG_OUT_X_H_M      = 0x03;
    static constexpr uint8_t REG_SR_M           = 0x09;
    static constexpr uint8_t REG_IRA_M          = 0x0A;
    static constexpr uint8_t REG_TEMP_OUT_H_M   = 0x31;

    // Bit 7 of the accelerometer sub-address enables address auto-increment.
    static constexpr uint8_t ACCEL_AUTO_INCREMENT = 0x80;

    // Expected magnetometer identity: ASCII "H43".
    static constexpr uint8_t MAG_ID_A = 0x48;
    static constexpr uint8_t MAG_ID_B = 0x34;
    static constexpr uint8_t MAG_ID_C = 0x33;

    RegisterConnection& _accel;
    RegisterConnection& _mag;

    // Cached configuration, kept in sync with the registers.
    uint8_t  _mode = MODE_HIGH_RESOLUTION;
    uint8_t  _fsG = 2;
    uint16_t _odrHz = 100;
    uint16_t _magRangeUt = 130;

    void _accelRead(uint8_t reg, uint8_t* buf, size_t len);
    void _accelWrite(uint8_t reg, uint8_t value);
    void _magRead(uint8_t reg, uint8_t* buf, size_t len);
    void _magWrite(uint8_t reg, uint8_t value);
    uint8_t _accelReadReg(uint8_t reg);
    uint8_t _magReadReg(uint8_t reg);

    void _convertAccel(const uint8_t* raw, float& x, float& y, float& z) const;
    void _convertAccelRaw(const uint8_t* raw, int16_t& x, int16_t& y, int16_t& z) const;
    void _reorderMag(const uint8_t* raw, int16_t& x, int16_t& y, int16_t& z) const;
    uint8_t _shift() const;
    float _sensitivityMg() const;
    void _magGains(float& xy, float& z) const;
};

/** @brief LSM303 full interface — extends LSM303Minimal with the complete chip API.
 *
 *  Adds:
 *  - Accelerometer range, output data rate, operating mode and per-axis
 *    enable, plus power-down
 *  - Raw acceleration and raw magnetic readings
 *  - High-pass filter configuration and the reference value
 *  - The 32-level FIFO in bypass / FIFO / stream / trigger mode
 *  - Two inertial interrupt generators with thresholds, durations, free-fall
 *    and 6D/4D orientation recognition, plus latching
 *  - Single and double click detection
 *  - Magnetometer gain, data rate, operating mode, identity, status and the
 *    relative temperature sensor
 *  - A software compass heading, optionally tilt-compensated
 *  - The Level-3 interrupt API (INT1 / INT2)
 *
 *  @param accelConnection RegisterConnection (I²C) bound to 0x19.
 *  @param magConnection   RegisterConnection (I²C) bound to 0x1E.
 */
class LSM303Full : public LSM303Minimal {
public:
    /** @brief New accelerometer XYZ sample (STATUS_REG_A.ZYXDA), INT1. */
    static constexpr uint8_t SOURCE_DATA_READY = 0x01;
    /** @brief FIFO content exceeds the watermark (FIFO_SRC_REG_A.WTM), INT1. */
    static constexpr uint8_t SOURCE_WATERMARK = 0x02;
    /** @brief FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO), INT1. */
    static constexpr uint8_t SOURCE_OVERRUN = 0x04;
    /** @brief Inertial generator 1 event (INT1_SOURCE_A.IA), INT1 or INT2. */
    static constexpr uint8_t SOURCE_IA1 = 0x08;
    /** @brief Inertial generator 2 event (INT2_SOURCE_A.IA), INT1 or INT2. */
    static constexpr uint8_t SOURCE_IA2 = 0x10;
    /** @brief Single / double click (CLICK_SRC_A.IA), INT1 or INT2. */
    static constexpr uint8_t SOURCE_CLICK = 0x20;
    /** @brief Boot procedure running — INT2 only, never reported by pollInterrupt(). */
    static constexpr uint8_t SOURCE_BOOT = 0x40;
    /** @brief New magnetometer sample (SR_REG_M.DRDY) — dedicated DRDY pin. */
    static constexpr uint8_t SOURCE_MAG_DATA_READY = 0x80;

    /** @brief FIFO bypass. */
    static constexpr uint8_t FIFO_BYPASS = 0;
    /** @brief FIFO mode. */
    static constexpr uint8_t FIFO_MODE = 1;
    /** @brief FIFO stream mode. */
    static constexpr uint8_t FIFO_STREAM = 2;
    /** @brief FIFO trigger mode. */
    static constexpr uint8_t FIFO_TRIGGER = 3;

    /** @brief Magnetometer continuous conversion. */
    static constexpr uint8_t MAG_MODE_CONTINUOUS = 0;
    /** @brief Magnetometer single conversion. */
    static constexpr uint8_t MAG_MODE_SINGLE = 1;
    /** @brief Magnetometer sleep. */
    static constexpr uint8_t MAG_MODE_SLEEP = 2;

    /** @brief Click detector axis mask: X. */
    static constexpr uint8_t CLICK_AXIS_X = 0x01;
    /** @brief Click detector axis mask: Y. */
    static constexpr uint8_t CLICK_AXIS_Y = 0x02;
    /** @brief Click detector axis mask: Z. */
    static constexpr uint8_t CLICK_AXIS_Z = 0x04;

    LSM303Full(RegisterConnection& accelConnection, RegisterConnection& magConnection);

    /** @brief Set the accelerometer full-scale range.
     *  @param rangeG 2, 4, 8 or 16 (*g*).
     *  @return true if written, false for an unsupported range (no write). */
    bool setRange(uint8_t rangeG);

    /** @brief Set the accelerometer output data rate.
     *  @param rateHz 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620
     *                 or 5376. 1620 and 5376 Hz exist in low-power mode only,
     *                 1344 Hz in normal and high-resolution mode only.
     *  @return true if written, false for an unsupported or illegal rate. */
    bool setDataRate(uint16_t rateHz);

    /** @brief Set the accelerometer operating mode.
     *  @param mode MODE_LOW_POWER, MODE_NORMAL or MODE_HIGH_RESOLUTION.
     *  @return true if written, false for an unsupported mode. */
    bool setMode(uint8_t mode);

    /** @brief Enable or disable the individual accelerometer axes. */
    void setAxes(bool x, bool y, bool z);

    /** @brief Put the accelerometer into power-down mode.
     *  The register contents are retained. */
    void powerDown();

    /** @brief Read the acceleration as raw counts.
     *  @param[out] x X counts at the current resolution.
     *  @param[out] y Y counts at the current resolution.
     *  @param[out] z Z counts at the current resolution. */
    void readAccelerationRaw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Report whether a new accelerometer sample is available.
     *  @return true if STATUS_REG_A.ZYXDA is set. */
    bool newDataAvailable();

    /** @brief Read the raw accelerometer status register.
     *  @return The STATUS_REG_A byte. */
    uint8_t readStatus();

    /** @brief Configure the accelerometer high-pass filter.
     *  @param mode        HPM code 0-3.
     *  @param cutoff     HPCF code 0-3.
     *  @param filterOutput True to route the filtered data to the output
     *                     registers and the FIFO.
     *  @param onIa1      True to high-pass the input of generator 1.
     *  @param onIa2      True to high-pass the input of generator 2.
     *  @param onClick    True to high-pass the click detector input.
     *  @return true if written, false for an out-of-range mode or cutoff. */
    bool setHighPass(uint8_t mode, uint8_t cutoff, bool filterOutput,
                     bool onIa1, bool onIa2, bool onClick);

    /** @brief Reset the high-pass filter and return the reference value.
     *  @return The reference value (0-255). */
    uint8_t resetHighPass();

    /** @brief Write the high-pass reference value.
     *  @param value Reference value 0-255. */
    void setReference(uint8_t value);

    /** @brief Configure the accelerometer FIFO.
     *  @param mode       FIFO_BYPASS, FIFO_MODE, FIFO_STREAM or FIFO_TRIGGER.
     *  @param watermark  Watermark level 0-31.
     *  @param triggerInt2 True to use INT2 as the trigger source.
     *  @return true if written, false for an out-of-range mode or watermark. */
    bool setFifo(uint8_t mode, uint8_t watermark = 0, bool triggerInt2 = false);

    /** @brief Return the number of unread sample sets in the FIFO.
     *  @return The number of unread sample sets (0-32). */
    uint8_t fifoLevel();

    /** @brief Read sample sets from the FIFO.
     *
     *  Each sample set is popped with one six-byte burst read.
     *
     *  @param[out] out     Buffer of at least @p maxCount * 3 floats.
     *  @param[in]  maxCount Capacity of @p out in sample sets.
     *  @return The number of sample sets written to @p out. */
    uint8_t readFifo(float* out, uint8_t maxCount);

    /** @brief Read the decoded FIFO status. */
    void readFifoStatus(bool& watermark, bool& overrun, bool& empty, uint8_t& level);

    /** @brief Configure one inertial interrupt generator.
     *  @param generator  1 or 2.
     *  @param events     Bit mask of the enabled events — ZHIE (0x20),
     *                    ZLIE (0x10), YHIE (0x08), YLIE (0x04), XHIE (0x02)
     *                    or XLIE (0x01).
     *  @param thresholdG Threshold in *g*, relative to the current range.
     *  @param durationMs Minimum duration in milliseconds.
     *  @param andMode    True to combine the events with AND instead of OR.
     *  @return true if written, false for an invalid generator or mask. */
    bool setMotionInterrupt(uint8_t generator, uint8_t events, float thresholdG,
                            float durationMs = 0, bool andMode = false);

    /** @brief Arm the free-fall detector of one generator.
     *  All three axes are watched for a low event and combined with AND.
     *  @param generator  1 or 2.
     *  @param thresholdG Threshold in *g*.
     *  @param durationMs Minimum duration in milliseconds.
     *  @return true if written, false for an invalid generator. */
    bool setFreeFall(uint8_t generator = 1, float thresholdG = 0.35f, float durationMs = 30.0f);

    /** @brief Arm the 6D orientation detector of one generator.
     *  @param generator 1 or 2.
     *  @param position  False for movement recognition, true for position
     *                  recognition.
     *  @param fourD     True for 4D detection, which drops the Z axis.
     *  @return true if written, false for an invalid generator. */
    bool setOrientation(uint8_t generator = 1, bool position = false, bool fourD = false);

    /** @brief Latch or release the interrupt of one generator.
     *  @param generator 1 or 2.
     *  @param enabled   True to latch.
     *  @return true if written, false for an invalid generator. */
    bool setLatch(uint8_t generator, bool enabled);

    /** @brief Read the interrupt source register of one generator.
     *  Reading the register clears the latched interrupt.
     *  @param generator 1 or 2.
     *  @return The raw INTx_SOURCE_A byte, or 0 for an invalid generator. */
    uint8_t readInterruptSource(uint8_t generator);

    /** @brief Configure the click detector.
     *  @param single       Axis mask of the enabled single-click detectors.
     *  @param doubleClick  Axis mask of the enabled double-click detectors.
     *  @param thresholdG   Click threshold in *g*.
     *  @param timeLimitMs  Maximum time between the two clicks of a double click.
     *  @param latencyMs    Minimum time between two consecutive clicks.
     *  @param windowMs     Time the click detector observes.
     *  @return true if written, false for an out-of-range axis mask. */
    bool setClick(uint8_t single, uint8_t doubleClick, float thresholdG = 0.5f,
                  float timeLimitMs = 10.0f, float latencyMs = 20.0f, float windowMs = 50.0f);

    /** @brief Read the click source register.
     *  Reading the register clears the latched click interrupt.
     *  @return The raw CLICK_SRC_A byte. */
    uint8_t readClickSource();

    /** @brief Set the polarity of the INT1 / INT2 outputs.
     *  @param activeLow True for active-low, false for active-high. */
    void setInterruptPolarity(bool activeLow);

    /** @brief Set the magnetometer full-scale range.
     *  @param rangeUt 130, 190, 250, 400, 470, 560 or 810 (µT).
     *  @return true if written, false for an unsupported range. */
    bool setMagRange(uint16_t rangeUt);

    /** @brief Set the magnetometer output data rate.
     *  @param rateHz 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220.
     *  @return true if written, false for an unsupported rate. */
    bool setMagDataRate(float rateHz);

    /** @brief Set the magnetometer operating mode.
     *  @param mode MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP.
     *  @return true if written, false for an unsupported mode. */
    bool setMagMode(uint8_t mode);

    /** @brief Read the magnetic field as raw counts.
     *  @param[out] x X counts.
     *  @param[out] y Y counts.
     *  @param[out] z Z counts. */
    void readMagneticRaw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Report whether a new magnetometer sample is available.
     *  @return true if SR_REG_M.DRDY is set. */
    bool magDataReady();

    /** @brief Read the decoded magnetometer status register.
     *  @param[out] drdy True if a new sample is available.
     *  @param[out] lock True if the output registers are locked. */
    void readMagStatus(bool& drdy, bool& lock);

    /** @brief Read the magnetometer identity bytes.
     *  @param[out] out Buffer of at least three bytes. */
    void readMagIdentity(uint8_t* out);

    /** @brief Enable or disable the magnetometer temperature sensor. */
    void enableTemperature(bool enabled);

    /** @brief Read the relative temperature of the magnetometer.
     *  The sensor is enabled on first use; the datasheet gives no zero
     *  reference, so the value is a delta and not an absolute °C reading.
     *  @return Temperature difference in °C (8 LSB/°C). */
    float readTemperatureDelta();

    /** @brief Compute the compass heading.
     *  @param tiltCompensate True to compensate for the tilt reported by the
     *                        accelerometer.
     *  @return The heading in degrees, 0-360. */
    float heading(bool tiltCompensate = true);

    /** @brief Route one interrupt source to INT1 or INT2.
     *  Only the routing is configured; the generator or function behind the
     *  source is set up with the set*() methods.
     *  @param source One of the SOURCE_* constants.
     *  @param pin    1 for INT1, 2 for INT2.
     *  @return true if written, false for an unroutable source or pin. */
    bool enableInterrupt(uint8_t source, uint8_t pin = 1);

    /** @brief Remove one interrupt source from INT1 or INT2.
     *  @param source One of the SOURCE_* constants.
     *  @param pin    1 for INT1, 2 for INT2.
     *  @return true if written, false for an unroutable source or pin. */
    bool disableInterrupt(uint8_t source, uint8_t pin = 1);

    /** @brief Subscribe to the INT1 or INT2 line.
     *
     *  The edge direction follows the polarity set with
     *  setInterruptPolarity(). Requires an InputPin — either the one on the
     *  accelerometer connection or an explicit override.
     *
     *  @param callback Called with the pollInterrupt() mask on every edge.
     *  @param pin      1 for INT1, 2 for INT2.
     *  @param intPin   Optional InputPin for this call, overriding the
     *                  connection's own INT pin. */
    void onInterrupt(void (*callback)(uint8_t status), uint8_t pin = 1,
                     InputPin* intPin = nullptr);

    /** @brief Unsubscribe from the INT1 or INT2 line.
     *  @param pin 1 for INT1, 2 for INT2. */
    void offInterrupt(uint8_t pin = 1);

    /** @brief Read the interrupt status of every enabled source.
     *  Reading INTx_SOURCE_A and CLICK_SRC_A clears their latched flags.
     *  @return A mask of the SOURCE_* constants. */
    uint8_t pollInterrupt();

    /** @brief Reboot the accelerometer's memory content.
     *  The BOOT bit reloads the factory trim values; the initialization
     *  sequence is run again afterwards, so any configuration applied after
     *  init() has to be repeated. */
    void reboot();

protected:
    // CTRL_REG1_A bits.
    static constexpr uint8_t CTRL1_ODR_MASK = 0xF0;
    static constexpr uint8_t CTRL1_LPEN = 0x08;
    static constexpr uint8_t CTRL1_AXES_MASK = 0x07;

    // CTRL_REG2_A bits.
    static constexpr uint8_t CTRL2_FDS = 0x08;
    static constexpr uint8_t CTRL2_HPCLICK = 0x04;
    static constexpr uint8_t CTRL2_HPIS2 = 0x02;
    static constexpr uint8_t CTRL2_HPIS1 = 0x01;

    // CTRL_REG3_A bits (routing to INT1).
    static constexpr uint8_t CTRL3_I1_CLICK = 0x80;
    static constexpr uint8_t CTRL3_I1_AOI1 = 0x40;
    static constexpr uint8_t CTRL3_I1_AOI2 = 0x20;
    static constexpr uint8_t CTRL3_I1_DRDY1 = 0x10;
    static constexpr uint8_t CTRL3_I1_WTM = 0x04;
    static constexpr uint8_t CTRL3_I1_OVERRUN = 0x02;

    // CTRL_REG4_A bits.
    static constexpr uint8_t CTRL4_FS_MASK = 0x30;
    static constexpr uint8_t CTRL4_HR = 0x08;

    // CTRL_REG5_A bits.
    static constexpr uint8_t CTRL5_BOOT = 0x80;
    static constexpr uint8_t CTRL5_FIFO_EN = 0x40;
    static constexpr uint8_t CTRL5_LIR_INT1 = 0x08;
    static constexpr uint8_t CTRL5_D4D_INT1 = 0x04;
    static constexpr uint8_t CTRL5_LIR_INT2 = 0x02;
    static constexpr uint8_t CTRL5_D4D_INT2 = 0x01;

    // CTRL_REG6_A bits (routing to INT2).
    static constexpr uint8_t CTRL6_I2_CLICK = 0x80;
    static constexpr uint8_t CTRL6_I2_INT1 = 0x40;
    static constexpr uint8_t CTRL6_I2_INT2 = 0x20;
    static constexpr uint8_t CTRL6_BOOT_I1 = 0x10;
    static constexpr uint8_t CTRL6_H_LACTIVE = 0x02;

    // STATUS_REG_A bits.
    static constexpr uint8_t STATUS_ZYXDA = 0x08;

    // FIFO_SRC_REG_A bits.
    static constexpr uint8_t FIFO_SRC_WTM = 0x80;
    static constexpr uint8_t FIFO_SRC_OVERRUN = 0x40;
    static constexpr uint8_t FIFO_SRC_EMPTY = 0x20;
    static constexpr uint8_t FIFO_SRC_LEVEL_MASK = 0x1F;

    // INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
    static constexpr uint8_t SOURCE_IA = 0x40;

    // CRA_REG_M bits.
    static constexpr uint8_t CRA_TEMP_EN = 0x80;
    static constexpr uint8_t CRA_DO_MASK = 0x1C;

    // CRB_REG_M bits.
    static constexpr uint8_t CRB_GN_MASK = 0xE0;

    // SR_REG_M bits.
    static constexpr uint8_t SR_LOCK = 0x02;
    static constexpr uint8_t SR_DRDY = 0x01;

    bool _tempEnabled = false;
    uint8_t _enabledSources = 0;

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static LSM303Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();

    bool _intRegs(uint8_t generator, uint8_t& cfg, uint8_t& ths, uint8_t& dur) const;
    uint8_t _encodeThreshold(float thresholdG) const;
    uint8_t _encodeDuration(float durationMs, uint8_t bits) const;
    void _sourceBits(uint8_t source, uint8_t& ctrl3, uint8_t& ctrl6) const;
    float _wrapDegrees(float degrees) const;
};
