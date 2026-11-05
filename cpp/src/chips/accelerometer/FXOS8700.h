#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief FXOS8700CQ 6-axis e-compass (accelerometer + magnetometer) — minimal interface.
 *
 *  The FXOS8700CQ combines a 14-bit three-axis MEMS accelerometer (±2 / ±4 /
 *  ±8 g) and a 16-bit three-axis magnetometer (fixed ±1200 µT range,
 *  0.1 µT/LSB) behind **one I²C address** (0x1C-0x1F, selected by SA1 / SA0)
 *  and one shared ADC. In hybrid mode both sensors run at half the
 *  programmed output data rate and all twelve data bytes can be read in one
 *  burst. Do not leave SA0 floating: a floating SA0 at power-up selects SPI.
 *
 *  Default configuration (written by init()):
 *  - Hybrid mode, system ODR 200 Hz (100 Hz per sensor), ±2 g (4096
 *    counts/g), 14-bit output, normal accelerometer oversampling,
 *    magnetometer OSR 7, low-noise off, hyb_autoinc_mode on, no FIFO, no
 *    detectors, no interrupt routing, interrupts active-low push-pull
 *
 *  @param connection RegisterConnection (I²C) bound to 0x1C-0x1F.
 */
class FXOS8700Minimal {
public:
    /** @brief I²C address for SA1 = 0, SA0 = 0 (default). */
    static constexpr uint8_t I2C_ADDRESS = 0x1E;

    explicit FXOS8700Minimal(RegisterConnection& connection);

    /** @brief Run the datasheet initialization sequence.
     *
     *  Verifies WHO_AM_I (0xC7, or 0xC4 on pre-production parts), performs a
     *  software reset (the rst write is not acknowledged; the NACK is
     *  ignored) and starts hybrid mode.
     *
     *  @return true if the chip was found, false for a wrong WHO_AM_I (wrong
     *          chip, wrong address or wiring problem); nothing is configured then.
     */
    bool init();

    /** @brief True if the last init() found an FXOS8700. */
    bool found() const { return _found; }

    /** @brief Read the acceleration (one burst read of 0x01-0x06).
     *  @param[out] x X acceleration in *g*.
     *  @param[out] y Y acceleration in *g*.
     *  @param[out] z Z acceleration in *g*.
     */
    void readAccel(float& x, float& y, float& z);

    /** @brief Read the magnetic flux density (one burst read of 0x33-0x38).
     *  @param[out] x X magnetic flux density in µT.
     *  @param[out] y Y magnetic flux density in µT.
     *  @param[out] z Z magnetic flux density in µT.
     */
    void readMag(float& x, float& y, float& z);

protected:
    static constexpr uint8_t REG_STATUS          = 0x00;
    static constexpr uint8_t REG_OUT_X_MSB       = 0x01;
    static constexpr uint8_t REG_F_SETUP         = 0x09;
    static constexpr uint8_t REG_TRIG_CFG        = 0x0A;
    static constexpr uint8_t REG_SYSMOD          = 0x0B;
    static constexpr uint8_t REG_INT_SOURCE      = 0x0C;
    static constexpr uint8_t REG_WHO_AM_I        = 0x0D;
    static constexpr uint8_t REG_XYZ_DATA_CFG    = 0x0E;
    static constexpr uint8_t REG_HP_FILTER_CUTOFF = 0x0F;
    static constexpr uint8_t REG_PL_STATUS       = 0x10;
    static constexpr uint8_t REG_PL_CFG          = 0x11;
    static constexpr uint8_t REG_PL_COUNT        = 0x12;
    static constexpr uint8_t REG_PL_BF_ZCOMP     = 0x13;
    static constexpr uint8_t REG_PL_THS          = 0x14;
    static constexpr uint8_t REG_A_FFMT_CFG      = 0x15;
    static constexpr uint8_t REG_A_FFMT_SRC      = 0x16;
    static constexpr uint8_t REG_A_FFMT_THS      = 0x17;
    static constexpr uint8_t REG_A_FFMT_COUNT    = 0x18;
    static constexpr uint8_t REG_TRANSIENT_CFG   = 0x1D;
    static constexpr uint8_t REG_TRANSIENT_SRC   = 0x1E;
    static constexpr uint8_t REG_TRANSIENT_THS   = 0x1F;
    static constexpr uint8_t REG_TRANSIENT_COUNT = 0x20;
    static constexpr uint8_t REG_PULSE_CFG       = 0x21;
    static constexpr uint8_t REG_PULSE_SRC       = 0x22;
    static constexpr uint8_t REG_PULSE_THSX      = 0x23;
    static constexpr uint8_t REG_PULSE_TMLT      = 0x26;
    static constexpr uint8_t REG_PULSE_LTCY      = 0x27;
    static constexpr uint8_t REG_PULSE_WIND      = 0x28;
    static constexpr uint8_t REG_ASLP_COUNT      = 0x29;
    static constexpr uint8_t REG_CTRL1           = 0x2A;
    static constexpr uint8_t REG_CTRL2           = 0x2B;
    static constexpr uint8_t REG_CTRL3           = 0x2C;
    static constexpr uint8_t REG_CTRL4           = 0x2D;
    static constexpr uint8_t REG_CTRL5           = 0x2E;
    static constexpr uint8_t REG_OFF_X           = 0x2F;
    static constexpr uint8_t REG_M_DR_STATUS     = 0x32;
    static constexpr uint8_t REG_M_OUT_X_MSB     = 0x33;
    static constexpr uint8_t REG_M_OFF_X_MSB     = 0x3F;
    static constexpr uint8_t REG_MAX_X_MSB       = 0x45;
    static constexpr uint8_t REG_MIN_X_MSB       = 0x4B;
    static constexpr uint8_t REG_TEMP            = 0x51;
    static constexpr uint8_t REG_M_CTRL1         = 0x5B;
    static constexpr uint8_t REG_M_CTRL2         = 0x5C;
    static constexpr uint8_t REG_M_CTRL3         = 0x5D;

