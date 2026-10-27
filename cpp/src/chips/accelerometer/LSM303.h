#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief Interrupt source identifiers for LSM303Full::enableInterrupt(),
 *  disableInterrupt() and the bits returned by LSM303Full::pollInterrupt().
 *
 *  Each value is also the bit position of that source in the poll status byte.
 *  DATA_READY, WATERMARK and OVERRUN are routed to INT1 only; BOOT is routed to
 *  INT2 only; IA1, IA2 and CLICK may be routed to either INT line; MAG_DATA_READY
 *  is always on the magnetometer DRDY pin and cannot be routed.
 */
class LSM303Source {
public:
    static constexpr uint16_t DATA_READY     = 0x0001;  ///< New accelerometer XYZ sample (ZYXDA), INT1
    static constexpr uint16_t WATERMARK      = 0x0002;  ///< FIFO content exceeds watermark (WTM), INT1
    static constexpr uint16_t OVERRUN        = 0x0004;  ///< FIFO overrun (OVRN_FIFO), INT1
    static constexpr uint16_t IA1            = 0x0008;  ///< Inertial generator 1 event, INT1 or INT2
    static constexpr uint16_t IA2            = 0x0010;  ///< Inertial generator 2 event, INT1 or INT2
    static constexpr uint16_t CLICK          = 0x0020;  ///< Single / double click, INT1 or INT2
    static constexpr uint16_t BOOT           = 0x0040;  ///< Boot procedure running, INT2 only (never polled)
    static constexpr uint16_t MAG_DATA_READY = 0x0080;  ///< New magnetometer sample (DRDY pin, not routable)
};

/** @brief LSM303DLHC e-compass (accelerometer + magnetometer) — minimal interface.
 *
 *  The module is two independent I²C slaves on one bus: the accelerometer at
 *  0x19 and the magnetometer at 0x1E. The driver takes one RegisterConnection
 *  for each, both on the same bus, and runs the initialization sequence at
 *  construction. Both connections use 1-byte register addresses.
 *
 *  The magnetometer identity (IRA/IRB/IRC = 0x48 0x34 0x33) is checked first.
 *  On mismatch the constructor writes nothing and valid() returns false.
 *
 *  Default configuration (written at construction):
 *  - Accelerometer: ±2 g (1 mg/LSB), 100 Hz, high-resolution mode, all axes
 *    enabled, BDU=1, high-pass filter bypassed, FIFO bypassed, interrupts
 *    unrouted
 *  - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
 *    temperature sensor off
 *
 *  The accelerometer driver sets bit 7 of the sub-address itself for multi-byte
 *  transfers; the magnetometer needs no such bit.
 *
 *  @param accelConnection RegisterConnection bound to the accelerometer at 0x19.
 *  @param magConnection   RegisterConnection bound to the magnetometer at 0x1E,
 *                         on the same bus as accelConnection.
 */
class LSM303Minimal {
public:
    /** @brief Construct the driver and run the initialization sequence (identity check, then configuration).
     *  @param accelConnection RegisterConnection bound to the accelerometer at 0x19.
     *  @param magConnection   RegisterConnection bound to the magnetometer at 0x1E.
     */
    LSM303Minimal(RegisterConnection& accelConnection, RegisterConnection& magConnection);

    /** @brief True if the magnetometer identity matched during construction.
     *  @return false when IRA/IRB/IRC did not read 0x48 0x34 0x33; the driver
     *          then configured nothing.
     */
    bool valid() const { return _valid; }

    /** @brief Read 3-axis acceleration (one 6-byte burst from OUT_X_L_A).
     *  @param[out] x  X acceleration in g.
     *  @param[out] y  Y acceleration in g.
     *  @param[out] z  Z acceleration in g.
     */
    void readAcceleration(float& x, float& y, float& z);

    /** @brief Read 3-axis magnetic field (one 6-byte burst from OUT_X_H_M, reordered X, Z, Y on the wire).
     *  @param[out] x  X magnetic flux density in µT.
     *  @param[out] y  Y magnetic flux density in µT.
     *  @param[out] z  Z magnetic flux density in µT.
     */
    void readMagnetic(float& x, float& y, float& z);

protected:
    static constexpr uint8_t AUTO_INCREMENT = 0x80;  ///< Accelerometer sub-address bit 7: multi-byte access

