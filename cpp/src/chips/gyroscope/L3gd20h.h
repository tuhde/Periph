#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief L3GD20H (and L3GD20) three-axis MEMS gyroscope — minimal interface.
 *
 *  Provides angular rate readings on the X, Y, and Z axes with no
 *  configuration beyond the connection. I²C address is 0x6A (SA0/SDO=GND) or
 *  0x6B (SA0/SDO=VCC). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 *  Default configuration (baked in at construction):
 *      - 95 Hz ODR, default bandwidth (DR=00, BW=00)
 *      - ±250 dps full scale (sensitivity 8.75 mdps/digit)
 *      - BDU=1 (block data update — hold registers until MSB+LSB read)
 *      - All axes enabled, normal power mode
 *      - 250 ms startup delay for gyroscope stabilization
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi        Pass true for SPI bus.
 */
class L3gd20hMinimal {
public:
    explicit L3gd20hMinimal(Connection& connection, bool spi = false);

    /** @brief Read angular rate on all three axes as a single burst transaction.
     *
     *  Burst-reads OUT_X_L through OUT_Z_H (registers 0x28–0x2D, 6 bytes,
     *  little-endian), unpacks the three signed 16-bit values, and converts
     *  them to rad/s using the current full-scale sensitivity.
     *
     *  @param x_rad_s Output X-axis angular rate (rad/s).
     *  @param y_rad_s Output Y-axis angular rate (rad/s).
     *  @param z_rad_s Output Z-axis angular rate (rad/s).
     */
    void gyro(float& x_rad_s, float& y_rad_s, float& z_rad_s);

protected:
    static constexpr uint8_t REG_WHO_AM_I      = 0x0F;
    static constexpr uint8_t REG_CTRL_REG1     = 0x20;
    static constexpr uint8_t REG_CTRL_REG2     = 0x21;
    static constexpr uint8_t REG_CTRL_REG3     = 0x22;
    static constexpr uint8_t REG_CTRL_REG4     = 0x23;
    static constexpr uint8_t REG_CTRL_REG5     = 0x24;
    static constexpr uint8_t REG_REFERENCE     = 0x25;
    static constexpr uint8_t REG_OUT_TEMP      = 0x26;
    static constexpr uint8_t REG_STATUS        = 0x27;
    static constexpr uint8_t REG_OUT_X_L       = 0x28;
    static constexpr uint8_t REG_OUT_X_H       = 0x29;
    static constexpr uint8_t REG_OUT_Y_L       = 0x2A;
    static constexpr uint8_t REG_OUT_Y_H       = 0x2B;
    static constexpr uint8_t REG_OUT_Z_L       = 0x2C;
    static constexpr uint8_t REG_OUT_Z_H       = 0x2D;
    static constexpr uint8_t REG_FIFO_CTRL     = 0x2E;
    static constexpr uint8_t REG_FIFO_SRC      = 0x2F;
    static constexpr uint8_t REG_INT1_CFG      = 0x30;
    static constexpr uint8_t REG_INT1_SRC      = 0x31;
    static constexpr uint8_t REG_INT1_TSH_XH   = 0x32;
    static constexpr uint8_t REG_INT1_TSH_XL   = 0x33;
    static constexpr uint8_t REG_INT1_TSH_YH   = 0x34;
    static constexpr uint8_t REG_INT1_TSH_YL   = 0x35;
    static constexpr uint8_t REG_INT1_TSH_ZH   = 0x36;
    static constexpr uint8_t REG_INT1_TSH_ZL   = 0x37;
    static constexpr uint8_t REG_INT1_DURATION = 0x38;

    static constexpr uint8_t WHO_AM_I_L3GD20  = 0xD4;
    static constexpr uint8_t WHO_AM_I_L3GD20H = 0xD7;

    /** CTRL_REG1 default: DR=00 (95 Hz), BW=00, PD=1, Zen=Yen=Xen=1. */
    static constexpr uint8_t CTRL_REG1_DEFAULT = 0x0F;
    /** CTRL_REG4 default: BDU=1, FS=00 (±250 dps), BLE=0, SIM=0. */
    static constexpr uint8_t CTRL_REG4_DEFAULT = 0x80;

    Connection& _connection;
    bool        _spi;
    uint16_t    _full_scale;  // dps: 250, 500, or 2000

    void _write_reg(uint8_t reg, uint8_t value);
    void _read_reg(uint8_t reg, uint8_t* buf, uint8_t len);
    float _sensitivity() const;
    static int16_t _int16_le(const uint8_t* data);
};

/** @brief L3GD20H full interface — extends L3gd20hMinimal with full configuration,
 *  FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.
 *
 *  Adds ODR/bandwidth/full-scale configuration, FIFO with all five modes
 *  and watermark, high-pass filter with selectable cutoff, per-axis
 *  interrupt generation with threshold and duration, INT1 pin routing,
 *  and access to temperature and status registers.
 */
class L3gd20hFull : public L3gd20hMinimal {
public:
    /** Output data rate selection (DR[1:0] in CTRL_REG1). */
    static constexpr uint8_t ODR_95_HZ  = 0;
    static constexpr uint8_t ODR_190_HZ = 1;
    static constexpr uint8_t ODR_380_HZ = 2;
    static constexpr uint8_t ODR_760_HZ = 3;

