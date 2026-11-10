#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief QMI8658A 6-axis IMU (QST) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate with no configuration
 * beyond the connection. The constructor runs the datasheet initialisation
 * sequence: soft reset, WHO_AM_I check, address auto-increment on,
 * little-endian reads, CTRL9 handshake by polling, both sensors on.
 *
 * Default configuration (written at construction):
 * - Accelerometer ±2 g, gyroscope ±256 dps
 * - Both output data rates code 5 (224.2 Hz in 6DOF mode)
 * - Both low-pass filters on, mode 3 (13.37 % of ODR)
 * - FIFO bypass (data-ready active), all INT pins high-Z, engines off
 *
 * Configuration registers (CTRL1–CTRL9, CAL1–CAL4) are always written one
 * register per transaction. The constructor aborts if WHO_AM_I does not read
 * 0x05. I3C and 3-wire SPI are out of scope.
 *
 * @param connection   RegisterConnection (I²C at 0x6B with SA0 = GND / 0x6A
 *                     with SA0 = VDDIO, SMBus, or SPI built with read bit
 *                     0x80 and no multi-byte bit) pointing at the device.
 */
class QMI8658AMinimal {
public:
    QMI8658AMinimal(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration.
     *  @param[out] x  X acceleration in m/s².
     *  @param[out] y  Y acceleration in m/s².
     *  @param[out] z  Z acceleration in m/s².
     */
    void accel(float& x, float& y, float& z);

    /** @brief Read 3-axis angular rate.
     *  @param[out] x  X angular rate in rad/s.
     *  @param[out] y  Y angular rate in rad/s.
     *  @param[out] z  Z angular rate in rad/s.
     */
    void gyro(float& x, float& y, float& z);

protected:
    static constexpr uint8_t REG_WHO_AM_I      = 0x00;
    static constexpr uint8_t REG_REVISION_ID   = 0x01;
    static constexpr uint8_t REG_CTRL1         = 0x02;
    static constexpr uint8_t REG_CTRL2         = 0x03;
    static constexpr uint8_t REG_CTRL3         = 0x04;
    static constexpr uint8_t REG_CTRL5         = 0x06;
    static constexpr uint8_t REG_CTRL7         = 0x08;
    static constexpr uint8_t REG_CTRL8         = 0x09;
    static constexpr uint8_t REG_CTRL9         = 0x0A;
    static constexpr uint8_t REG_CAL1_L        = 0x0B;
    static constexpr uint8_t REG_FIFO_WTM_TH   = 0x13;
    static constexpr uint8_t REG_FIFO_CTRL     = 0x14;
    static constexpr uint8_t REG_FIFO_SMPL_CNT = 0x15;
    static constexpr uint8_t REG_FIFO_STATUS   = 0x16;
    static constexpr uint8_t REG_FIFO_DATA     = 0x17;
    static constexpr uint8_t REG_STATUSINT     = 0x2D;
    static constexpr uint8_t REG_STATUS0       = 0x2E;
    static constexpr uint8_t REG_STATUS1       = 0x2F;
    static constexpr uint8_t REG_TIMESTAMP     = 0x30;
    static constexpr uint8_t REG_TEMP_L        = 0x33;
    static constexpr uint8_t REG_AX_L          = 0x35;
    static constexpr uint8_t REG_GX_L          = 0x3B;
    static constexpr uint8_t REG_COD_STATUS    = 0x46;
    static constexpr uint8_t REG_FW_VERSION    = 0x49;
    static constexpr uint8_t REG_RESET_STATUS  = 0x4D;
    static constexpr uint8_t REG_DVX_L         = 0x51;
    static constexpr uint8_t REG_TAP_STATUS    = 0x59;
    static constexpr uint8_t REG_STEP_CNT      = 0x5A;
    static constexpr uint8_t REG_RESET         = 0x60;

    static constexpr uint8_t WHO_AM_I_VALUE = 0x05;
    static constexpr uint8_t RESET_CMD      = 0xB0;

    static constexpr uint8_t CMD_ACK                = 0x00;
    static constexpr uint8_t CMD_RST_FIFO           = 0x04;
    static constexpr uint8_t CMD_REQ_FIFO           = 0x05;
    static constexpr uint8_t CMD_WRITE_WOM          = 0x08;
    static constexpr uint8_t CMD_ACCEL_OFFSET       = 0x09;
    static constexpr uint8_t CMD_GYRO_OFFSET        = 0x0A;
    static constexpr uint8_t CMD_CONFIG_TAP         = 0x0C;
    static constexpr uint8_t CMD_CONFIG_PEDOMETER   = 0x0D;
    static constexpr uint8_t CMD_CONFIG_MOTION      = 0x0E;
    static constexpr uint8_t CMD_RESET_PEDOMETER    = 0x0F;
    static constexpr uint8_t CMD_COPY_USID          = 0x10;
    static constexpr uint8_t CMD_SET_RPU            = 0x11;
    static constexpr uint8_t CMD_AHB_CLOCK_GATING   = 0x12;
    static constexpr uint8_t CMD_COD                = 0xA2;
    static constexpr uint8_t CMD_APPLY_GYRO_GAINS   = 0xAA;

    RegisterConnection& _connection;
    uint8_t _ctrl1 = 0x40;
    uint8_t _ctrl7 = 0x03;
    uint8_t _ctrl8 = 0x80;
    uint8_t _accel_fs = 0;
    uint8_t _gyro_fs = 4;
    uint8_t _accel_odr = 5;
    uint8_t _gyro_odr = 5;
    float   _accel_sens = 16384.0f;
    float   _gyro_sens = 128.0f;
    uint8_t _fifo_ctrl = 0;
    uint8_t _fifo_wtm = 0;
    bool    _sync = false;

    void _w(uint8_t reg, uint8_t value);
    uint8_t _r1(uint8_t reg);
    void _update_sens();
    virtual void _read_data(uint8_t reg, uint8_t* buf, size_t len);
    void _vec(uint8_t reg, float sens, float scale, float& x, float& y, float& z);
    void _reset_and_configure();
};

/** @brief QMI8658A full interface — extends QMI8658AMinimal with complete functionality.
 *
 * Adds raw and burst reads, temperature, sample counter, full-scale / ODR /
 * filter configuration, power control, SyncSample data locking, the FIFO,
 * the motion engines (any / no / significant motion, tap, pedometer,
 * wake-on-motion), offsets, calibration-on-demand, self-test, device info,
 * pull-up control and interrupts.
 *
 * @param connection   RegisterConnection (I²C, SMBus or SPI) pointing at the device.
 */
class QMI8658AFull : public QMI8658AMinimal {
public:
    static constexpr uint16_t SOURCE_DATA_READY         = 0x0001;
    static constexpr uint16_t SOURCE_FIFO_WATERMARK     = 0x0002;
    static constexpr uint16_t SOURCE_FIFO_FULL          = 0x0004;
    static constexpr uint16_t SOURCE_ANY_MOTION         = 0x0008;
    static constexpr uint16_t SOURCE_NO_MOTION          = 0x0010;
    static constexpr uint16_t SOURCE_SIGNIFICANT_MOTION = 0x0020;
    static constexpr uint16_t SOURCE_STEP               = 0x0040;
    static constexpr uint16_t SOURCE_TAP                = 0x0080;
    static constexpr uint16_t SOURCE_WAKE_ON_MOTION     = 0x0100;