    static constexpr uint8_t REG_CTRL_REG1_A     = 0x20;
    static constexpr uint8_t REG_CTRL_REG2_A     = 0x21;
    static constexpr uint8_t REG_CTRL_REG3_A     = 0x22;
    static constexpr uint8_t REG_CTRL_REG4_A     = 0x23;
    static constexpr uint8_t REG_CTRL_REG5_A     = 0x24;
    static constexpr uint8_t REG_CTRL_REG6_A     = 0x25;
    static constexpr uint8_t REG_REFERENCE_A     = 0x26;
    static constexpr uint8_t REG_STATUS_REG_A    = 0x27;
    static constexpr uint8_t REG_OUT_X_L_A       = 0x28;
    static constexpr uint8_t REG_FIFO_CTRL_REG_A = 0x2E;
    static constexpr uint8_t REG_FIFO_SRC_REG_A  = 0x2F;
    static constexpr uint8_t REG_INT1_CFG_A      = 0x30;
    static constexpr uint8_t REG_INT1_SOURCE_A   = 0x31;
    static constexpr uint8_t REG_INT1_THS_A      = 0x32;
    static constexpr uint8_t REG_INT1_DURATION_A = 0x33;
    static constexpr uint8_t REG_INT2_CFG_A      = 0x34;
    static constexpr uint8_t REG_INT2_SOURCE_A   = 0x35;
    static constexpr uint8_t REG_INT2_THS_A      = 0x36;
    static constexpr uint8_t REG_INT2_DURATION_A = 0x37;
    static constexpr uint8_t REG_CLICK_CFG_A     = 0x38;
    static constexpr uint8_t REG_CLICK_SRC_A     = 0x39;
    static constexpr uint8_t REG_CLICK_THS_A     = 0x3A;
    static constexpr uint8_t REG_TIME_LIMIT_A    = 0x3B;
    static constexpr uint8_t REG_TIME_LATENCY_A  = 0x3C;
    static constexpr uint8_t REG_TIME_WINDOW_A   = 0x3D;

    static constexpr uint8_t REG_CRA_REG_M       = 0x00;
    static constexpr uint8_t REG_CRB_REG_M       = 0x01;
    static constexpr uint8_t REG_MR_REG_M        = 0x02;
    static constexpr uint8_t REG_OUT_X_H_M       = 0x03;
    static constexpr uint8_t REG_SR_REG_M        = 0x09;
    static constexpr uint8_t REG_IRA_REG_M       = 0x0A;
    static constexpr uint8_t REG_TEMP_OUT_H_M    = 0x31;

    static constexpr uint8_t ID_A = 0x48;   ///< IRA_REG_M expected value ("H")
    static constexpr uint8_t ID_B = 0x34;   ///< IRB_REG_M expected value ("4")
    static constexpr uint8_t ID_C = 0x33;   ///< IRC_REG_M expected value ("3")

    RegisterConnection& _accelConn;
    RegisterConnection& _magConn;
    bool     _valid        = false;
    uint8_t  _mode         = 2;      ///< 0 low-power, 1 normal, 2 high-resolution
    uint8_t  _fsCode       = 0;      ///< 0..3 → ±2, ±4, ±8, ±16 g
    uint8_t  _odrCode      = 5;      ///< CTRL_REG1_A ODR field, 0..9
    uint8_t  _accelShift   = 4;      ///< Right shift applied to the 16-bit sample
    uint8_t  _mgPerLsb     = 1;      ///< Accelerometer sensitivity in mg/LSB for the current mode and range
    uint16_t _magGainXY    = 1100;   ///< X/Y gain in LSB/gauss for the current magnetometer range
    uint16_t _magGainZ     = 980;    ///< Z gain in LSB/gauss for the current magnetometer range
    bool     _tempEnabled  = false;  ///< Magnetometer temperature sensor (TEMP_EN) state, as the driver last wrote it
    bool     _intActiveLow = false;  ///< H_LACTIVE state, as the driver last wrote it