    static constexpr uint8_t CTRL1_ACTIVE        = 0x01;
    static constexpr uint8_t CTRL1_F_READ        = 0x02;
    static constexpr uint8_t CTRL1_LNOISE        = 0x04;
    static constexpr uint8_t CTRL1_DR_MASK       = 0x38;
    static constexpr uint8_t CTRL1_ASLP_MASK     = 0xC0;
    static constexpr uint8_t CTRL2_ST            = 0x80;
    static constexpr uint8_t CTRL2_RST           = 0x40;
    static constexpr uint8_t CTRL2_SMODS_MASK    = 0x18;
    static constexpr uint8_t CTRL2_SLPE          = 0x04;
    static constexpr uint8_t CTRL2_MODS_MASK     = 0x03;
    static constexpr uint8_t STATUS_ZYXDR        = 0x08;
    static constexpr uint8_t M_CTRL1_ACAL        = 0x80;
    static constexpr uint8_t M_CTRL1_RST         = 0x40;
    static constexpr uint8_t M_CTRL1_OST         = 0x20;
    static constexpr uint8_t M_CTRL1_OS_MASK     = 0x1C;
    static constexpr uint8_t M_CTRL1_HMS_MASK    = 0x03;
    static constexpr uint8_t M_CTRL2_HYB_AUTOINC = 0x20;

    RegisterConnection& _conn;

    // Cached configuration, kept in sync with the registers.
    uint8_t _mode = 2;       // 0 accel only, 1 mag only, 2 hybrid
    uint8_t _dr = 2;         // CTRL_REG1.dr code
    uint8_t _fsG = 2;
    uint8_t _mods = 0;       // accelerometer oversampling (wake)
    bool    _lnoise = false;
    bool    _fRead = false;  // true = 8-bit fast read
    uint8_t _fifoMode = 0;
    bool    _hyb = true;     // hyb_autoinc_mode
    bool    _found = false;

    uint8_t _readReg(uint8_t reg);
    void    _writeReg(uint8_t reg, uint8_t value);
    void    _modify(uint8_t reg, uint8_t mask, uint8_t value);
    uint8_t _whoAmI();
    void    _softReset();
    float   _accelScale() const;
    float   _odrHz() const;
    uint32_t _turnOnMs() const;
    void    _decodeAccel(const uint8_t* data, int16_t& x, int16_t& y, int16_t& z) const;
    static void _decodeMag(const uint8_t* data, int16_t& x, int16_t& y, int16_t& z);
    void    _readAccelCounts(int16_t& x, int16_t& y, int16_t& z);
};