    QMI8658AFull(RegisterConnection& connection);

    /** @brief Read raw 3-axis accelerometer values (signed 16-bit counts). */
    void accel_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read raw 3-axis gyroscope values (signed 16-bit counts). */
    void gyro_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Burst-read temperature, acceleration and angular rate in one sample.
     *  @param[out] accel   X, Y, Z acceleration in m/s² (3 floats).
     *  @param[out] gyro    X, Y, Z angular rate in rad/s (3 floats).
     *  @param[out] temp_c  Die temperature in °C.
     */
    void read_all(float accel[3], float gyro[3], float& temp_c);

    /** @brief Read the die temperature in °C. */
    float temperature();

    /** @brief Read the 24-bit sample counter (wraps at 0xFFFFFF). */
    uint32_t timestamp();

    /** @brief True when an enabled sensor has new data (STATUSINT.Avail while SyncSample is on). */
    bool data_ready();

    /** @brief Configure the accelerometer (CTRL2).
     *  @param full_scale  0 = ±2 g, 1 = ±4 g, 2 = ±8 g, 3 = ±16 g.
     *  @param odr         ODR code 0–8 or 12–15 (12–15 only with the gyroscope off).
     */
    void configure_accel(uint8_t full_scale = 0, uint8_t odr = 5);

    /** @brief Configure the gyroscope (CTRL3).
     *  @param full_scale  0 = ±16 dps … 7 = ±2048 dps (doubling).
     *  @param odr         ODR code 0–8.
     */
    void configure_gyro(uint8_t full_scale = 4, uint8_t odr = 5);

    /** @brief Configure the low-pass filters (CTRL5); modes 0–3 = 2.66 / 3.63 / 5.39 / 13.37 % of ODR. */
    void configure_filters(bool accel_filter = true, uint8_t accel_mode = 3,
                           bool gyro_filter = true, uint8_t gyro_mode = 3);

    /** @brief Enable or disable the sensors (CTRL7 aEN / gEN / gSN).
     *  The gyroscope needs 150 ms + 3/ODR, the accelerometer 3 ms + 3/ODR to become valid. */
    void set_sensors(bool accel = true, bool gyro = true, bool gyro_snooze = false);