    void _init();
    void _writeAccel(uint8_t reg, uint8_t value);
    uint8_t _readAccel(uint8_t reg);
    void _readAccelBurst(uint8_t reg, uint8_t* buf, size_t len);
    void _updateAccel(uint8_t reg, uint8_t mask, uint8_t value);
    void _writeMag(uint8_t reg, uint8_t value);
    uint8_t _readMag(uint8_t reg);
    void _updateMag(uint8_t reg, uint8_t mask, uint8_t value);
    void _recomputeAccelScale();
};

/** @brief LSM303DLHC full interface — extends LSM303Minimal with configuration,
 *  FIFO, inertial and click interrupt generators, interrupt routing, magnetometer
 *  configuration, temperature, and software compass heading.
 *
 *  Interrupt pins: the accelerometer INT1 and INT2 are wired to the module,
 *  and the DRDY pin is the magnetometer's own. The connection carries one InputPin
 *  (accelConnection.intPin()), used for INT1 by default. For INT2 pass an
 *  InputPin explicitly to onInterrupt(). enableInterrupt() routes a source to a
 *  pin inside the chip and does not need any InputPin.
 *
 *  @param accelConnection RegisterConnection bound to the accelerometer at 0x19.
 *  @param magConnection   RegisterConnection bound to the magnetometer at 0x1E.
 */
class LSM303Full : public LSM303Minimal {
public:
    /** @brief Construct the full driver; same initialization as LSM303Minimal.
     *  @param accelConnection RegisterConnection bound to the accelerometer at 0x19.
     *  @param magConnection   RegisterConnection bound to the magnetometer at 0x1E.
     */
    LSM303Full(RegisterConnection& accelConnection, RegisterConnection& magConnection);

    /** @brief Set accelerometer full-scale range; updates the cached sensitivity.
     *  @param rangeG  2, 4, 8 or 16 (g).
     *  @return false if rangeG is not one of the four values (nothing written).
     */
    bool setRange(int rangeG);

    /** @brief Set accelerometer output data rate (CTRL_REG1_A ODR).
     *  @param rateHz  0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376 Hz.
     *                 1620 Hz requires low-power mode; 1344 Hz requires normal or
     *                 high-resolution mode; 5376 Hz requires low-power mode.
     *  @return false if the rate is not allowed in the current mode (nothing written).
     */
    bool setDataRate(int rateHz);

    /** @brief Set accelerometer operating mode (LPen / HR bits).
     *  @param mode  0 low-power (8-bit), 1 normal (10-bit), 2 high-resolution (12-bit).
     *  @return false if the current output data rate is not allowed in the new mode,
     *          or mode is out of range (nothing written).
     */
    bool setMode(int mode);

    /** @brief Enable or disable each accelerometer axis (CTRL_REG1_A Xen/Yen/Zen).
     *  @param x  Enable X axis.
     *  @param y  Enable Y axis.
     *  @param z  Enable Z axis.
     */
    void setAxes(bool x = true, bool y = true, bool z = true);

    /** @brief Put the accelerometer in power-down (ODR = 0000); other control bits are kept. */
    void powerDown();

    /** @brief Read raw 3-axis acceleration counts after the current resolution shift.
     *  @param[out] x  Signed counts on X (12-, 10- or 8-bit depending on mode).
     *  @param[out] y  Signed counts on Y.
     *  @param[out] z  Signed counts on Z.
     */
    void readAccelerationRaw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Check whether a new accelerometer sample is available.
     *  @return true when STATUS_REG_A.ZYXDA is set.
     */
    bool newDataAvailable();

    /** @brief Read STATUS_REG_A.
     *  @return The raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA).
     */
    uint8_t readStatus();

    /** @brief Configure the accelerometer high-pass filter (CTRL_REG2_A); writes the whole register.
     *  @param mode          HPM<1:0>: 0 normal (reset by REFERENCE_A read), 1 reference filter,
     *                       2 normal, 3 autoreset on interrupt.
     *  @param cutoff        HPCF<2:1> cutoff selection, 0–3 (no Hz values are tabulated).
     *  @param filterOutput  true routes the filtered data to OUT_* and the FIFO (FDS).
     *  @param onIa1         Enable the high-pass filter for interrupt generator 1 (HPIS1).
     *  @param onIa2         Enable the high-pass filter for interrupt generator 2 (HPIS2).
     *  @param onClick       Enable the high-pass filter for click detection (HPCLICK).
     */
    void setHighPass(uint8_t mode = 0, uint8_t cutoff = 0, bool filterOutput = false,
                     bool onIa1 = false, bool onIa2 = false, bool onClick = false);

