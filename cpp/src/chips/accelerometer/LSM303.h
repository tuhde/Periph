#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief LSM303DLHC 3-axis accelerometer + 3-axis magnetometer e-compass (STMicroelectronics) — minimal interface.
 *
 *  System-in-package combining a three-axis MEMS accelerometer (I²C address
 *  0x19) and a three-axis magnetometer (I²C address 0x1E) behind two
 *  independent I²C slave addresses on one bus. The driver treats the module
 *  as one logical device with two connections (the same pattern as MPU9250);
 *  the compass heading is computed in software from both sensors.
 *
 *  The two slaves use different I²C framing: the accelerometer sub-address
 *  bit 7 enables address auto-increment (the driver sets it for every
 *  multi-byte transfer); the magnetometer has an address pointer that always
 *  auto-increments and needs no extra bit. Magnetometer outputs are X, Z, Y
 *  with the high byte first — the driver reorders to X, Y, Z. The
 *  accelerometer outputs are X, Y, Z low byte first.
 *
 *  Default configuration (written at construction):
 *  - Accelerometer: ±2 *g* (1 mg/LSB), 100 Hz ODR, high-resolution mode,
 *    all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
 *    all interrupt routing cleared
 *  - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
 *    temperature sensor off
 *
 *  @param accel_connection RegisterConnection (I²C or SMBus) bound to the
 *                          accelerometer slave address 0x19.
 *  @param mag_connection   RegisterConnection (I²C or SMBus) bound to the
 *                          magnetometer slave address 0x1E, on the same bus.
 */
class LSM303Minimal {
public:
    /** @brief Default 7-bit I²C address of the accelerometer slave. */
    static constexpr uint8_t ACCEL_I2C_ADDRESS = 0x19;
    /** @brief Default 7-bit I²C address of the magnetometer slave. */
    static constexpr uint8_t MAG_I2C_ADDRESS   = 0x1E;

    /** @brief Construct and initialise the LSM303.
     *  @param accel_connection RegisterConnection bound to the accelerometer slave 0x19.
     *  @param mag_connection   RegisterConnection bound to the magnetometer slave 0x1E.
     */
    LSM303Minimal(RegisterConnection& accel_connection, RegisterConnection& mag_connection);

    /** @brief Read 3-axis linear acceleration.
     *
     *  Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so
     *  all six bytes belong to one sample (BDU=1). Per axis: sign-extend the
     *  16-bit pair, arithmetic-shift right per mode (4/6/8), scale with the
     *  mode- and range-dependent sensitivity (mg/LSB table).
     *
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read_acceleration(float& x, float& y, float& z);

    /** @brief Read 3-axis magnetic flux density.
     *
     *  Burst-reads the six output registers 0x03..0x08 (on the wire: X, Z,
     *  Y, each high byte first) in one transaction and reorders to X, Y, Z.
     *  Per axis: raw / gain (LSB/gauss, Z lower than X and Y) * 100 µT.
     *
     *  @param x Output X magnetic flux density in µT.
     *  @param y Output Y magnetic flux density in µT.
     *  @param z Output Z magnetic flux density in µT.
     */
    void read_magnetic(float& x, float& y, float& z);

protected:
    // Accelerometer register map (sub-address bit 7 = auto-increment).
    static constexpr uint8_t REG_CTRL1_A      = 0x20;
    static constexpr uint8_t REG_CTRL2_A      = 0x21;
    static constexpr uint8_t REG_CTRL3_A      = 0x22;
    static constexpr uint8_t REG_CTRL4_A      = 0x23;
    static constexpr uint8_t REG_CTRL5_A      = 0x24;
    static constexpr uint8_t REG_CTRL6_A      = 0x25;
    static constexpr uint8_t REG_REFERENCE_A  = 0x26;
    static constexpr uint8_t REG_STATUS_A     = 0x27;
    static constexpr uint8_t REG_OUT_X_L_A    = 0x28;
    static constexpr uint8_t REG_FIFO_CTRL_A  = 0x2E;
    static constexpr uint8_t REG_FIFO_SRC_A   = 0x2F;
    static constexpr uint8_t REG_INT1_CFG_A   = 0x30;
    static constexpr uint8_t REG_INT1_SRC_A   = 0x31;
    static constexpr uint8_t REG_INT1_THS_A   = 0x32;
    static constexpr uint8_t REG_INT1_DUR_A   = 0x33;
    static constexpr uint8_t REG_INT2_CFG_A   = 0x34;
    static constexpr uint8_t REG_INT2_SRC_A   = 0x35;
    static constexpr uint8_t REG_INT2_THS_A   = 0x36;
    static constexpr uint8_t REG_INT2_DUR_A   = 0x37;
    static constexpr uint8_t REG_CLICK_CFG_A  = 0x38;
    static constexpr uint8_t REG_CLICK_SRC_A  = 0x39;
    static constexpr uint8_t REG_CLICK_THS_A  = 0x3A;
    static constexpr uint8_t REG_TIME_LIMIT_A = 0x3B;
    static constexpr uint8_t REG_TIME_LAT_A   = 0x3C;
    static constexpr uint8_t REG_TIME_WIN_A   = 0x3D;

