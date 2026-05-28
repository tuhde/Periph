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