    /** @brief Read REFERENCE_A; in HPM = 00 this resets the high-pass filter.
     *  @return The REFERENCE_A byte.
     */
    uint8_t resetHighPass();

    /** @brief Write the high-pass reference value (used in HPM = 01).
     *  @param value  Reference value, 0–255.
     */
    void setReference(uint8_t value);

    /** @brief Configure the 32-level FIFO (FIFO_CTRL_REG_A) and CTRL_REG5_A FIFO_EN.
     *  @param mode          0 bypass, 1 FIFO, 2 stream, 3 trigger. Returning to bypass clears the buffer.
     *  @param watermark     FTH<4:0> watermark level, 0–31.
     *  @param triggerInt2   Trigger source (TR): false = INT1, true = INT2.
     *  @return false if mode or watermark is out of range (nothing written).
     */
    bool setFifo(uint8_t mode, uint8_t watermark = 0, bool triggerInt2 = false);

    /** @brief Number of unread FIFO sample sets (FIFO_SRC_REG_A FSS).
     *  @return 0–32.
     */
    uint8_t fifoLevel();

    /** @brief Drain up to @p count FIFO sample sets, one 6-byte burst per sample, in g.
     *  @param[out] x      Output buffer for X values; at least @p count entries.
     *  @param[out] y      Output buffer for Y values; at least @p count entries.
     *  @param[out] z      Output buffer for Z values; at least @p count entries.
     *  @param      count  Maximum sample sets to read (pass 32 to drain a full FIFO).
     *  @return Number of sample sets actually read, min(count, fifoLevel()).
     */
    uint8_t readFifo(float* x, float* y, float* z, uint8_t count);

    /** @brief Decode FIFO_SRC_REG_A.
     *  @param[out] watermark  true when the FIFO content exceeds the watermark (WTM).
     *  @param[out] overrun    true when the FIFO was full and the next sample overwrote the oldest (OVRN_FIFO).
     *  @param[out] empty      true when the FIFO is empty.
     *  @param[out] level      Number of unread sample sets (FSS), 0–32.
     */
    void readFifoStatus(bool& watermark, bool& overrun, bool& empty, uint8_t& level);

    /** @brief Configure inertial interrupt generator 1 or 2 (INTx_CFG_A, INTx_THS_A, INTx_DURATION_A).
     *  @param generator    1 or 2.
     *  @param events       6-bit enable mask: bit5 ZHIE, bit4 ZLIE, bit3 YHIE, bit2 YLIE, bit1 XHIE, bit0 XLIE.
     *  @param thresholdG   Threshold in g. Code = round(thresholdG × 128 / FS_g), clamped to 0–127.
     *  @param durationMs   Minimum duration in ms. Code = round(durationMs × ODR / 1000), clamped to 0–127.
     *  @param andMode      true = AND combination of the enabled events (AOI); false = OR.
     *  @return false if generator is not 1 or 2 (nothing written).
     */
    bool setMotionInterrupt(uint8_t generator, uint8_t events, float thresholdG,
                            float durationMs = 0.0f, bool andMode = false);

    /** @brief Free-fall detection on a generator: all three axes low, AND mode (INTx_CFG_A = 0x95).
     *  @param generator    1 or 2.
     *  @param thresholdG   Threshold in g.
     *  @param durationMs   Minimum duration in ms.
     *  @return false if generator is not 1 or 2 (nothing written).
     */
    bool setFreeFall(uint8_t generator = 1, float thresholdG = 0.35f, float durationMs = 30.0f);

