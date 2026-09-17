#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief L3G4200D three-axis MEMS gyroscope — minimal interface.
 *
 *  Provides angular rate readings on the X, Y, and Z axes with no
 *  configuration beyond the connection. I²C address is 0x68 (SA0=GND) or
 *  0x69 (SA0=VDD). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 *  Default configuration (baked in at construction):
 *      - 100 Hz ODR, 12.5 Hz LPF2 cutoff (DR=00, BW=00)
 *      - ±250 dps full scale
 *      - BDU=1 (block data update — hold registers until MSB+LSB read)
 *      - All axes enabled, normal power mode
 *      - FIFO disabled (bypass)
 *      - HPF disabled
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi        Pass true for SPI bus.
 */
class L3G4200DMinimal {
public:
    explicit L3G4200DMinimal(Connection& connection, bool spi = false);

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
    void angular_rate(float& x_rad_s, float& y_rad_s, float& z_rad_s);

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
    static constexpr uint8_t REG_INT1_THS_XH   = 0x32;
    static constexpr uint8_t REG_INT1_THS_XL   = 0x33;
    static constexpr uint8_t REG_INT1_THS_YH   = 0x34;
    static constexpr uint8_t REG_INT1_THS_YL   = 0x35;
    static constexpr uint8_t REG_INT1_THS_ZH   = 0x36;
    static constexpr uint8_t REG_INT1_THS_ZL   = 0x37;
    static constexpr uint8_t REG_INT1_DURATION = 0x38;

    static constexpr uint8_t WHO_AM_I_EXPECTED = 0xD3;

    /** CTRL_REG1 default: DR=00 (100 Hz), BW=00 (12.5 Hz), PD=1, Zen=Yen=Xen=1. */
    static constexpr uint8_t CTRL_REG1_DEFAULT = 0x0F;
    /** CTRL_REG4 default: BDU=1, FS=00 (±250 dps), 4-wire SPI, LSB at lower address. */
    static constexpr uint8_t CTRL_REG4_DEFAULT = 0x80;

    Connection& _connection;
    bool        _spi;
    uint16_t    _full_scale;  // dps: 250, 500, or 2000

    void _write_reg(uint8_t reg, uint8_t value);
    void _read_reg(uint8_t reg, uint8_t* buf, uint8_t len);
    float _sensitivity() const;
    static int16_t _int16_le(const uint8_t* data);
};

/** @brief L3G4200D full interface — extends L3G4200DMinimal with full configuration,
 *  FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.
 *
 *  Adds ODR/bandwidth/full-scale configuration, FIFO with all five modes
 *  and watermark, high-pass filter with selectable cutoff, per-axis
 *  interrupt generation with threshold and duration, INT1/DRDY pin routing,
 *  and access to temperature and status registers.
 */
class L3G4200DFull : public L3G4200DMinimal {
public:
    /** Output data rate selection (DR[1:0] in CTRL_REG1). */
    static constexpr uint8_t ODR_100_HZ = 0;
    static constexpr uint8_t ODR_200_HZ = 1;
    static constexpr uint8_t ODR_400_HZ = 2;
    static constexpr uint8_t ODR_800_HZ = 3;

    /** Full-scale range. */
    static constexpr uint16_t FS_250_DPS  = 250;
    static constexpr uint16_t FS_500_DPS  = 500;
    static constexpr uint16_t FS_2000_DPS = 2000;

    /** FIFO mode (FM[2:0] in FIFO_CTRL_REG). */
    static constexpr uint8_t FIFO_BYPASS            = 0;
    static constexpr uint8_t FIFO_FIFO              = 1;
    static constexpr uint8_t FIFO_STREAM            = 2;
    static constexpr uint8_t FIFO_STREAM_TO_FIFO    = 3;
    static constexpr uint8_t FIFO_BYPASS_TO_STREAM  = 4;

    /** HPF mode (HPM[1:0] in CTRL_REG2). */
    static constexpr uint8_t HPM_NORMAL     = 0;
    static constexpr uint8_t HPM_REFERENCE  = 1;
    static constexpr uint8_t HPM_NORMAL_ALT = 2;
    static constexpr uint8_t HPM_AUTORESET  = 3;

    explicit L3G4200DFull(Connection& connection, bool spi = false);

    /** @brief Configure ODR, LPF2 bandwidth, and full scale in one call.
     *
     *  @param odr          Output data rate code (0=100, 1=200, 2=400, 3=800 Hz).
     *  @param bandwidth    LPF2 bandwidth code 0-3 (depends on ODR).
     *  @param full_scale   Full-scale range in dps (250, 500, or 2000).
     */
    void configure(uint8_t odr, uint8_t bandwidth, uint16_t full_scale);