    // Magnetometer register map (address pointer auto-increments; no auto-inc bit).
    static constexpr uint8_t REG_CRA_M     = 0x00;
    static constexpr uint8_t REG_CRB_M     = 0x01;
    static constexpr uint8_t REG_MR_M      = 0x02;
    static constexpr uint8_t REG_OUT_X_H_M = 0x03;
    static constexpr uint8_t REG_SR_M      = 0x09;
    static constexpr uint8_t REG_IRA_M     = 0x0A;
    static constexpr uint8_t REG_IRB_M     = 0x0B;
    static constexpr uint8_t REG_IRC_M     = 0x0C;
    static constexpr uint8_t REG_TEMP_H_M  = 0x31;
    static constexpr uint8_t REG_TEMP_L_M  = 0x32;

    // Accelerometer output data rates (CTRL_REG1_A ODR bits 7:4).
    static constexpr uint8_t ODR_1HZ    = 0x1;
    static constexpr uint8_t ODR_10HZ   = 0x2;
    static constexpr uint8_t ODR_25HZ   = 0x3;
    static constexpr uint8_t ODR_50HZ   = 0x4;
    static constexpr uint8_t ODR_100HZ  = 0x5;
    static constexpr uint8_t ODR_200HZ  = 0x6;
    static constexpr uint8_t ODR_400HZ  = 0x7;
    static constexpr uint8_t ODR_1620HZ = 0x8;  // low-power mode only
    static constexpr uint8_t ODR_1344HZ = 0x9;  // 1.344 kHz normal / 5.376 kHz low-power

    // Operating modes (LPen / HR split).
    static constexpr uint8_t MODE_LOW_POWER = 0;
    static constexpr uint8_t MODE_NORMAL    = 1;
    static constexpr uint8_t MODE_HIGH_RES  = 2;

    // CTRL_REG1 / CTRL_REG4 bit masks.
    static constexpr uint8_t LPEN_BIT = 0x08;  // CTRL_REG1_A bit 3
    static constexpr uint8_t HR_BIT   = 0x08;  // CTRL_REG4_A bit 3
    static constexpr uint8_t FS_MASK  = 0x30;  // CTRL_REG4_A bits 5:4

    // CTRL_REG5_A bits.
    static constexpr uint8_t BOOT_BIT    = 0x80;
    static constexpr uint8_t FIFO_EN_BIT = 0x40;

    // Register defaults written by init.
    static constexpr uint8_t CTRL4_DEFAULT = 0x88;  // BDU=1, FS=00, HR=1
    static constexpr uint8_t CTRL1_DEFAULT = 0x57;  // ODR=100 Hz, LPen=0, X/Y/Z enabled

    RegisterConnection& _accel;
    RegisterConnection& _mag;

    uint8_t  _range_bits = 0;   // 0..3: ±2/4/8/16 g
    uint8_t  _mode       = 2;   // MODE_HIGH_RES
    float    _odr_hz     = 100.0f;  // accelerometer output data rate
    uint8_t  _mag_gn     = 0;   // gain index 0..6
    float    _mag_odr_hz = 15.0f;
    bool     _temp_enabled = false;

    // Sensitivity in mg/LSB at the current mode and range.
    float _accel_sensitivity_g();
    // Convert a 6-byte accelerometer burst to (x, y, z) signed counts.
    void _convert_accel_sample(const uint8_t* raw,
                               int16_t& cx, int16_t& cy, int16_t& cz);

    /** @brief Read the magnetometer gains (LSB/gauss) for X/Y. */
    static float _mag_gain_xy(uint8_t index);
    /** @brief Read the magnetometer gain (LSB/gauss) for Z (lower than X/Y). */
    static float _mag_gain_z(uint8_t index);

