#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief ICM-20948 9-axis MotionTracking device (TDK InvenSense) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate with no configuration
 * beyond the connection. The registers are organised in four user banks
 * selected through REG_BANK_SEL (0x7F); the driver caches the selected bank
 * and always returns to bank 0 after a bank 1–3 access. VDDIO is 1.71–1.95 V
 * only. SPI and the DMP are out of scope.
 *
 * Default configuration (written at construction):
 * - Gyroscope ±250 dps, low-pass filter 51.2 Hz (GYRO_DLPFCFG = 3)
 * - Accelerometer ±2 g, low-pass filter 50.4 Hz (ACCEL_DLPFCFG = 3)
 * - Both output data rates 102.3 Hz (divider 10)
 * - Clock auto (CLKSEL = 1), all six axes and the temperature sensor on
 * - FIFO, DMP, I²C master and all interrupts off
 *
 * The constructor aborts if WHO_AM_I does not read 0xEA.
 *
 * @param connection   RegisterConnection (I²C or SMBus) pointing at the device
 *                     (address 0x68 with AD0 = GND, 0x69 with AD0 = VDDIO).
 */
class ICM20948Minimal {
public:
    ICM20948Minimal(RegisterConnection& connection);

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
    // Bank 0
    static constexpr uint8_t REG_WHO_AM_I       = 0x00;
    static constexpr uint8_t REG_USER_CTRL      = 0x03;
    static constexpr uint8_t REG_LP_CONFIG      = 0x05;
    static constexpr uint8_t REG_PWR_MGMT_1     = 0x06;
    static constexpr uint8_t REG_PWR_MGMT_2     = 0x07;
    static constexpr uint8_t REG_INT_PIN_CFG    = 0x0F;
    static constexpr uint8_t REG_INT_ENABLE     = 0x10;
    static constexpr uint8_t REG_INT_ENABLE_1   = 0x11;
    static constexpr uint8_t REG_INT_ENABLE_2   = 0x12;
    static constexpr uint8_t REG_I2C_MST_STATUS = 0x17;
    static constexpr uint8_t REG_INT_STATUS     = 0x19;
    static constexpr uint8_t REG_INT_STATUS_1   = 0x1A;
    static constexpr uint8_t REG_ACCEL_XOUT_H   = 0x2D;
    static constexpr uint8_t REG_GYRO_XOUT_H    = 0x33;
    static constexpr uint8_t REG_TEMP_OUT_H     = 0x39;
    static constexpr uint8_t REG_EXT_SLV_SENS_DATA_00 = 0x3B;
    static constexpr uint8_t REG_FIFO_EN_2      = 0x67;
    static constexpr uint8_t REG_FIFO_RST       = 0x68;
    static constexpr uint8_t REG_FIFO_MODE      = 0x69;
    static constexpr uint8_t REG_FIFO_COUNTH    = 0x70;
    static constexpr uint8_t REG_FIFO_R_W       = 0x72;
    static constexpr uint8_t REG_BANK_SEL       = 0x7F;
    // Bank 1
    static constexpr uint8_t REG_XA_OFFS_H      = 0x14;
    static constexpr uint8_t REG_TIMEBASE_CORRECTION_PLL = 0x28;
    // Bank 2
    static constexpr uint8_t REG_GYRO_SMPLRT_DIV    = 0x00;
    static constexpr uint8_t REG_GYRO_CONFIG_1      = 0x01;
    static constexpr uint8_t REG_GYRO_CONFIG_2      = 0x02;
    static constexpr uint8_t REG_XG_OFFS_USRH       = 0x03;
    static constexpr uint8_t REG_ACCEL_SMPLRT_DIV_1 = 0x10;
    static constexpr uint8_t REG_ACCEL_SMPLRT_DIV_2 = 0x11;
    static constexpr uint8_t REG_ACCEL_INTEL_CTRL   = 0x12;
    static constexpr uint8_t REG_ACCEL_WOM_THR      = 0x13;
    static constexpr uint8_t REG_ACCEL_CONFIG       = 0x14;
    static constexpr uint8_t REG_ACCEL_CONFIG_2     = 0x15;
    // Bank 3
    static constexpr uint8_t REG_I2C_MST_CTRL  = 0x01;
    static constexpr uint8_t REG_I2C_SLV0_ADDR = 0x03;
    static constexpr uint8_t REG_I2C_SLV0_REG  = 0x04;
    static constexpr uint8_t REG_I2C_SLV0_CTRL = 0x05;
    static constexpr uint8_t REG_I2C_SLV4_ADDR = 0x13;
    static constexpr uint8_t REG_I2C_SLV4_REG  = 0x14;
    static constexpr uint8_t REG_I2C_SLV4_CTRL = 0x15;
    static constexpr uint8_t REG_I2C_SLV4_DO   = 0x16;
    static constexpr uint8_t REG_I2C_SLV4_DI   = 0x17;