    /** @brief Orientation recognition (6D) on a generator, with optional 4D.
     *
     *  Writes INTx_CFG_A with the 6D bit and all six zone enables set. position = false
     *  selects movement recognition (AOI = 0), true selects position recognition (AOI = 1).
     *  The threshold and duration already set by setMotionInterrupt() are kept.
     *
     *  @param generator  1 or 2.
     *  @param position   false = movement recognition, true = position recognition.
     *  @param fourD      true enables 4D (D4D_INTx), which ignores the Z zone.
     *  @return false if generator is not 1 or 2 (nothing written).
     */
    bool setOrientation(uint8_t generator = 1, bool position = false, bool fourD = false);

    /** @brief Latch a generator's interrupt until its INTx_SOURCE_A register is read (LIR_INTx).
     *  @param generator  1 or 2.
     *  @param enabled    true to latch.
     *  @return false if generator is not 1 or 2 (nothing written).
     */
    bool setLatch(uint8_t generator, bool enabled);

    /** @brief Read INTx_SOURCE_A. This clears the IA flag and the INT line (and the latch, if set).
     *  @param generator  1 or 2.
     *  @return The INTx_SOURCE_A byte, or 0 if generator is not 1 or 2.
     */
    uint8_t readInterruptSource(uint8_t generator);

    /** @brief Configure single and double click detection (CLICK_CFG_A, CLICK_THS_A, TIME_*_A).
     *  @param singleAxes  Single-click axis mask: X=1, Y=2, Z=4.
     *  @param doubleAxes  Double-click axis mask: X=1, Y=2, Z=4.
     *  @param thresholdG  Click threshold in g. Code = round(thresholdG × 128 / FS_g), clamped to 0–127.
     *  @param timeLimitMs Click time limit in ms. Code = round(ms × ODR / 1000), clamped to 0–127.
     *  @param latencyMs   Latency in ms (double-click dead time). Code clamped to 0–255.
     *  @param windowMs    Window in ms (double-click window). Code clamped to 0–255.
     */
    void setClick(uint8_t singleAxes = 0, uint8_t doubleAxes = 0, float thresholdG = 0.5f,
                  float timeLimitMs = 10.0f, float latencyMs = 20.0f, float windowMs = 50.0f);

    /** @brief Read CLICK_SRC_A. This clears the click interrupt.
     *  @return The CLICK_SRC_A byte.
     */
    uint8_t readClickSource();

    /** @brief Set the INT1 / INT2 polarity (CTRL_REG6_A H_LACTIVE).
     *  @param activeLow  true = active-low, false = active-high (default).
     */
    void setInterruptPolarity(bool activeLow);

    /** @brief Set magnetometer full-scale range (CRB_REG_M GN); updates the cached gains.
     *  @param rangeUt  130, 190, 250, 400, 470, 560 or 810 µT (±1.3 … ±8.1 gauss).
     *  @return false if rangeUt is not one of the seven values (nothing written).
     */
    bool setMagRange(int rangeUt);

    /** @brief Set magnetometer output data rate (CRA_REG_M DO); TEMP_EN is kept.
     *  @param rateHz  0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz.
     *  @return false if rateHz is not one of the eight values (nothing written).
     */
    bool setMagDataRate(float rateHz);

    /** @brief Set magnetometer operating mode (MR_REG_M MD).
     *  @param mode  0 continuous conversion, 1 single conversion (then sleep), 2 sleep.
     *  @return false if mode is out of range (nothing written).
     */
    bool setMagMode(uint8_t mode);

    /** @brief Read raw magnetometer counts in X, Y, Z order (after reordering from the wire order X, Z, Y).
     *  @param[out] x  Signed X counts.
     *  @param[out] y  Signed Y counts.
     *  @param[out] z  Signed Z counts.
     */
    void readMagneticRaw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Check whether a new magnetometer sample is available.
     *  @return true when SR_REG_M.DRDY is set.
     */
    bool magDataReady();

    /** @brief Decode SR_REG_M.
     *  @param[out] drdy  true when new data is available (DRDY).
     *  @param[out] lock  true when the output registers are locked (LOCK).
     */
    void readMagStatus(bool& drdy, bool& lock);

    /** @brief Read the magnetometer identification registers IRA/IRB/IRC.
     *  @param[out] buf  Buffer of at least 3 bytes; receives 0x48, 0x34, 0x33 on a genuine part.
     */
    void readMagIdentity(uint8_t* buf);