    void    _accel_write(uint8_t reg, uint8_t value);
    uint8_t _accel_read(uint8_t reg);
    /** @brief Multi-byte accelerometer transfer: sets the auto-increment bit (reg | 0x80). */
    void    _accel_read_burst(uint8_t reg, uint8_t* buf, size_t len);
    void    _mag_write(uint8_t reg, uint8_t value);
    uint8_t _mag_read(uint8_t reg);
    void    _mag_read_burst(uint8_t reg, uint8_t* buf, size_t len);
    static void _delay_ms(uint32_t ms);
};

/** @brief LSM303DLHC full interface — extends LSM303Minimal with complete
 *  functionality.
 *
 *  Adds accelerometer range / data-rate / power-mode configuration, per-axis
 *  enable, raw data access, the selectable high-pass filter, the 32-level
 *  FIFO, two inertial interrupt generators with free-fall and 6D/4D
 *  orientation, single/double click detection, interrupt routing across
 *  INT1/INT2 with edge callbacks, the magnetometer gain / data-rate /
 *  operating-mode controls, the relative temperature sensor, the software
 *  compass heading, and memory reboot.
 *
 *  Invalid parameters (a range or rate the chip does not support, an
 *  out-of-range generator or axis mask) are rejected silently — no bus write
 *  happens and no state changes; C++ drivers in this repo never throw.
 *
 *  @param accel_connection RegisterConnection (I²C or SMBus) bound to the
 *                          accelerometer slave address 0x19.
 *  @param mag_connection   RegisterConnection (I²C or SMBus) bound to the
 *                          magnetometer slave address 0x1E, on the same bus.
 */
class LSM303Full : public LSM303Minimal {
public:
    // Interrupt source bits — the poll_interrupt() status layout.
    static constexpr uint16_t SOURCE_DATA_READY     = 0x0001;
    static constexpr uint16_t SOURCE_WATERMARK      = 0x0002;
    static constexpr uint16_t SOURCE_OVERRUN        = 0x0004;
    static constexpr uint16_t SOURCE_IA1            = 0x0008;
    static constexpr uint16_t SOURCE_IA2            = 0x0010;
    static constexpr uint16_t SOURCE_CLICK          = 0x0020;
    static constexpr uint16_t SOURCE_BOOT           = 0x0040;  // pin-only, never polled
    static constexpr uint16_t SOURCE_MAG_DATA_READY = 0x0080;  // DRDY pin only, not routable

    // CTRL_REG3_A routing bits (INT1 side).
    static constexpr uint8_t I1_CLICK   = 0x80;
    static constexpr uint8_t I1_AOI1    = 0x40;
    static constexpr uint8_t I1_AOI2    = 0x20;
    static constexpr uint8_t I1_DRDY1   = 0x10;
    static constexpr uint8_t I1_WTM     = 0x04;
    static constexpr uint8_t I1_OVERRUN = 0x01;

    // CTRL_REG6_A routing bits (INT2 side).
    static constexpr uint8_t I2_CLICK = 0x80;
    static constexpr uint8_t I2_INT1  = 0x40;
    static constexpr uint8_t I2_INT2  = 0x20;
    static constexpr uint8_t BOOT_I1  = 0x10;

    /** @brief Construct the Full driver; runs the Minimal initialisation, then
     *  clears the interrupt-subscription state.
     *  @param accel_connection RegisterConnection bound to the accelerometer slave 0x19.
     *  @param mag_connection   RegisterConnection bound to the magnetometer slave 0x1E.
     */
    explicit LSM303Full(RegisterConnection& accel_connection, RegisterConnection& mag_connection);

    /** @brief Set the accelerometer full-scale range.
     *  @param range_g One of 2, 4, 8, 16 (*g*). Updates the cached sensitivity.
     */
    void set_range(uint8_t range_g);

    /** @brief Set the accelerometer output data rate.
     *
     *  @param rate_hz One of 0 (power-down), 1, 10, 25, 50, 100, 200, 400,
     *  1344, 1620 or 5376 Hz. 1620 Hz and 5376 Hz exist in low-power mode
     *  only, 1344 Hz in normal / high-resolution mode; an illegal
     *  combination is rejected without a bus write.
     */
    void set_data_rate(uint16_t rate_hz);

