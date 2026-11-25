#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief BNO055 9-axis absolute-orientation sensor (Bosch Sensortec) — minimal interface.
 *
 * Waits for the chip to boot (up to 650 ms), checks CHIP_ID (0xA0), selects normal
 * power mode and UNIT_SEL = 0x86 (Android orientation, Euler angles in radians, angular
 * rate in rad/s, acceleration in m/s², temperature in °C), then enters NDOF fusion mode
 * (100 Hz absolute orientation, internal oscillator) and waits 7 ms.
 *
 * I²C address 0x29 (COM3 high, datasheet default) or 0x28 (COM3 low). The chip stretches
 * the I²C clock; the host bus must support clock stretching.
 *
 * @param connection   RegisterConnection (I²C) pointing at the device.
 */
class BNO055Minimal {
public:
    BNO055Minimal(RegisterConnection& connection);

    /** @brief True if CHIP_ID read 0xA0 during construction. */
    bool valid() const { return _valid; }

    /** @brief Read the fused Euler angles (burst read of 0x1A–0x1F, raw / 900).
     *  @param[out] heading  Heading in rad.
     *  @param[out] roll     Roll in rad.
     *  @param[out] pitch    Pitch in rad.
     */
    void euler(float& heading, float& roll, float& pitch);

protected:
    static constexpr uint8_t REG_CHIP_ID         = 0x00;
    static constexpr uint8_t REG_PAGE_ID         = 0x07;
    static constexpr uint8_t REG_ACC_DATA        = 0x08;
    static constexpr uint8_t REG_MAG_DATA        = 0x0E;
    static constexpr uint8_t REG_GYR_DATA        = 0x14;
    static constexpr uint8_t REG_EUL_DATA        = 0x1A;
    static constexpr uint8_t REG_QUA_DATA        = 0x20;
    static constexpr uint8_t REG_LIA_DATA        = 0x28;
    static constexpr uint8_t REG_GRV_DATA        = 0x2E;
    static constexpr uint8_t REG_TEMP            = 0x34;
    static constexpr uint8_t REG_CALIB_STAT      = 0x35;
    static constexpr uint8_t REG_ST_RESULT       = 0x36;
    static constexpr uint8_t REG_INT_STA         = 0x37;
    static constexpr uint8_t REG_SYS_STATUS      = 0x39;
    static constexpr uint8_t REG_UNIT_SEL        = 0x3B;
    static constexpr uint8_t REG_OPR_MODE        = 0x3D;
    static constexpr uint8_t REG_PWR_MODE        = 0x3E;
    static constexpr uint8_t REG_SYS_TRIGGER     = 0x3F;
    static constexpr uint8_t REG_TEMP_SOURCE     = 0x40;
    static constexpr uint8_t REG_AXIS_MAP_CONFIG = 0x41;
    static constexpr uint8_t REG_AXIS_MAP_SIGN   = 0x42;
    static constexpr uint8_t REG_ACC_OFFSET      = 0x55;
    static constexpr uint8_t REG_INT_MSK         = 0x0F;  // page 1
    static constexpr uint8_t REG_INT_EN          = 0x10;  // page 1
    static constexpr uint8_t REG_UNIQUE_ID       = 0x50;  // page 1

    static constexpr uint8_t CHIP_ID_VALUE = 0xA0;
    static constexpr uint8_t UNIT_SEL_SI   = 0x86;
    static constexpr uint8_t MODE_CONFIG_V = 0x00;
    static constexpr uint8_t MODE_NDOF_V   = 0x0C;

    RegisterConnection& _connection;
    uint8_t _mode = MODE_NDOF_V;
    bool    _valid = false;

    bool _initialize();
    void _w(uint8_t reg, uint8_t value);
    uint8_t _r(uint8_t reg);
    void _vector(uint8_t reg, float scale, float& x, float& y, float& z);
};

/** @brief BNO055 full interface — all outputs, calibration, modes and interrupts.
 *
 * Extends BNO055Minimal with the quaternion, linear acceleration, gravity, raw sensor
 * data, calibration read/write, operation/power mode control, axis remap, self-test,
 * identification and the page-1 interrupt registers.
 *
 * @param connection   RegisterConnection (I²C) pointing at the device.
 */
class BNO055Full : public BNO055Minimal {
public:
    static constexpr uint8_t MODE_CONFIG = 0x0;
    static constexpr uint8_t MODE_ACCONLY = 0x1;
    static constexpr uint8_t MODE_MAGONLY = 0x2;
    static constexpr uint8_t MODE_GYROONLY = 0x3;
    static constexpr uint8_t MODE_ACCMAG = 0x4;
    static constexpr uint8_t MODE_ACCGYRO = 0x5;
    static constexpr uint8_t MODE_MAGGYRO = 0x6;
    static constexpr uint8_t MODE_AMG = 0x7;
    static constexpr uint8_t MODE_IMU = 0x8;
    static constexpr uint8_t MODE_COMPASS = 0x9;
    static constexpr uint8_t MODE_M4G = 0xA;
    static constexpr uint8_t MODE_NDOF_FMC_OFF = 0xB;
    static constexpr uint8_t MODE_NDOF = 0xC;

    static constexpr uint8_t POWER_NORMAL = 0;
    static constexpr uint8_t POWER_LOW_POWER = 1;
    static constexpr uint8_t POWER_SUSPEND = 2;