    static constexpr uint8_t WHO_AM_I_VALUE = 0xEA;

    static constexpr float ACCEL_SENSITIVITY[4] = {16384.0f, 8192.0f, 4096.0f, 2048.0f};
    static constexpr float GYRO_SENSITIVITY[4]  = {131.0f, 65.5f, 32.8f, 16.4f};

    RegisterConnection& _connection;
    uint8_t _bank = 0;
    uint8_t _accel_fs = 0;
    uint8_t _gyro_fs = 0;
    uint8_t _accel_dlpf = 3;
    uint8_t _gyro_dlpf = 3;
    bool    _accel_filter = true;
    bool    _gyro_filter = true;
    bool    _lp_en = false;
    bool    _lp_suspended = false;

    void _select_bank(uint8_t bank, bool force = false);
    void _rd(uint8_t bank, uint8_t reg, uint8_t* buf, size_t len);
    void _wr(uint8_t bank, uint8_t reg, const uint8_t* data, size_t len);
    void _wr1(uint8_t bank, uint8_t reg, uint8_t value);
    uint8_t _rd1(uint8_t bank, uint8_t reg);
    void _write_gyro_config();
    void _write_accel_config();
};

/** @brief ICM-20948 full interface — extends ICM20948Minimal with complete functionality.
 *
 * Adds raw and burst reads, temperature, full-scale / filter / sample-rate
 * configuration, sensor power control, low-power modes, gyro and
 * accelerometer offsets, the AK09916 magnetometer (read through the chip's
 * own I²C master — no second connection is needed), single-byte
 * auxiliary-bus access, the 4 KiB FIFO, wake-on-motion, interrupts,
 * self-test and the PLL time-base correction.
 *
 * Methods that touch banks 1–3 select the bank and return to bank 0, so the
 * bank register is 0 between calls.
 *
 * @param connection   RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ICM20948Full : public ICM20948Minimal {
public:
    static constexpr uint8_t SOURCE_DATA_READY     = 0x01;
    static constexpr uint8_t SOURCE_WAKE_ON_MOTION = 0x02;
    static constexpr uint8_t SOURCE_I2C_MASTER     = 0x04;
    static constexpr uint8_t SOURCE_PLL_READY      = 0x08;
    static constexpr uint8_t SOURCE_FIFO_OVERFLOW  = 0x10;

    ICM20948Full(RegisterConnection& connection);

    /** @brief Read raw 3-axis accelerometer values (signed 16-bit counts). */
    void accel_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read raw 3-axis gyroscope values (signed 16-bit counts). */
    void gyro_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Burst-read acceleration, angular rate and temperature in one sample.
     *  @param[out] accel  X, Y, Z acceleration in m/s² (3 floats).
     *  @param[out] gyro   X, Y, Z angular rate in rad/s (3 floats).
     *  @param[out] temp_c Die temperature in °C.
     */
    void read_all(float accel[3], float gyro[3], float& temp_c);

    /** @brief Read die temperature.
     *  @return Temperature in °C.
     */
    float temperature();

    /** @brief Set gyroscope full-scale range.
     *  @param full_scale  0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    void configure_gyro(uint8_t full_scale = 0);

    /** @brief Set accelerometer full-scale range.
     *  @param full_scale  0–3 (0=±2, 1=±4, 2=±8, 3=±16 g).
     */
    void configure_accel(uint8_t full_scale = 0);

    /** @brief Set the digital low-pass filters.
     *  @param gyro_dlpf     GYRO_DLPFCFG 0–7 (3 = 51.2 Hz).
     *  @param accel_dlpf    ACCEL_DLPFCFG 0–7 (3 = 50.4 Hz).
     *  @param gyro_filter   false bypasses the gyro filter (9 kHz output rate).
     *  @param accel_filter  false bypasses the accelerometer filter (4.5 kHz).
     */
    void configure_filters(uint8_t gyro_dlpf = 3, uint8_t accel_dlpf = 3,
                           bool gyro_filter = true, bool accel_filter = true);

    /** @brief Set the sample-rate dividers; ODR = 1125 Hz / (1 + div).
     *  @param gyro_div   GYRO_SMPLRT_DIV 0–255.
     *  @param accel_div  ACCEL_SMPLRT_DIV 0–4095.
     */
    void configure_sample_rate(uint8_t gyro_div = 10, uint16_t accel_div = 10);

    /** @brief Switch the accelerometer and gyroscope on or off (PWR_MGMT_2).
     *  Turning a sensor on again needs its start-up time (accel 20 ms, gyro 35 ms). */
    void set_sensors(bool accel = true, bool gyro = true);

    /** @brief Set or clear the SLEEP bit in PWR_MGMT_1. */
    void set_sleep(bool sleep = true);

    /** @brief Configure duty-cycled low-power operation; LP_EN is set last.
     *  @param accel      Duty-cycle the accelerometer.
     *  @param gyro       Duty-cycle the gyroscope.
     *  @param accel_avg  DEC3_CFG averaging code 0–3.
     *  @param gyro_avg   GYRO_AVGCFG averaging code 0–7.
     */
    void set_low_power(bool accel = false, bool gyro = false,
                       uint8_t accel_avg = 0, uint8_t gyro_avg = 0);

    /** @brief Write the gyroscope offset-cancellation registers (rad/s, 0.0305 dps/LSB). */
    void set_gyro_offset(float x, float y, float z);

    /** @brief Read the 15-bit accelerometer offset codes (0.98 mg/LSB; per-part factory trim). */
    void get_accel_offset(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Write the 15-bit accelerometer offset codes. */
    void set_accel_offset(int16_t x, int16_t y, int16_t z);

    /** @brief Enable the AK09916 magnetometer via the I²C master.
     *  Resets the magnetometer, verifies WIA2 == 0x09, selects continuous
     *  measurement 4 (100 Hz) and configures slave 0 to copy the 9-byte
     *  ST1..ST2 block to EXT_SLV_SENS_DATA_00..08 continuously.
     *  @return false if WIA2 mismatches or an auxiliary transfer fails.
     */
    bool enable_mag();

    /** @brief Read the magnetic field in µT (as the chip reports it).
     *  @return false if the magnetometer is not enabled or HOFL (overflow) is set.
     */
    bool mag(float& x, float& y, float& z);

    /** @brief Read raw magnetometer values (signed 16-bit counts).
     *  @return false if the magnetometer is not enabled.
     */
    bool mag_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Magnetometer DRDY flag (ST1) from the shadowed block; false if not enabled. */
    bool mag_data_ready();

    /** @brief Write the magnetometer CNTL2 mode through slave 4
     *  (0 power-down, 1 single, 2/4/6 continuous 1–3, 8 continuous 4). */
    bool mag_set_mode(uint8_t mode);

    /** @brief Single-byte read from an auxiliary-bus device via slave 4.
     *  @return false on NACK or timeout.
     */
    bool aux_read_byte(uint8_t address, uint8_t reg, uint8_t& value);

    /** @brief Single-byte write to an auxiliary-bus device via slave 4.
     *  @return false on NACK or timeout.
     */
    bool aux_write_byte(uint8_t address, uint8_t reg, uint8_t value);

    /** @brief Check INT_STATUS_1.RAW_DATA_0_RDY_INT. */
    bool data_ready();

    /** @brief Select FIFO sources, enable the FIFO and reset it. */
    void enable_fifo(bool accel = true, bool gyro = true, bool temp = false);

    /** @brief Select FIFO mode: false = stream (overwrite oldest), true = snapshot (stop when full). */
    void set_fifo_mode(bool snapshot = false);

    /** @brief Number of bytes in the FIFO (0–4096). */
    uint16_t fifo_count();

    /** @brief Read bytes from the FIFO (frame layout is not documented in the datasheet).
     *  @param[out] buf  Destination buffer.
     *  @param      len  Maximum number of bytes to read.
     *  @return Number of bytes read (limited by the FIFO count).
     */
    uint16_t read_fifo(uint8_t* buf, uint16_t len);

    /** @brief Reset the FIFO (assert and de-assert FIFO_RST). */
    void reset_fifo();

    /** @brief Configure wake-on-motion (needs the accelerometer on with its filter enabled).
     *  @param threshold_mg  Threshold in mg (4 mg/LSB, 0–1020).
     *  @param enabled       true to arm, false to disarm.
     */
    void set_wake_on_motion(float threshold_mg, bool enabled = true);

    /** @brief Configure the INT1 pin (INT_PIN_CFG). */
    void set_interrupt_pin(bool active_low = false, bool open_drain = false,
                           bool latch = false, bool any_read_clears = false);

    /** @brief Enable one interrupt source (SOURCE_* constant). */
    void enable_interrupt(uint8_t source);

    /** @brief Disable one interrupt source (SOURCE_* constant). */
    void disable_interrupt(uint8_t source);

    /** @brief Read (and clear) INT_STATUS, _1 and _2 and return the combined SOURCE_* bits. */
    uint8_t poll_interrupt();

    /** @brief Subscribe to INT1 assertions on the connection's interrupt pin.
     *  The callback receives the poll_interrupt() status, and is only invoked
     *  for a non-zero status. The edge follows set_interrupt_pin()'s polarity.
     *  Only one ICM20948Full instance can be subscribed at a time.
     */
    void on_interrupt(void (*callback)(uint8_t status));

    /** @brief Unsubscribe from INT1 assertions. */
    void off_interrupt();

    /** @brief Run the self-test enable procedure and return the raw response.
     *  Configures ±2 g / ±250 dps, averages samples with and without the
     *  self-test actuation and restores the configuration. No pass/fail:
     *  the datasheet gives no limits.
     *  @param[out] accel  Raw response (enabled − disabled) per accelerometer axis.
     *  @param[out] gyro   Raw response per gyroscope axis.
     */
    void self_test(float accel[3], float gyro[3]);

    /** @brief PLL time-base correction in percent (0.079 %/LSB, signed). */
    float read_timebase_correction();

    /** @brief Issue DEVICE_RESET and wait; construct a new driver afterwards. */
    void reset();

protected:
    static constexpr uint8_t AK_ADDR = 0x0C;
    static constexpr uint8_t AK_WIA2 = 0x01;
    static constexpr uint8_t AK_ST1  = 0x10;
    static constexpr uint8_t AK_CNTL2 = 0x31;
    static constexpr uint8_t AK_CNTL3 = 0x32;
    static constexpr uint8_t AK_WIA2_VALUE = 0x09;
    static constexpr float   MAG_SENSITIVITY = 0.15f;

    bool _mag_enabled = false;
    bool _int_active_low = false;
    void (*_int_callback)(uint8_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static ICM20948Full* _active_instance;

    void _lp_suspend();
    void _lp_resume();
    bool _slv4_wait();
    bool _mag_block(uint8_t* block);
    void _int_enable(uint8_t source, bool on);
    void _average_raw(float accel[3], float gyro[3]);
    void _handle_edge();
    static void _edge_trampoline();
};