    /** @brief Power down (CTRL1.SensorDisable, sensors disabled first) or wake the chip. */
    void set_power_down(bool down = true);

    /** @brief Enable or disable SyncSample data locking; incompatible with the FIFO and motion engines. */
    void set_sync_sample(bool enabled);

    /** @brief Configure the FIFO.
     *  @param mode       0 = bypass, 1 = FIFO (stops when full), 2 = stream.
     *  @param size       0–3 = 16 / 32 / 64 / 128 samples.
     *  @param watermark  Watermark in samples (0 disables).
     */
    void configure_fifo(uint8_t mode = 2, uint8_t size = 3, uint8_t watermark = 0);

    /** @brief Raw FIFO_STATUS byte (full 7, watermark 6, overflow 5, not-empty 4). */
    uint8_t fifo_status();

    /** @brief Bytes in the FIFO (2 × sample-word count). */
    uint16_t fifo_count();

    /** @brief Drain the FIFO with the read-mode procedure (little-endian 6-byte frames, accel first).
     *  @param[out] buf  Destination buffer.
     *  @param      len  Maximum number of bytes to read.
     *  @return Number of bytes read.
     */
    uint16_t read_fifo(uint8_t* buf, uint16_t len);

    /** @brief Reset the FIFO (CTRL9 RST_FIFO). */
    void reset_fifo();

    /** @brief Configure the any-motion engine (threshold in g, 1/32 g steps; window in samples; axis mask bit 0 X, 1 Y, 2 Z). */
    void configure_any_motion(float threshold_g = 0.25f, uint8_t window = 3,
                              uint8_t axes = 0x07, bool logic_and = false);

    /** @brief Configure the no-motion engine (arguments as for any-motion). */
    void configure_no_motion(float threshold_g = 0.125f, uint8_t window = 30,
                             uint8_t axes = 0x07, bool logic_and = true);

    /** @brief Configure the significant-motion engine (windows in samples); needs any- and no-motion configured and enabled. */
    void configure_significant_motion(uint16_t wait_window = 150, uint16_t confirm_window = 600);

    /** @brief Enable the motion engines (CTRL8 bits 1–3). */
    void enable_motion(bool any_motion = false, bool no_motion = false, bool significant = false);

    /** @brief Configure the tap engine (two CTRL9 CONFIGURE_TAP commands).
     *  @param priority          Axis priority 0–5.
     *  @param peak_window       Peak window in samples.
     *  @param tap_window        Quiet window in samples.
     *  @param double_tap_window Double-tap window in samples.
     *  @param alpha             Acceleration averaging weight (1/128 steps).
     *  @param gamma             Magnitude averaging weight (1/128 steps).
     *  @param peak_threshold    Peak threshold in g² (1/1024 steps).
     *  @param quiet_threshold   Undefined-motion threshold in g² (1/1024 steps).
     */
    void configure_tap(uint8_t priority = 0, uint16_t peak_window = 20, uint16_t tap_window = 50,
                       uint16_t double_tap_window = 250, float alpha = 0.0625f, float gamma = 0.25f,
                       float peak_threshold = 0.8f, float quiet_threshold = 0.4f);

    /** @brief Enable or disable the tap engine (use an accelerometer ODR above 200 Hz). */
    void enable_tap(bool enabled = true);

    /** @brief Read the last tap event.
     *  @param[out] count     0 none, 1 single, 2 double.
     *  @param[out] axis      0 none, 1 X, 2 Y, 3 Z.
     *  @param[out] negative  true for the negative direction.
     */
    void tap_status(uint8_t& count, uint8_t& axis, bool& negative);

    /** @brief Configure the pedometer (two CTRL9 CONFIGURE_PEDOMETER commands; defaults for a 50 Hz ODR). */
    void configure_pedometer(uint16_t sample_count = 50, float peak_to_peak_mg = 200.0f,
                             float peak_mg = 100.0f, uint16_t time_up = 200, uint8_t time_low = 20,
                             uint8_t entry_count = 10, uint8_t precision = 0, uint8_t report_every = 4);

    /** @brief Enable or disable the pedometer; a 0→1 transition resets the count. */
    void enable_pedometer(bool enabled = true);

    /** @brief 24-bit step count (updated every report_every steps). */
    uint32_t step_count();

    /** @brief Clear the step count (CTRL9 RESET_PEDOMETER). */
    void reset_step_count();