    /** @brief Set the accelerometer operating mode (resolution).
     *  @param mode 0 = low-power (8-bit), 1 = normal (10-bit), 2 =
     *              high-resolution (12-bit). Updates the cached sensitivity.
     */
    void set_mode(uint8_t mode);

    /** @brief Enable or disable the accelerometer axes.
     *  @param x Enable the X axis.
     *  @param y Enable the Y axis.
     *  @param z Enable the Z axis.
     */
    void set_axes(bool x = true, bool y = true, bool z = true);

    /** @brief Enter power-down (ODR = 0000). Register contents are retained. */
    void power_down();

    /** @brief Read raw accelerometer counts at the current resolution.
     *  @param x Output X signed counts (12/10/8-bit right-aligned per mode).
     *  @param y Output Y signed counts.
     *  @param z Output Z signed counts.
     */
    void read_acceleration_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Check whether a new accelerometer sample is available.
     *  @return true when STATUS_REG_A.ZYXDA is set.
     */
    bool new_data_available();

    /** @brief Read the accelerometer status register.
     *  @return STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun, ZYXDA data-ready).
     */
    uint8_t read_status();

    /** @brief Configure the high-pass filter.
     *  @param mode          HPM bits 7:6 (0 normal, 1 reference signal, 2 normal, 3 autoreset).
     *  @param cutoff        HPCF bits 5:4 raw code 0–3.
     *  @param filter_output Filtered data goes to the output registers and FIFO.
     *  @param on_ia1        High-pass filter on interrupt generator 1.
     *  @param on_ia2        High-pass filter on interrupt generator 2.
     *  @param on_click      High-pass filter on click detection.
     */
    void set_high_pass(uint8_t mode = 0, uint8_t cutoff = 0, bool filter_output = false,
                       bool on_ia1 = false, bool on_ia2 = false, bool on_click = false);

    /** @brief Reset the high-pass filter (HPM = 00) by reading REFERENCE_A.
     *  @return The REFERENCE_A register value.
     */
    uint8_t reset_high_pass();

    /** @brief Write the high-pass reference register (for HPM = 01).
     *  @param value Reference value 0–255.
     */
    void set_reference(uint8_t value);

    /** @brief Configure the 32-level FIFO.
     *  @param mode          0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO).
     *  @param watermark     Watermark level 0–31.
     *  @param trigger_int2  Trigger mode source: false = INT1, true = INT2.
     */
    void set_fifo(uint8_t mode, uint8_t watermark = 0, bool trigger_int2 = false);

    /** @brief Number of unread sample sets in the FIFO (0–32).
     *  @return FSS bits 4:0 from FIFO_SRC_REG_A.
     */
    uint8_t fifo_level();