    static constexpr uint8_t TEMP_SOURCE_ACCELEROMETER = 0;
    static constexpr uint8_t TEMP_SOURCE_GYROSCOPE = 1;

    static constexpr uint8_t SOURCE_ACC_BSX_DRDY = 0x01;
    static constexpr uint8_t SOURCE_MAG_DRDY = 0x02;
    static constexpr uint8_t SOURCE_GYRO_AM = 0x04;
    static constexpr uint8_t SOURCE_GYR_HIGH_RATE = 0x08;
    static constexpr uint8_t SOURCE_GYR_DRDY = 0x10;
    static constexpr uint8_t SOURCE_ACC_HIGH_G = 0x20;
    static constexpr uint8_t SOURCE_ACC_AM = 0x40;
    static constexpr uint8_t SOURCE_ACC_NM = 0x80;

    BNO055Full(RegisterConnection& connection);

    /** @brief Select the operation mode through CONFIGMODE (19 ms + 7 ms waits).
     *  @param mode  0 (CONFIGMODE) … 12 (NDOF); values above 12 are ignored. */
    void set_mode(uint8_t mode);

    /** @brief Read OPR_MODE (0–12). */
    uint8_t get_mode();

    /** @brief Read the orientation quaternion (raw / 16384). */
    void quaternion(float& w, float& x, float& y, float& z);

    /** @brief Read the gravity-free linear acceleration in m/s² (raw / 100). */
    void linear_acceleration(float& x, float& y, float& z);

    /** @brief Read the gravity vector in m/s² (raw / 100). */
    void gravity(float& x, float& y, float& z);

    /** @brief Read the raw accelerometer in m/s² (raw / 100). */
    void acceleration(float& x, float& y, float& z);

    /** @brief Read the raw magnetometer in µT (raw / 16). */
    void magnetic_field(float& x, float& y, float& z);

    /** @brief Read the raw gyroscope in rad/s (raw / 900). */
    void angular_rate(float& x, float& y, float& z);

    /** @brief Read the temperature in °C (signed 8 bit, 1 LSB = 1 °C). */
    int8_t temperature();

    /** @brief Read CALIB_STAT: each value 0 (uncalibrated) … 3 (fully calibrated). */
    void calibration_status(uint8_t& sys, uint8_t& gyr, uint8_t& acc, uint8_t& mag);

    /** @brief True if sys, gyr, acc and mag are all 3. */
    bool is_calibrated();

    /** @brief Read the 22 offset/radius bytes 0x55–0x6A in CONFIGMODE (mode restored). */
    void get_calibration(uint8_t out[22]);

    /** @brief Write the 22 offset/radius bytes in CONFIGMODE (mode restored). */
    void set_calibration(const uint8_t data[22]);

    /** @brief Write AXIS_MAP_CONFIG / AXIS_MAP_SIGN in CONFIGMODE (mode restored). */
    void set_axis_remap(uint8_t config, uint8_t sign);

    /** @brief Select the power mode (POWER_NORMAL, POWER_LOW_POWER, POWER_SUSPEND) in CONFIGMODE. */
    void set_power_mode(uint8_t mode);

    /** @brief Select the temperature source (TEMP_SOURCE_ACCELEROMETER / TEMP_SOURCE_GYROSCOPE). */
    void set_temperature_source(uint8_t source);

    /** @brief Select the external 32.768 kHz crystal (only if fitted) or the internal oscillator. */
    void use_external_crystal(bool on);

    /** @brief Run the self-test (CONFIGMODE, mode restored); each flag is true if passed. */
    void self_test(bool& acc, bool& mag, bool& gyr, bool& mcu);

    /** @brief Read SYS_STATUS (5 = fusion running) and SYS_ERR (0 = none). */
    void system_status(uint8_t& status, uint8_t& error);

    /** @brief Reset the chip (RST_SYS), wait 650 ms and re-initialise into NDOF. */
    void reset();

    /** @brief Read the identification and revision registers 0x00–0x06. */
    void revisions(uint8_t& chip_id, uint8_t& acc_id, uint8_t& mag_id, uint8_t& gyr_id,
                   uint16_t& sw_revision, uint8_t& bootloader);

    /** @brief Read the 16-byte unique ID from page 1 (page 0 restored). */
    void unique_id(uint8_t out[16]);

    /** @brief Enable interrupt source bits in INT_EN and INT_MSK (page 1, page 0 restored). */
    void enable_interrupt(uint8_t source);

    /** @brief Disable interrupt source bits in INT_EN and INT_MSK. */
    void disable_interrupt(uint8_t source);

    /** @brief Read INT_STA (cleared on read) as SOURCE_* bits. */
    uint8_t poll_interrupt();

    /** @brief Subscribe to rising edges on the connection's INT pin; the callback gets the INT_STA bits.
     *  Without an INT pin nothing is registered: call poll_interrupt() from the main loop instead. */
    void on_interrupt(void (*callback)(uint8_t status));

    /** @brief Unsubscribe from INT pin edges. */
    void off_interrupt();

protected:
    uint8_t _clk_sel = 0x00;
    void (*_int_callback)(uint8_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static BNO055Full* _active_instance;

    uint8_t _enter_config();
    void _leave_config(uint8_t mode);
    void _update_int_reg(uint8_t source, bool enable);
    void _handle_edge();
    static void _edge_trampoline();
};
