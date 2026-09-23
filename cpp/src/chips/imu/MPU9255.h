#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief MPU-9255 9-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the connection. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization. Magnetometer
 * and wake-on-motion are not included in Minimal — both require non-trivial
 * secondary initialization paths.
 *
 * Default configuration (written at construction):
 * - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
 * - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
 * - Gyroscope DLPF: 41 Hz bandwidth (CONFIG DLPF_CFG=3)
 * - Accelerometer DLPF: 44.8 Hz bandwidth (ACCEL_CONFIG2 A_DLPFCFG=3)
 * - Sample rate: 200 Hz (SMPLRT_DIV=4)
 * - Clock: auto PLL (CLKSEL=1)
 * - All six axes enabled
 * - SPI only: I2C_IF_DIS set to prevent accidental I²C re-enable
 *
 * @param connection   Configured I²C or SPI connection pointing at the device.
 */
class MPU9255Minimal {
public:
    MPU9255Minimal(Connection& connection);

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
    static constexpr uint8_t REG_SMPLRT_DIV    = 0x19;
    static constexpr uint8_t REG_CONFIG        = 0x1A;
    static constexpr uint8_t REG_GYRO_CONFIG   = 0x1B;
    static constexpr uint8_t REG_ACCEL_CONFIG  = 0x1C;
    static constexpr uint8_t REG_ACCEL_CONFIG2 = 0x1D;
    static constexpr uint8_t REG_LP_ACCEL_ODR  = 0x1E;
    static constexpr uint8_t REG_WOM_THR       = 0x1F;
    static constexpr uint8_t REG_FIFO_EN       = 0x23;
    static constexpr uint8_t REG_INT_PIN_CFG   = 0x37;
    static constexpr uint8_t REG_INT_ENABLE    = 0x38;
    static constexpr uint8_t REG_INT_STATUS    = 0x3A;
    static constexpr uint8_t REG_ACCEL_XOUT_H  = 0x3B;
    static constexpr uint8_t REG_TEMP_OUT_H    = 0x41;
    static constexpr uint8_t REG_GYRO_XOUT_H   = 0x43;
    static constexpr uint8_t REG_MOT_DETECT_CTRL = 0x69;
    static constexpr uint8_t REG_USER_CTRL     = 0x6A;
    static constexpr uint8_t REG_PWR_MGMT_1    = 0x6B;
    static constexpr uint8_t REG_PWR_MGMT_2    = 0x6C;
    static constexpr uint8_t REG_FIFO_COUNTH   = 0x72;
    static constexpr uint8_t REG_FIFO_COUNTL   = 0x73;
    static constexpr uint8_t REG_FIFO_R_W      = 0x74;
    static constexpr uint8_t REG_WHO_AM_I      = 0x75;

    static constexpr uint8_t WHO_AM_I_VALUE    = 0x73;

    static constexpr float ACCEL_SENSITIVITY[4] = {16384.0f, 8192.0f, 4096.0f, 2048.0f};
    static constexpr float GYRO_SENSITIVITY[4]  = {131.0f, 65.5f, 32.8f, 16.4f};

    Connection& _connection;
    uint8_t    _accel_fs = 0;
    uint8_t    _gyro_fs  = 0;

    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    int16_t _read_reg16_signed(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, uint8_t len);
};

/** @brief MPU-9255 full interface — extends MPU9255Minimal with complete functionality.
 *
 * Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, magnetometer (AK8963) support,
 * wake-on-motion, raw data access, data-ready polling, sleep/standby control,
 * and FIFO management.
 *
 * The AK8963 magnetometer sits behind the MPU-9255's I²C bypass (BYPASS_EN)
 * as its own device at address 0x0C, so it needs its own connection bound
 * to that address on the same bus — it cannot be reached through the
 * connection already bound to the MPU-9255's own address. Construct that
 * second connection the same way as the primary one (e.g. on Linux,
 * I2CConnection(1, 0x0C) alongside I2CConnection(1, 0x68)) and pass both in.
 *
 * @param connection      Configured I²C or SPI connection pointing at the MPU-9255.
 * @param magConnection   Configured I²C connection bound to the AK8963's address (0x0C),
 *                        on the same bus as connection.
 */
class MPU9255Full : public MPU9255Minimal {
public:
    MPU9255Full(Connection& connection, Connection& magConnection);

    /** @brief Set gyroscope full-scale range.
     *  @param full_scale  Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    void configure_gyro(uint8_t full_scale = 0);

    /** @brief Set accelerometer full-scale range.
     *  @param full_scale  Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    void configure_accel(uint8_t full_scale = 0);

    /** @brief Set digital low-pass filter bandwidth.
     *  @param gyro_dlpf   Gyro filter setting 0–7 (0=250 Hz, 1=184 Hz, 2=92 Hz, 3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz, 7=3600 Hz).
     *  @param accel_dlpf  Accel filter setting 0–7 (0=218.1 Hz, 1=218.1 Hz, 2=99 Hz, 3=44.8 Hz, 4=21.2 Hz, 5=10.2 Hz, 6=5.05 Hz, 7=420 Hz).
     */
    void configure_dlpf(uint8_t gyro_dlpf = 3, uint8_t accel_dlpf = 3);

    /** @brief Set sample rate divider.
     *  @param divider  SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider)
     *                  when DLPF is active.
     */
    void configure_sample_rate(uint8_t divider = 4);