    /** @brief Drain up to @p max_samples sample sets from the FIFO.
     *
     *  Each sample is popped with one 6-byte burst read of
     *  OUT_X_L_A..OUT_Z_H_A. Samples are converted to *g* with the current
     *  sensitivity.
     *
     *  @param x_buf X destination buffer; capacity max_samples floats.
     *  @param y_buf Y destination buffer; capacity max_samples floats.
     *  @param z_buf Z destination buffer; capacity max_samples floats.
     *  @param max_samples Maximum number of sample sets to read.
     *  @return Number of sample sets actually read.
     */
    uint8_t read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples);

    /** @brief Read the decoded FIFO status.
     *  @param watermark Output: FIFO content exceeds the watermark.
     *  @param overrun   Output: FIFO full; next sample overwrites the oldest.
     *  @param empty     Output: FIFO empty.
     *  @param level     Output: number of unread sample sets (FSS 0–32).
     */
    void read_fifo_status(bool& watermark, bool& overrun, bool& empty, uint8_t& level);

    /** @brief Configure one inertial interrupt generator.
     *  @param generator   1 or 2.
     *  @param events      6-bit event mask (bit 5 = Z high, 4 = Z low,
     *                     3 = Y high, 2 = Y low, 1 = X high, 0 = X low;
     *                     the same bits select the direction zones in 6D modes).
     *  @param threshold_g Event threshold in *g* (1 LSb = full-scale / 128).
     *  @param duration_ms Minimum event duration in ms (1 LSb = 1/ODR).
     *  @param and_mode    AND combination of the enabled events (false = OR).
     */
    void set_motion_interrupt(uint8_t generator, uint8_t events, float threshold_g,
                              float duration_ms = 0, bool and_mode = false);

    /** @brief Configure free-fall detection: all three axes low, AND mode (INTx_CFG_A = 0x95).
     *  @param generator   1 (default) or 2.
     *  @param threshold_g Free-fall threshold in *g* (default 0.35).
     *  @param duration_ms Minimum free-fall duration in ms (default 30).
     */
    void set_free_fall(uint8_t generator = 1, float threshold_g = 0.35f, float duration_ms = 30);

    /** @brief Configure 6D / 4D orientation recognition on one generator.
     *  @param generator 1 (default) or 2.
     *  @param position  false = 6D movement recognition (one-ODR pulse),
     *                   true = 6D position recognition (level inside a known zone).
     *  @param four_d    4D detection (Z-axis zone disabled; needs the 6D bit).
     */
    void set_orientation(uint8_t generator = 1, bool position = false, bool four_d = false);

    /** @brief Latch (or unlatch) one generator's interrupt until its source is read.
     *  @param generator 1 or 2.
     *  @param enabled   Latch INT output until INTx_SOURCE_A is read.
     */
    void set_latch(uint8_t generator, bool enabled);

    /** @brief Read one generator's source register — clears the interrupt.
     *  @param generator 1 or 2.
     *  @return INTx_SOURCE_A byte (bit 6 = IA, bits 5:0 = axis high/low flags).
     */
    uint8_t read_interrupt_source(uint8_t generator);

    /** @brief Configure single / double click detection.
     *  @param single        Axis mask for single click (X=1, Y=2, Z=4; 0 = off).
     *  @param double_click  Axis mask for double click (X=1, Y=2, Z=4; 0 = off).
     *  @param threshold_g   Click threshold in *g* (1 LSb = full-scale / 128).
     *  @param time_limit_ms Maximum click duration in ms (1 LSb = 1/ODR, 7-bit).
     *  @param latency_ms    Click latency in ms (1 LSb = 1/ODR, 8-bit).
     *  @param window_ms     Time window for a second click (1 LSb = 1/ODR, 8-bit).
     */
    void set_click(uint8_t single = 0, uint8_t double_click = 0, float threshold_g = 0.5f,
                   float time_limit_ms = 10, float latency_ms = 20, float window_ms = 50);

    /** @brief Read the click source register — clears the click interrupt.
     *  @return CLICK_SRC_A byte (bit 6 = IA, bit 5 = DCLICK, bit 4 = SCLICK,
     *          bit 3 = sign, bits 2:0 = Z/Y/X event axis).
     */
    uint8_t read_click_source();

    /** @brief Set the INT1 / INT2 output polarity.
     *  @param active_low true = INT pins active-low, false = active-high.
     */
    void set_interrupt_polarity(bool active_low);

    /** @brief Set the magnetometer full-scale range (gain).
     *  @param range_ut One of 130, 190, 250, 400, 470, 560, 810 (µT).
     *                  Updates the cached conversion gains.
     */
    void set_mag_range(uint16_t range_ut);

    /** @brief Set the magnetometer output data rate.
     *  @param rate_hz One of 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz.
     *                 Preserves the TEMP_EN setting.
     */
    void set_mag_data_rate(float rate_hz);

    /** @brief Set the magnetometer operating mode.
     *  @param mode 0 continuous conversion, 1 single conversion (one
     *              conversion, then back to sleep), 2 sleep.
     */
    void set_mag_mode(uint8_t mode);

    /** @brief Read raw magnetometer counts (X, Y, Z order, after reordering).
     *  @param x Output X signed 12-bit counts.
     *  @param y Output Y signed 12-bit counts.
     *  @param z Output Z signed 12-bit counts.
     */
    void read_magnetic_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Check whether a new magnetometer sample is available.
     *  @return true when SR_REG_M.DRDY is set.
     */
    bool mag_data_ready();

    /** @brief Read the decoded magnetometer status register.
     *  @param drdy Output: new set of measurements available.
     *  @param lock Output: output registers locked mid-read.
     */
    void read_mag_status(bool& drdy, bool& lock);

    /** @brief Read the magnetometer identification registers.
     *  @param ida Output: IRA (0x48, ASCII "H").
     *  @param idb Output: IRB (0x34, ASCII "4").
     *  @param idc Output: IRC (0x33, ASCII "3").
     */
    void read_mag_identity(uint8_t& ida, uint8_t& idb, uint8_t& idc);

    /** @brief Enable or disable the relative temperature sensor.
     *  @param enabled true = TEMP_EN set (updates at the magnetometer data rate).
     */
    void enable_temperature(bool enabled);

    /** @brief Read the relative temperature.
     *
     *  The sensor is relative only — the datasheet gives no zero-degree
     *  reference — so the value is a delta, never an absolute °C. Enables
     *  TEMP_EN on first use (read-modify-write).
     *
     *  @return Relative temperature in °C (8 LSB/°C).
     */
    float read_temperature_delta();

    /** @brief Compute the compass heading from both sensors.
     *
     *  Uses the atan2(y, x) convention, wrapped to 0–360°. The datasheet
     *  documents neither the axis alignment between the two dies nor a
     *  declination, so the result assumes both sensors report in the same
     *  body frame and is magnetic (not true) north.
     *
     *  @param tilt_compensate true (default) = project the magnetic field
     *  onto the horizontal plane using the current acceleration; false =
     *  flat compass from the magnetic X/Y components only.
     *  @return Heading in degrees, 0–360.
     */
    float heading(bool tilt_compensate = true);

    /** @brief Route one interrupt source to INT1 or INT2.
     *
     *  Only routes the source; the generator or function behind it is
     *  configured with the set_motion_interrupt / set_click / ... methods.
     *  @p pin is ignored for INT1-only sources; SOURCE_MAG_DATA_READY is
     *  always on the dedicated magnetometer DRDY pin and never routable
     *  (rejected without a bus write); SOURCE_BOOT is INT2-only.
     *
     *  @param source One of the SOURCE_* constants.
     *  @param pin    1 (default) or 2.
     */
    void enable_interrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Remove one interrupt source from INT1 or INT2.
     *  @param source One of the SOURCE_* constants.
     *  @param pin    1 (default) or 2.
     */
    void disable_interrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Read the source registers of every enabled interrupt source.
     *
     *  Reading INTx_SOURCE_A / CLICK_SRC_A clears their latched flags, so a
     *  poll acknowledges the INT line.
     *
     *  @return Combined status: bit 0 SOURCE_DATA_READY, bit 1
     *  SOURCE_WATERMARK, bit 2 SOURCE_OVERRUN, bit 3 SOURCE_IA1, bit 4
     *  SOURCE_IA2, bit 5 SOURCE_CLICK, bit 7 SOURCE_MAG_DATA_READY.
     *  SOURCE_BOOT is pin-only and never reported.
     */
    uint16_t poll_interrupt();

    /** @brief Subscribe to INT1 or INT2 assertions.
     *
     *  Uses the accelerometer connection's INT pin (connection.intPin()) —
     *  both INT lines typically share one physical GPIO. Without a wired
     *  INT pin no edge delivery happens (C++ drivers here never spawn
     *  threads); poll poll_interrupt() directly instead. The callback
     *  receives the combined status from poll_interrupt().
     *
     *  @param callback Function pointer void(*)(uint16_t status).
     *  @param pin      1 (default) or 2.
     */
    void on_interrupt(void (*callback)(uint16_t status), uint8_t pin = 1);

    /** @brief Unsubscribe from INT1 or INT2 assertions.
     *  @param pin 1 (default) or 2.
     */
    void off_interrupt(uint8_t pin = 1);

    /** @brief Reboot the accelerometer memory content (BOOT = 1).
     *
     *  Reloads the factory trim values; waits ≥ 5 ms. The datasheet does
     *  not say whether the control registers survive, so re-run the
     *  initialisation (construct a fresh driver) afterwards.
     */
    void reboot();

private:
    void (*_int_callbacks[2])(uint16_t) = { nullptr, nullptr };
    uint16_t _int_sources = 0;
    InputPin* _intPinUsed = nullptr;

    /** @brief Map generator 1/2 to its (CFG, SRC, THS, DUR) register triple. */
    void _int_regs(uint8_t& cfg, uint8_t& src, uint8_t& ths, uint8_t& dur, uint8_t generator);
    /** @brief Convert a threshold in g to the 7-bit INTx_THS / CLICK_THS code. */
    uint8_t _encode_threshold(float threshold_g);
    /** @brief Convert a duration in ms to the 1/ODR code, clamped to the width. */
    uint8_t _encode_duration(float duration_ms, uint8_t max_bits = 7);

    static LSM303Full* _activeInstance;
    static void _edgeTrampoline();
    /** @brief Shared edge handler: polls the sources and dispatches to both callbacks. */
    void _handleEdge();
};