/** @brief FXOS8700 full interface — the complete chip functionality.
 *
 *  Methods that change a STANDBY-only register put the chip into STANDBY,
 *  write, and restore ACTIVE internally, so the caller never manages the
 *  mode. Every such change flushes the FIFO and resets the detectors'
 *  debounce counters.
 *
 *  The vector-magnitude detectors (0x5F-0x72), the magnetic threshold
 *  detector (0x52-0x5A) and the per-axis freefall / motion thresholds
 *  (0x73-0x78) are not wrapped: use readRegister() / writeRegister().
 *
 *  @param connection RegisterConnection (I²C) bound to 0x1C-0x1F.
 */
class FXOS8700Full : public FXOS8700Minimal {
public:
    /** @brief New sample (accelerometer in accel-only mode, magnetometer otherwise). */
    static constexpr uint8_t SOURCE_DATA_READY = 0x01;
    /** @brief Accelerometer vector-magnitude change (configured via raw registers). */
    static constexpr uint8_t SOURCE_ACCEL_VECM = 0x02;
    /** @brief Freefall / motion (A_FFMT_SRC.ea). */
    static constexpr uint8_t SOURCE_FF_MT = 0x04;
    /** @brief Single / double tap (PULSE_SRC.ea). */
    static constexpr uint8_t SOURCE_PULSE = 0x08;
    /** @brief Orientation change (PL_STATUS.newlp). */
    static constexpr uint8_t SOURCE_ORIENTATION = 0x10;
    /** @brief Transient (TRANSIENT_SRC.ea). */
    static constexpr uint8_t SOURCE_TRANSIENT = 0x20;
    /** @brief FIFO overflow or watermark. */
    static constexpr uint8_t SOURCE_FIFO = 0x40;
    /** @brief WAKE <-> SLEEP transition. */
    static constexpr uint8_t SOURCE_AUTO_SLEEP = 0x80;

    /** @brief Operating mode: accelerometer only. */
    static constexpr uint8_t MODE_ACCEL_ONLY = 0;
    /** @brief Operating mode: magnetometer only. */
    static constexpr uint8_t MODE_MAG_ONLY = 1;
    /** @brief Operating mode: hybrid (both sensors, half the ODR each). */
    static constexpr uint8_t MODE_HYBRID = 2;

    /** @brief FIFO disabled. */
    static constexpr uint8_t FIFO_DISABLED = 0;
    /** @brief FIFO circular mode (oldest sample discarded). */
    static constexpr uint8_t FIFO_CIRCULAR = 1;
    /** @brief FIFO fill mode (stops accepting when full). */
    static constexpr uint8_t FIFO_FILL = 2;
    /** @brief FIFO trigger mode. */
    static constexpr uint8_t FIFO_TRIGGER = 3;

    explicit FXOS8700Full(RegisterConnection& connection);

    // --- Mode / power ---

    /** @brief Enter STANDBY (active = 0) and wait until SYSMOD reads 0 (up to 350 ms).
     *  @return true on success, false on timeout. */
    bool standby();

    /** @brief Enter ACTIVE (active = 1) and wait the turn-on time. */
    void activate();

    /** @brief Software reset (CTRL_REG2.rst). The NACK is tolerated; every
     *  register returns to its reset value. Call init() afterwards. */
    void reset();

    /** @brief Select accelerometer-only, magnetometer-only or hybrid operation.
     *  @param mode 0 accelerometer only, 1 magnetometer only, 2 hybrid (also enables hyb_autoinc_mode).
     *  @return true if written, false for an invalid mode. */
    bool setMode(uint8_t mode);

    /** @brief Select the accelerometer full-scale range.
     *  @param rangeG 2, 4 or 8 (g).
     *  @return true if written, false for an invalid range or 8 g with low-noise on. */
    bool setRange(uint8_t rangeG);

    /** @brief Select the system output data rate (each sensor runs at half in hybrid mode).
     *  @param rateHz 800, 400, 200, 100, 50, 12.5, 6.25 or 1.5625 (1.56 accepted).
     *  @return true if written, false for an unsupported rate. */
    bool setDataRate(float rateHz);

    /** @brief Select the accelerometer oversampling mode.
     *  @param mode      0 normal, 1 low-noise low-power, 2 high resolution, 3 low power.
     *  @param sleepMode Same encoding for SLEEP mode (smods), or -1 to leave it unchanged.
     *  @return true if written, false for a value outside 0-3. */
    bool setAccelOversampling(uint8_t mode, int8_t sleepMode = -1);

    /** @brief Select the magnetometer oversampling ratio code (m_os, datasheet Table 203).
     *  @param ratio      0-7 for active mode.
     *  @param sleepRatio 0-7 for Auto-SLEEP (m_aslp_os), or -1 to leave it unchanged.
     *  @return true if written, false for a value outside 0-7. */
    bool setMagOversampling(uint8_t ratio, int8_t sleepRatio = -1);

