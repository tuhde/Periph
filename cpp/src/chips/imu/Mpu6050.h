#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief MPU-6050 6-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the transport. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization.
 *
 * Default configuration (written at construction):
 * - Gyroscope full-scale: ±250 dps (FS_SEL=0)
 * - Accelerometer full-scale: ±2 g (AFS_SEL=0)
 * - DLPF: 44 Hz bandwidth (CONFIG DLPF_CFG=3, 1 kHz gyro rate)
 * - Sample rate: 200 Hz (SMPLRT_DIV=4)
 * - Clock: PLL with gyro X reference (CLKSEL=1)
 *
 * @param transport   Configured I²C transport pointing at the device.
 */
class MPU6050Minimal {
public:
    MPU6050Minimal(Transport& transport);

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
    static constexpr uint8_t REG_SMPLRT_DIV   = 0x19;
    static constexpr uint8_t REG_CONFIG       = 0x1A;
    static constexpr uint8_t REG_GYRO_CONFIG  = 0x1B;
    static constexpr uint8_t REG_ACCEL_CONFIG = 0x1C;
    static constexpr uint8_t REG_FIFO_EN      = 0x23;
    static constexpr uint8_t REG_INT_PIN_CFG  = 0x37;
    static constexpr uint8_t REG_INT_ENABLE   = 0x38;
    static constexpr uint8_t REG_INT_STATUS   = 0x3A;
    static constexpr uint8_t REG_ACCEL_XOUT_H = 0x3B;
    static constexpr uint8_t REG_TEMP_OUT_H   = 0x41;
    static constexpr uint8_t REG_GYRO_XOUT_H  = 0x43;
    static constexpr uint8_t REG_USER_CTRL    = 0x6A;
    static constexpr uint8_t REG_PWR_MGMT_1   = 0x6B;
    static constexpr uint8_t REG_PWR_MGMT_2   = 0x6C;
    static constexpr uint8_t REG_FIFO_COUNTH  = 0x72;
    static constexpr uint8_t REG_FIFO_COUNTL  = 0x73;
    static constexpr uint8_t REG_FIFO_R_W     = 0x74;
    static constexpr uint8_t REG_WHO_AM_I     = 0x75;

    static constexpr uint8_t WHO_AM_I_VALUE   = 0x68;

    static constexpr float ACCEL_SENSITIVITY[4] = {16384.0f, 8192.0f, 4096.0f, 2048.0f};
    static constexpr float GYRO_SENSITIVITY[4]  = {131.0f, 65.5f, 32.8f, 16.4f};

    Transport& _transport;
    uint8_t    _accel_fs = 0;
    uint8_t    _gyro_fs  = 0;

    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    int16_t _read_reg16_signed(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, uint8_t len);
};

/** @brief MPU-6050 full interface — extends MPU6050Minimal with configuration and FIFO support.
 *
 * Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, raw data access, data-ready polling,
 * sleep/standby control, and FIFO management.
 *
 * @param transport   Configured I²C transport pointing at the device.
 */
class MPU6050Full : public MPU6050Minimal {
public:
    MPU6050Full(Transport& transport);

    /** @brief Set gyroscope full-scale range.
     *  @param full_scale  Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    void configure_gyro(uint8_t full_scale = 0);

    /** @brief Set accelerometer full-scale range.
     *  @param full_scale  Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    void configure_accel(uint8_t full_scale = 0);

    /** @brief Set digital low-pass filter bandwidth.
     *  @param dlpf  Filter setting 0–6 (0=260/256 Hz, 1=184/188 Hz, 2=94/98 Hz,
     *               3=44/42 Hz, 4=21/20 Hz, 5=10/10 Hz, 6=5/5 Hz; gyro/accel BW).
     */
    void configure_dlpf(uint8_t dlpf = 3);

    /** @brief Set sample rate divider.
     *  @param divider  SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider)
     *                  when DLPF is active.
     */
    void configure_sample_rate(uint8_t divider = 4);

    /** @brief Read die temperature.
     *  @return Temperature in °C.
     */
    float temperature();

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

    /** @brief Check if new sensor data is available.
     *  @return true when DATA_RDY_INT is set in INT_STATUS.
     */
    bool data_ready();

    /** @brief Set or clear the SLEEP bit in PWR_MGMT_1.
     *  @param sleep  true to enter sleep mode, false to wake.
     */
    void set_sleep(bool sleep = true);

    /** @brief Put individual axes into standby mode.
     *  @param xa  X accelerometer standby.
     *  @param ya  Y accelerometer standby.
     *  @param za  Z accelerometer standby.
     *  @param xg  X gyroscope standby.
     *  @param yg  Y gyroscope standby.
     *  @param zg  Z gyroscope standby.
     */
    void set_standby(bool xa = false, bool ya = false, bool za = false,
                     bool xg = false, bool yg = false, bool zg = false);

    /** @brief Read the number of bytes in the FIFO buffer.
     *  @return FIFO byte count (0–1024).
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

    /** @brief Read the WHO_AM_I register.
     *  @return Device identity; expect 0x68.
     */
    uint8_t who_am_i();
};