    /** Full-scale range (FS[1:0] in CTRL_REG4). */
    static constexpr uint16_t FS_250_DPS  = 250;
    static constexpr uint16_t FS_500_DPS  = 500;
    static constexpr uint16_t FS_2000_DPS = 2000;

    /** FIFO mode (FM[2:0] in FIFO_CTRL_REG). */
    static constexpr uint8_t FIFO_BYPASS             = 0;
    static constexpr uint8_t FIFO_FIFO               = 1;
    static constexpr uint8_t FIFO_STREAM             = 2;
    static constexpr uint8_t FIFO_BYPASS_TO_STREAM   = 3;
    static constexpr uint8_t FIFO_STREAM_TO_FIFO     = 7;

    /** HPF mode (HPM[1:0] in CTRL_REG2). */
    static constexpr uint8_t HPM_NORMAL      = 0;
    static constexpr uint8_t HPM_REFERENCE   = 1;
    static constexpr uint8_t HPM_NORMAL_ALT  = 2;
    static constexpr uint8_t HPM_AUTORESET   = 3;

    /** Power mode strings for set_power_mode(). */
    static constexpr const char* POWER_NORMAL     = "normal";
    static constexpr const char* POWER_SLEEP      = "sleep";
    static constexpr const char* POWER_POWERDOWN  = "power_down";

    explicit L3gd20hFull(Connection& connection, bool spi = false);

    /** @brief Configure ODR, bandwidth, and full scale in one call.
     *
     *  @param odr         Output data rate code 0-3 (95/190/380/760 Hz).
     *  @param bw          Bandwidth selection code 0-3 (ODR-dependent; see datasheet Table 21).
     *  @param full_scale  Full-scale code 0=±250, 1=±500, 2=±2000 dps.
     */
    void configure(uint8_t odr, uint8_t bw, uint8_t full_scale);

    /** @brief Read raw 16-bit signed angular rate values.
     *  @param x_raw Output X-axis raw value.
     *  @param y_raw Output Y-axis raw value.
     *  @param z_raw Output Z-axis raw value.
     */
    void gyro_raw(int16_t& x_raw, int16_t& y_raw, int16_t& z_raw);

    /** @brief Read the relative temperature count.
     *
     *  OUT_TEMP is an 8-bit signed value with 1 LSB/°C sensitivity. There is
     *  no absolute calibration — it represents change from the device's
     *  power-on temperature baseline. Do not convert to absolute Celsius.
     *
     *  @return Signed 8-bit temperature count.
     */
    int8_t temperature();

    /** @brief Check whether a new X/Y/Z sample is ready.
     *  @return True if STATUS_REG.ZYXDA (bit 3) is set.
     */
    bool data_ready();

    /** @brief Configure the high-pass filter (CTRL_REG2).
     *
     *  @param mode   HPF mode 0-3 (HPM[1:0] in CTRL_REG2).
     *  @param cutoff HPF cutoff code 0-15 (HPCF[3:0] in CTRL_REG2; actual
     *                cutoff depends on ODR — see datasheet Table 21).
     */
    void configure_hp_filter(uint8_t mode, uint8_t cutoff);

    /** @brief Enable or disable the high-pass filter on the output path.
     *  @param enable True to enable (sets HPen in CTRL_REG5), False to disable.
     */
    void enable_hp_filter(bool enable);

    /** @brief Configure the FIFO (FIFO_CTRL_REG).
     *
     *  @param mode      FIFO mode 0=Bypass, 1=FIFO, 2=Stream, 3=Bypass-to-Stream,
     *                   7=Stream-to-FIFO.
     *  @param watermark Watermark threshold 0-31 (WTM[4:0]).
     */
    void configure_fifo(uint8_t mode, uint8_t watermark);

    /** @brief Enable or disable the FIFO (FIFO_EN bit in CTRL_REG5).
     *  @param enable True to enable FIFO, False to disable and clear to bypass.
     */
    void enable_fifo(bool enable);

    /** @brief Read number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).
     *  @return Number of stored samples (0-31).
     */
    uint8_t fifo_level();

    /** @brief Read all available FIFO samples and return as rad/s tuples.
     *
     *  Each burst read of OUT_X_L through OUT_Z_H pops the oldest entry.
     *  Reads FIFO_SRC_REG to determine sample count, then burst-reads all.
     *
     *  @param out_x  Output array for X-axis rad/s values (size >= fifo_level()).
     *  @param out_y  Output array for Y-axis rad/s values.
     *  @param out_z  Output array for Z-axis rad/s values.
     *  @param max_samples Maximum number of samples to read (size of output arrays).
     *  @return Number of samples actually read.
     */
    uint8_t read_fifo(float* out_x, float* out_y, float* out_z, uint8_t max_samples);

    /** @brief Set the power mode (CTRL_REG1 PD and axis enable bits).
     *  @param mode "normal" (PD=1, all axes on), "sleep" (PD=1, all axes off),
     *              or "power_down" (PD=0).
     */
    void set_power_mode(const char* mode);

protected:
    uint8_t _odr;
    uint8_t _bw;
    uint16_t _threshold_raw;
};