    /** @brief Enter (threshold_mg 1–255) or leave (0) wake-on-motion mode.
     *  Uses accelerometer ±2 g at ODR code 12 (128 Hz).
     *  @param threshold_mg      Threshold in mg; 0 exits WoM mode.
     *  @param interrupt_pin     1 or 2.
     *  @param initial_level     Initial pin level 0 or 1.
     *  @param blanking_samples  Samples ignored after enabling (0–63).
     */
    void set_wake_on_motion(uint8_t threshold_mg, uint8_t interrupt_pin = 1,
                            uint8_t initial_level = 0, uint8_t blanking_samples = 0);

    /** @brief Apply an accelerometer offset in m/s² (signed 4.12 g; volatile). */
    void set_accel_offset(float x, float y, float z);

    /** @brief Apply a gyroscope offset in rad/s (signed 11.5 dps; volatile). */
    void set_gyro_offset(float x, float y, float z);

    /** @brief Gyroscope calibration-on-demand (~1.5 s, keep still).
     *  @param[out] cod_status  COD_STATUS register (may be nullptr).
     *  @return true on success.
     */
    bool calibrate_gyro(uint8_t* cod_status = nullptr);

    /** @brief Raw X/Y/Z gyroscope gains, valid right after calibrate_gyro(). */
    void get_gyro_gains(uint16_t& x, uint16_t& y, uint16_t& z);

    /** @brief Restore previously saved gyroscope gains. */
    void set_gyro_gains(uint16_t x, uint16_t y, uint16_t z);

    /** @brief Accelerometer self-test; @p xyz receives m/s². @return true if every axis exceeds 200 mg. */
    bool self_test_accel(float xyz[3]);

    /** @brief Gyroscope self-test; @p xyz receives rad/s. @return true if every axis exceeds 300 dps. */
    bool self_test_gyro(float xyz[3]);

    /** @brief Read revision, firmware version (3 bytes) and USID (6 bytes) via CTRL9 COPY_USID. */
    void device_info(uint8_t& revision, uint8_t firmware[3], uint8_t usid[6]);

    /** @brief Control the internal 200 kΩ pull-ups (false disables a group). */
    void set_pullups(bool aux = true, bool sdx = true, bool cs = true, bool i2c = true);

    /** @brief Soft reset and wait 15 ms; the sensors are off afterwards, run the init defaults again. */
    void reset();

    /** @brief Route the FIFO interrupt (CTRL1.FIFO_INT_SEL) and the motion-engine group (CTRL8.ACTIVITY_INT_SEL) to INT1 (1) or INT2 (2). */
    void set_interrupt_pins(uint8_t fifo_pin = 2, uint8_t activity_pin = 2);

    /** @brief Enable one interrupt source (SOURCE_* constant). */
    void enable_interrupt(uint16_t source);

    /** @brief Disable one interrupt source (SOURCE_* constant). */
    void disable_interrupt(uint16_t source);

    /** @brief Read STATUS0, STATUS1 and FIFO_STATUS and return the combined SOURCE_* bits. */
    uint16_t poll_interrupt();

    /** @brief Subscribe to rising edges on the connection's interrupt pin.
     *  The callback receives the poll_interrupt() status for a non-zero status.
     *  Only one QMI8658AFull instance can be subscribed at a time.
     */
    void on_interrupt(void (*callback)(uint16_t status));

    /** @brief Unsubscribe from interrupt-pin edges. */
    void off_interrupt();

protected:
    uint8_t  _any_cfg_axes = 0x07, _no_cfg_axes = 0x07;
    bool     _any_and = false, _no_and = true;
    float    _any_thr = 0.25f, _no_thr = 0.125f;
    uint8_t  _any_win = 3, _no_win = 30;
    uint16_t _sig_wait = 150, _sig_confirm = 600;
    uint16_t _int_sources = 0;
    uint8_t  _fifo_pin = 2, _activity_pin = 2, _wom_pin = 1;
    void (*_int_callback)(uint16_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static QMI8658AFull* _active_instance;

    void _read_data(uint8_t reg, uint8_t* buf, size_t len) override;
    void _set_ctrl1(uint8_t v);
    void _set_ctrl7(uint8_t v);
    void _set_ctrl8(uint8_t v);
    bool _wait_status(uint8_t mask, bool want, uint16_t timeout_ms);
    bool _ctrl9(uint8_t cmd, uint16_t timeout_ms = 100);
    bool _cal_cmd(uint8_t cmd, const uint8_t params[8], uint16_t timeout_ms = 100);
    void _two_sets(uint8_t cmd, const uint8_t set1[7], const uint8_t set2[7]);
    uint8_t _sensors_off();
    void _sensors_restore(uint8_t prev);
    void _send_motion();
    void _offset_cmd(uint8_t cmd, const int16_t codes[3]);
    bool _self_test(uint8_t ctrl_reg, uint8_t saved, float scale, float out[3]);
    uint8_t _source_pin(uint16_t source) const;
    void _update_int_pins();
    void _handle_edge();
    static void _edge_trampoline();
};