    /** @brief Read die temperature.
     *  @return Temperature in °C.
     */
    float temperature();

    /** @brief Initialize AK8963 magnetometer via I²C bypass mode.
     *  @param bits  Output resolution, 14 or 16.
     *  @param mode  Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
     */
    void enable_mag(uint8_t bits = 16, uint8_t mode = 6);

    /** @brief Read 3-axis magnetic field.
     *  @param[out] x  X magnetic field in µT.
     *  @param[out] y  Y magnetic field in µT.
     *  @param[out] z  Z magnetic field in µT.
     *  @note Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
     */
    void mag(float& x, float& y, float& z);

    /** @brief Read raw 3-axis accelerometer values.
     *  @param[out] x  Raw X value (16-bit signed).
     *  @param[out] y  Raw Y value (16-bit signed).
     *  @param[out] z  Raw Z value (16-bit signed).
     */
    void accel_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read raw 3-axis gyroscope values.
     *  @param[out] x  Raw X value (16-bit signed).
     *  @param[out] y  Raw Y value (16-bit signed).
     *  @param[out] z  Raw Z value (16-bit signed).
     */
    void gyro_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read raw 3-axis magnetometer values.
     *  @param[out] x  Raw X value (16-bit signed).
     *  @param[out] y  Raw Y value (16-bit signed).
     *  @param[out] z  Raw Z value (16-bit signed).
     */
    void mag_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Check if new sensor data is available.
     *  @return true when RAW_DATA_RDY_INT is set in INT_STATUS.
     */
    bool data_ready();

    /** @brief Set or clear the SLEEP bit in PWR_MGMT_1.
     *  @param sleep  true to enter sleep mode, false to wake.
     */
    void set_sleep(bool sleep = true);

    /** @brief Read the number of bytes in the FIFO buffer.
     *  @return FIFO byte count (0–512).
     */
    uint16_t fifo_count();

    /** @brief Read all available data from the FIFO buffer.
     *  @param[out] buf  Buffer to receive FIFO data.
     *  @param      len  Maximum bytes to read.
     *  @return Number of bytes actually read.
     */
    uint16_t read_fifo(uint8_t* buf, uint16_t len);

    /** @brief Configure and enable FIFO sources.
     *  @param gyro   Enable gyroscope data in FIFO.
     *  @param accel  Enable accelerometer data in FIFO.
     *  @param temp   Enable temperature data in FIFO.
     */
    void enable_fifo(bool gyro = true, bool accel = true, bool temp = false);

    /** @brief Reset the FIFO buffer by setting FIFO_RST in USER_CTRL. */
    void reset_fifo();

    /** @brief Configure wake-on-motion and enter accelerometer-only low-power mode.
     *
     * Disables the gyroscope, sets the accelerometer DLPF to ~184 Hz, arms the
     * hardware motion-detection logic, and enables CYCLE mode in PWR_MGMT_1
     * so the accelerometer samples at the configured wake-up ODR. Re-enable
     * the gyroscope (via PWR_MGMT_2) and clear CYCLE when motion is detected
     * if full 6-axis data is needed again.
     *
     *  @param threshold_mg  Motion threshold in milligrams (4–1020 mg, quantized
     *                       to 4 mg steps). Values outside this range are clamped.
     *  @param odr_hz        Wake-up output data rate in Hz (0.24–500 Hz). Mapped to
     *                       the LP_ACCEL_ODR register's Lposc_clksel field via the chip's
     *                       standard 16-entry table.
     */
    void configure_wake_on_motion(uint16_t threshold_mg = 64, float odr_hz = 31.25f);

    /** @brief Check if a wake-on-motion interrupt has fired.
     *  @return true when WOM_INT (bit 6) is set in INT_STATUS. Reading INT_STATUS
     *          clears the interrupt.
     */
    bool motion_detected();

protected:
    static constexpr uint8_t AK8963_ADDR = 0x0C;

    static constexpr uint8_t AK8963_REG_WIA      = 0x00;
    static constexpr uint8_t AK8963_REG_ST1      = 0x02;
    static constexpr uint8_t AK8963_REG_HXL      = 0x03;
    static constexpr uint8_t AK8963_REG_ST2      = 0x09;
    static constexpr uint8_t AK8963_REG_CNTL1    = 0x0A;
    static constexpr uint8_t AK8963_REG_CNTL2    = 0x0B;
    static constexpr uint8_t AK8963_REG_ASAX     = 0x10;
    static constexpr uint8_t AK8963_REG_ASAY     = 0x11;
    static constexpr uint8_t AK8963_REG_ASAZ     = 0x12;

    static constexpr uint8_t AK8963_WIA_VALUE = 0x48;

    static constexpr float MAG_SENSITIVITY_14BIT = 0.6f;
    static constexpr float MAG_SENSITIVITY_16BIT = 0.15f;

    Connection& _mag_connection;
    bool   _mag_enabled   = false;
    uint8_t _mag_bits      = 16;
    float  _mag_scale_x    = 1.0f;
    float  _mag_scale_y    = 1.0f;
    float  _mag_scale_z    = 1.0f;

    void _ak8963_write(uint8_t reg, uint8_t value);
    uint8_t _ak8963_read(uint8_t reg);
    void _ak8963_read_burst(uint8_t reg, uint8_t* buf, uint8_t len);
};