    /** @brief Enable or disable the reduced-noise mode (limits the range to ±4 g).
     *  @return true if written, false when enabling at ±8 g. */
    bool setLowNoise(bool enabled);

    /** @brief Select 14-bit or 8-bit (fast read) accelerometer output.
     *  @param bits 14 or 8.
     *  @return true if written, false for another value or while the FIFO is on. */
    bool setResolution(uint8_t bits);

    // --- Data ---

    /** @brief Read the raw accelerometer counts (14-bit right-aligned, or 8-bit). */
    void readRawAccel(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read the raw magnetometer counts (16-bit, 0.1 µT/LSB). */
    void readRawMag(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read accelerometer and magnetometer with one burst from 0x01.
     *  @param[out] ax,ay,az Acceleration in *g*.
     *  @param[out] mx,my,mz Magnetic flux density in µT.
     *  @return true on success, false when the chip is not in hybrid mode. */
    bool readHybrid(float& ax, float& ay, float& az, float& mx, float& my, float& mz);

    /** @brief Read the die temperature (int8(TEMP) × 0.96, relative, uncalibrated).
     *  @param[out] celsius Temperature in °C.
     *  @return true on success, false in accelerometer-only mode. */
    bool readTemperature(float& celsius);

    /** @brief True if a new accelerometer sample is ready (STATUS.zyxdr; invalid while the FIFO is on). */
    bool newAccelData();
    /** @brief True if a new magnetometer sample is ready (M_DR_STATUS.zyxdr). */
    bool newMagData();
    /** @brief Read the STATUS byte. */
    uint8_t readStatus();
    /** @brief Read the M_DR_STATUS byte. */
    uint8_t readMagStatus();
    /** @brief Read SYSMOD bits 1:0 (0 standby, 1 wake, 2 sleep). */
    uint8_t readSystemMode();

    /** @brief Select high-pass-filtered output data (cutoff table: datasheet Table 64).
     *  @param enabled True to output high-pass-filtered data (hpf_out).
     *  @param cutoff  sel code 0-3.
     *  @return true if written, false for a cutoff outside 0-3. */
    bool setHighPassOutput(bool enabled, uint8_t cutoff = 0);

    /** @brief Write the per-axis accelerometer offsets (added to the output, 2 mg/LSB, ±0.256 g). */
    void setAccelOffset(float xG, float yG, float zG);
    /** @brief Read the accelerometer offsets in *g*. */
    void getAccelOffset(float& xG, float& yG, float& zG);
    /** @brief Write the hard-iron offsets (15-bit value in bits 15:1, subtracted from the output, 0.1 µT/LSB). */
    void setMagOffset(float xUt, float yUt, float zUt);
    /** @brief Read the hard-iron offsets in µT. */
    void getMagOffset(float& xUt, float& yUt, float& zUt);
    /** @brief Select raw magnetometer output (m_raw); true = offsets are not applied. */
    void setMagRaw(bool raw);

    /** @brief Enable or disable hardware hard-iron auto-calibration (m_acal).
     *  Enabling also clears m_raw and enables min/max tracking. Rotate the
     *  board through all orientations while on (NXP AN4399). */
    void setMagAutoCalibration(bool enabled);

    /** @brief Read the tracked magnetic minimum and maximum.
     *  @param[out] minUt Three values (x, y, z) in µT.
     *  @param[out] maxUt Three values (x, y, z) in µT. */
    void readMagMinMax(float* minUt, float* maxUt);

    /** @brief Reset the min/max registers to 0x7FFF / 0x8000 (m_maxmin_rst). */
    void resetMagMinMax();

    /** @brief Select the automatic magnetic reset period.
     *  @param mode 0 every ODR cycle, 1 every 16, 2 every 512, 3 disabled.
     *  @return true if written, false for a mode outside 0-3. */
    bool setMagReset(uint8_t mode);

    /** @brief Trigger a one-shot magnetic reset (m_rst). */
    void triggerMagReset();

    /** @brief Take one magnetic measurement in STANDBY (m_ost); ACTIVE is restored.
     *  @param[out] x,y,z Magnetic flux density in µT.
     *  @return true on success, false in accelerometer-only mode or on a 1 s timeout. */
    bool measureMagOnce(float& x, float& y, float& z);

    /** @brief Compute the tilt-compensated compass heading (chip frame X forward, Y right, Z down).
     *  @param declinationDeg Magnetic declination added to the result.
     *  @return Heading in degrees, [0, 360). */
    float heading(float declinationDeg = 0.0f);

    // --- FIFO ---

    /** @brief Configure the 32-sample accelerometer FIFO.
     *  @param mode      0 disabled, 1 circular, 2 fill, 3 trigger.
     *  @param watermark 0-63 (0 disables the flag).
     *  @return true if written, false for an invalid argument. */
    bool setFifo(uint8_t mode, uint8_t watermark = 0);

    /** @brief Select the FIFO trigger sources (TRIG_CFG). */
    void setFifoTrigger(bool transient = false, bool orientation = false, bool pulse = false, bool motion = false);

    /** @brief Number of samples in the FIFO (0-32). */
    uint8_t fifoLevel();

    /** @brief Decode F_STATUS (clears SOURCE_FIFO). */
    void readFifoStatus(bool& overflow, bool& watermark, uint8_t& count);

    /** @brief Drain accelerometer samples from the FIFO, one transaction per sample.
     *  @param samples    Output buffer for @p maxSamples × 3 floats (x, y, z in *g*).
     *  @param maxSamples Capacity of @p samples in sample sets.
     *  @return Number of samples read. */
    size_t readFifo(float* samples, size_t maxSamples);

    /** @brief Enable or disable the FIFO gate (fifo_gate). */
    void setFifoGate(bool enabled);

    // --- Detectors ---

    /** @brief Configure the freefall / motion detector.
     *  @param motion        True = motion (any axis above), false = freefall (all below).
     *  @param axes          Axis mask X=1, Y=2, Z=4.
     *  @param thresholdG    Threshold in *g* (0.063 g/LSB).
     *  @param debounceMs    Debounce time in ms.
     *  @param latch         Latch until A_FFMT_SRC is read.
     *  @param debounceClear Clear (instead of decrement) the debounce counter when the condition is lost. */
    void setFreefallMotion(bool motion, uint8_t axes = 7, float thresholdG = 0.3f, float debounceMs = 0.0f,
                           bool latch = false, bool debounceClear = false);
    /** @brief Read A_FFMT_SRC (clears the flags when latched). */
    uint8_t readMotionSource();

    /** @brief Configure the transient detector.
     *  @param axes       Axis mask X=1, Y=2, Z=4.
     *  @param thresholdG Threshold in *g*.
     *  @param debounceMs Debounce time in ms.
     *  @param bypassHpf  Bypass the high-pass filter (motion-like behaviour).
     *  @param latch      Latch until TRANSIENT_SRC is read. */
    void setTransient(uint8_t axes = 7, float thresholdG = 0.3f, float debounceMs = 0.0f,
                      bool bypassHpf = false, bool latch = false);
    /** @brief Read TRANSIENT_SRC — clears the interrupt. */
    uint8_t readTransientSource();

    /** @brief Configure tap (pulse) detection.
     *  @param single       Axis mask (X=1, Y=2, Z=4) for single taps.
     *  @param doubleTap    Axis mask for double taps.
     *  @param thresholdG   Three thresholds (x, y, z) in *g*, or nullptr for 0.5 g on every axis.
     *  @param timeLimitMs  Pulse time limit in ms.
     *  @param latencyMs    Latency between the taps in ms.
     *  @param windowMs     Window for the second tap in ms.
     *  @param latch        Latch until PULSE_SRC is read.
     *  @param doubleAbort  Abort double-tap detection on a pulse during the latency window.
     *  @param lpf          Enable the pulse low-pass filter.
     *  @param hpf          Enable the pulse high-pass filter. */
    void setPulse(uint8_t single = 0, uint8_t doubleTap = 0, const float* thresholdG = nullptr,
                  float timeLimitMs = 30.0f, float latencyMs = 100.0f, float windowMs = 300.0f,
                  bool latch = false, bool doubleAbort = false, bool lpf = true, bool hpf = true);
    /** @brief Read PULSE_SRC — clears the interrupt. */
    uint8_t readPulseSource();

    /** @brief Configure orientation (portrait / landscape) detection.
     *  @param enabled      Enable the detector (pl_en).
     *  @param debounceMs   Debounce time in ms.
     *  @param thresholdDeg Trip angle: 15, 20, 30, 35, 40, 45, 55, 60, 70 or 75.
     *  @param hysteresis   hys code 0-7.
     *  @param zLock        Z-lock angle code 0-7.
     *  @param backFront    Back/front trip code 0-3.
     *  @return true if written, false for an invalid argument or pl_ths + hys outside 1-31. */
    bool setOrientation(bool enabled = true, float debounceMs = 0.0f, uint8_t thresholdDeg = 45,
                        uint8_t hysteresis = 4, uint8_t zLock = 4, uint8_t backFront = 2);

    /** @brief Decode PL_STATUS — clears newlp.
     *  @param[out] changed           New orientation detected.
     *  @param[out] lockout           Z-tilt lockout active.
     *  @param[out] portraitLandscape 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left.
     *  @param[out] back              True when the back is facing up. */
    void readOrientation(bool& changed, bool& lockout, uint8_t& portraitLandscape, bool& back);

    /** @brief Configure Auto-SLEEP / WAKE.
     *  @param enabled      Enable Auto-SLEEP (slpe).
     *  @param sleepRateHz  50, 12.5, 6.25 or 1.56 Hz.
     *  @param timeoutS     Inactivity time before SLEEP in seconds.
     *  @param wakeSources  Mask of SOURCE_TRANSIENT, SOURCE_ORIENTATION, SOURCE_PULSE, SOURCE_FF_MT, SOURCE_ACCEL_VECM.
     *  @return true if written, false for an unsupported rate or source. */
    bool setAutoSleep(bool enabled, float sleepRateHz = 50.0f, float timeoutS = 10.0f, uint8_t wakeSources = 0);

    // --- Interrupts ---

    /** @brief Configure INT1 / INT2 polarity and drive (ipol, pp_od). */
    void setInterruptPins(bool activeHigh = false, bool openDrain = false);

    /** @brief Enable an interrupt source and route it to INT1 or INT2.
     *  @param source A mask of SOURCE_* constants.
     *  @param pin    1 for INT1, 2 for INT2.
     *  @return true if written, false for an invalid pin. */
    bool enableInterrupt(uint8_t source, uint8_t pin = 1);

    /** @brief Disable an interrupt source (clears its int_en bit). */
    void disableInterrupt(uint8_t source);

    /** @brief Read INT_SOURCE and clear the pending sources.
     *
     *  For every set bit except SOURCE_DATA_READY and SOURCE_ACCEL_VECM the
     *  matching source register is read so the flag (and the pin) clears.
     *  @return A mask of the SOURCE_* constants. */
    uint8_t pollInterrupt();

    /** @brief Subscribe to the INT1 or INT2 line.
     *
     *  The edge direction follows the configured polarity. Requires an
     *  InputPin — either the one on the connection or an explicit override.
     *
     *  @param callback Called with the pollInterrupt() mask on every edge.
     *  @param pin      1 for INT1, 2 for INT2.
     *  @param intPin   Optional InputPin overriding the connection's own INT pin. */
    void onInterrupt(void (*callback)(uint8_t status), uint8_t pin = 1, InputPin* intPin = nullptr);

    /** @brief Unsubscribe from the INT1 or INT2 line. */
    void offInterrupt(uint8_t pin = 1);

    // --- Escape hatch / self-test ---

    /** @brief Read raw register bytes (vector-magnitude, magnetic-threshold and
     *  per-axis FFMT registers). No STANDBY handling. */
    void readRegister(uint8_t reg, uint8_t* buf, size_t len);
    /** @brief Write raw register bytes (at most 16). No STANDBY handling. */
    void writeRegister(uint8_t reg, const uint8_t* data, size_t len);

    /** @brief Run the accelerometer self-test and restore the configuration.
     *
     *  STANDBY, ±2 g, 100 Hz accelerometer-only; the difference of two
     *  8-sample averages (before / after st) is checked against the datasheet
     *  minimum X >= +192, Y >= +270, Z >= +1275 counts.
     *
     *  @param[out] dx,dy,dz Differences in 14-bit counts.
     *  @return true if every axis meets its minimum. */
    bool selfTest(int16_t& dx, int16_t& dy, int16_t& dz);

private:
    bool    _begin();
    void    _end(bool wasActive);
    bool    _waitStandby();
    float   _stepMs() const;
    float   _pulseStepMs(bool lpf) const;
    static uint8_t _threshold(float thresholdG);
    void    _averageCounts(int32_t& x, int32_t& y, int32_t& z);
    void    _handleEdge();
    static void _edgeTrampoline();

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static FXOS8700Full* _activeInstance;
};