    /** @brief Update the full-scale range.
     *  @param full_scale 250, 500, or 2000 dps. */
    void set_full_scale(uint16_t full_scale);

    /** @brief Read WHO_AM_I (0x0F).
     *  @return 0xD3 for a genuine L3G4200D. */
    uint8_t who_am_i();

    /** @brief Read STATUS_REG (0x27) raw byte.
     *
     *  Bit fields: ZYXOR[7], ZOR[6], YOR[5], XOR[4], ZYXDA[3], ZDA[2],
     *  YDA[1], XDA[0].
     *
     *  @return Raw status byte.
     */
    uint8_t status();

    /** @brief Check whether a new X/Y/Z sample is ready.
     *  @return True if STATUS_REG.ZYXDA (bit 3) is set. */
    bool data_ready();

    /** @brief Read the relative temperature count.
     *
     *  OUT_TEMP is an 8-bit signed value with a -1 °C/digit scale; there is
     *  no absolute calibration — useful only for tracking drift.
     *
     *  @return Signed 8-bit temperature count.
     */
    int8_t temperature();

    /** @brief Enter power-down mode (PD=0 in CTRL_REG1). */
    void power_down();

    /** @brief Wake from power-down (PD=1); previously enabled axes restored. */
    void wake_up();

    /** @brief Enter sleep mode (PD=1, all axes disabled). */
    void sleep();

    /** @brief Enable or disable individual axes (Xen/Yen/Zen in CTRL_REG1).
     *
     *  @param x Enable X axis.
     *  @param y Enable Y axis.
     *  @param z Enable Z axis.
     */
    void enable_axes(bool x, bool y, bool z);

    /** @brief Configure and enable the FIFO.
     *
     *  @param mode      FIFO mode 0-4 (0=bypass/disable, 1=FIFO, 2=stream,
     *                   3=stream-to-FIFO, 4=bypass-to-stream).
     *  @param watermark Watermark threshold 0-31 (number of stored samples).
     */
    void enable_fifo(uint8_t mode, uint8_t watermark);

    /** @brief Disable the FIFO (clear FIFO_EN and put it in bypass mode). */
    void disable_fifo();

    /** @brief Read FSS[4:0] from FIFO_SRC_REG.
     *  @return Number of stored samples in the FIFO (0-31). */
    uint8_t fifo_samples();

    /** @brief Enable the high-pass filter on the output path.
     *
     *  @param mode   HPF mode 0-3 (HPM field in CTRL_REG2).
     *  @param cutoff HPF cutoff code 0-9 (HPCF field in CTRL_REG2; actual
     *               cutoff depends on ODR — see datasheet Table 27).
     */
    void enable_highpass(uint8_t mode, uint8_t cutoff);

    /** @brief Clear HPen in CTRL_REG5. */
    void disable_highpass();

    /** @brief Configure INT1_CFG — which axis/direction events fire INT1.
     *
     *  @param x_high   Enable X high-event interrupt.
     *  @param x_low    Enable X low-event interrupt.
     *  @param y_high   Enable Y high-event interrupt.
     *  @param y_low    Enable Y low-event interrupt.
     *  @param z_high   Enable Z high-event interrupt.
     *  @param z_low    Enable Z low-event interrupt.
     *  @param and_mode AND combination of events (false=OR).
     *  @param latch    Latch interrupt request until INT1_SRC is read.
     */
    void set_interrupt(bool x_high, bool x_low,
                       bool y_high, bool y_low,
                       bool z_high, bool z_low,
                       bool and_mode, bool latch);

    /** @brief Set the interrupt threshold for one axis.
     *
     *  @param axis           'x', 'y', or 'z'.
     *  @param threshold_dps  Threshold in dps. The 15-bit raw value is
     *                        int(threshold_dps / sensitivity).
     */
    void set_threshold(char axis, float threshold_dps);

    /** @brief Set INT1_DURATION.
     *
     *  @param samples Duration 0-127 (number of ODR cycles the condition must
     *                 be true before INT1 fires).
     *  @param wait    If true, INT1 stays asserted until INT1_SRC is read;
     *                 if false, INT1 deasserts as soon as the condition
     *                 stops being true.
     */
    void set_duration(uint8_t samples, bool wait);

    /** @brief Read INT1_SRC; reading clears the interrupt-active bit.
     *  @return Raw INT1_SRC byte. */
    uint8_t read_int_source();

    /** @brief Route the data-ready signal to the DRDY/INT2 pin.
     *  @param enable True to enable, False to disable. */
    void set_data_ready_pin(bool enable);

protected:
    uint8_t _odr;
    uint8_t _bw;
};