    /** @brief Enable or disable the magnetometer temperature sensor (CRA_REG_M TEMP_EN).
     *  @param enabled  true to enable.
     */
    void enableTemperature(bool enabled);

    /** @brief Read the relative magnetometer temperature. Enables TEMP_EN on first use.
     *  The datasheet gives no reference point, so the value is a delta, not an absolute temperature.
     *  @return Relative temperature delta in °C (8 LSB/°C).
     */
    float readTemperatureDelta();

    /** @brief Compass heading from the magnetometer, optionally tilt-compensated with the accelerometer.
     *
     *  Assumes both sensors report in the same body frame; the datasheet does not state the
     *  axis alignment between the two dies, so verify on hardware before relying on tilt.
     *
     *  @param tiltCompensate  true reads both sensors and compensates for roll and pitch; false is flat.
     *  @return Heading in degrees, 0 to 360, using atan2(y, x).
     */
    float heading(bool tiltCompensate = true);

    /** @brief Route an interrupt source to INT1 or INT2 inside the chip.
     *
     *  INT1-only sources (DATA_READY, WATERMARK, OVERRUN) ignore @p pin and go to INT1.
     *  BOOT ignores @p pin and goes to INT2. MAG_DATA_READY cannot be routed.
     *  enableInterrupt does not configure the generator or function behind the source;
     *  use setMotionInterrupt / setClick / setFifo for that.
     *
     *  @param source  One LSM303Source constant.
     *  @param pin     1 or 2.
     *  @return false if the source cannot be routed or pin is not 1 or 2 (nothing written).
     */
    bool enableInterrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Remove an interrupt source from INT1 or INT2 (same routing rules as enableInterrupt).
     *  @param source  One LSM303Source constant.
     *  @param pin     1 or 2.
     *  @return false if the source cannot be routed or pin is not 1 or 2 (nothing written).
     */
    bool disableInterrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Subscribe a callback to edges on INT1 or INT2.
     *
     *  The callback receives the result of pollInterrupt(). Edge polarity follows
     *  setInterruptPolarity(). The subscription uses static state, so only one
     *  LSM303Full may subscribe per pin at a time; a later subscription replaces an earlier one.
     *
     *  @param callback  Function receiving the poll status byte. Must be non-null.
     *  @param pin       1 or 2.
     *  @param intPin    InputPin wired to that INT line. nullptr means accelConnection.intPin() for pin 1,
     *                   and pin 2 needs an explicit InputPin.
     *  @return false if no InputPin is available, the pin is not 1 or 2, or the pin cannot take another handler.
     */
    bool onInterrupt(void (*callback)(uint8_t status), uint8_t pin = 1, InputPin* intPin = nullptr);

    /** @brief Unsubscribe the callback from INT1 or INT2.
     *  @param pin  1 or 2.
     */
    void offInterrupt(uint8_t pin = 1);

    /** @brief Read the source registers of every enabled source and return the combined status.
     *
     *  Bit positions match LSM303Source values. Reading INT1_SOURCE_A, INT2_SOURCE_A or
     *  CLICK_SRC_A clears their latched flags. MAG_DATA_READY is always checked, since it
     *  cannot be routed.
     *
     *  @return Status byte (bit 0 DATA_READY, 1 WATERMARK, 2 OVERRUN, 3 IA1, 4 IA2, 5 CLICK, 7 MAG_DATA_READY).
     */
    uint8_t pollInterrupt();

    /** @brief Reboot the accelerometer memory (CTRL_REG5_A BOOT), wait 5 ms, and rerun the initialization sequence.
     *
     *  The datasheet does not say whether control registers survive a reboot, so the
     *  driver rewrites its defaults. Routing and temperature state are reset.
     */
    void reboot();

private:
    static void _trampoline1();
    static void _trampoline2();
    static void _dispatch(uint8_t idx);
    bool _routeTarget(uint16_t source, uint8_t& pin, uint8_t& reg, uint8_t& bit) const;

    uint16_t _routed[2] = {0, 0};   ///< Sources routed to INT1 / INT2 by this driver
    InputPin* _intPinUsed[2] = {nullptr, nullptr};
    static void (*_cb[2])(uint8_t);
    static LSM303Full* _owner[2];
};
