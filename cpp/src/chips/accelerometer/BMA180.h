#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief BMA180 3-axis MEMS accelerometer — minimal interface.
 *
 *  Triaxial low-g accelerometer with 14-bit digital output and seven
 *  selectable full-scale ranges (±1 to ±16 *g*). Communicates over I²C
 *  at address 0x40 (SDO = GND) or 0x41 (SDO = VDDIO). The chip also
 *  supports 4-wire SPI; out of scope here.
 *
 *  Default configuration (baked in at construction):
 *  - Range ±2 *g* (4096 LSB/g)
 *  - Bandwidth 150 Hz low-pass
 *  - mode_config = 00 (low-noise, factory-calibrated)
 *  - 14-bit readout, shadow_dis = 0
 *  - All interrupt enables left untouched
 *  - Calibration bits preserved everywhere
 *
 *  @param connection Configured RegisterConnection (I²C, SMBus, or SPI)
 *                    pointing at the device (I²C address 0x40 or 0x41).
 */
class BMA180Minimal {
public:
    explicit BMA180Minimal(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration.
     *
     *  Burst-reads the six LSB-then-MSB data bytes (0x02..0x07) so the X,
     *  Y, Z samples are guaranteed to come from a single measurement with
     *  shadow_dis=0 enforcing correct ordering.
     *
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read(float& x, float& y, float& z);

protected:
    // Register map (0x00..0x3A).
    static constexpr uint8_t REG_CHIP_ID          = 0x00;
    static constexpr uint8_t REG_VERSION          = 0x01;
    static constexpr uint8_t REG_ACC_X_LSB        = 0x02;
    static constexpr uint8_t REG_ACC_X_MSB        = 0x03;
    static constexpr uint8_t REG_ACC_Y_LSB        = 0x04;
    static constexpr uint8_t REG_ACC_Y_MSB        = 0x05;
    static constexpr uint8_t REG_ACC_Z_LSB        = 0x06;
    static constexpr uint8_t REG_ACC_Z_MSB        = 0x07;
    static constexpr uint8_t REG_TEMP             = 0x08;
    static constexpr uint8_t REG_STATUS_REG1      = 0x09;
    static constexpr uint8_t REG_STATUS_REG2      = 0x0A;
    static constexpr uint8_t REG_STATUS_REG3      = 0x0B;
    static constexpr uint8_t REG_STATUS_REG4      = 0x0C;
    static constexpr uint8_t REG_CTRL_REG0        = 0x0D;
    static constexpr uint8_t REG_CTRL_REG1        = 0x0E;
    static constexpr uint8_t REG_CTRL_REG2        = 0x0F;
    static constexpr uint8_t REG_RESET            = 0x10;
    static constexpr uint8_t REG_BW_TCS           = 0x20;
    static constexpr uint8_t REG_CTRL_REG3        = 0x21;
    static constexpr uint8_t REG_CTRL_REG4        = 0x22;
    static constexpr uint8_t REG_HY               = 0x23;
    static constexpr uint8_t REG_SLOPE_TAPSENS    = 0x24;
    static constexpr uint8_t REG_HIGH_LOW_INFO    = 0x25;
    static constexpr uint8_t REG_LOW_DUR          = 0x26;
    static constexpr uint8_t REG_HIGH_DUR         = 0x27;
    static constexpr uint8_t REG_TAPSENS_TH       = 0x28;
    static constexpr uint8_t REG_LOW_TH           = 0x29;
    static constexpr uint8_t REG_HIGH_TH          = 0x2A;
    static constexpr uint8_t REG_SLOPE_TH         = 0x2B;
    static constexpr uint8_t REG_CD1              = 0x2C;
    static constexpr uint8_t REG_CD2              = 0x2D;
    static constexpr uint8_t REG_TCO_X            = 0x2E;
    static constexpr uint8_t REG_TCO_Y            = 0x2F;
    static constexpr uint8_t REG_TCO_Z            = 0x30;
    static constexpr uint8_t REG_GAIN_T           = 0x31;
    static constexpr uint8_t REG_GAIN_X           = 0x32;
    static constexpr uint8_t REG_GAIN_Y           = 0x33;
    static constexpr uint8_t REG_GAIN_Z           = 0x34;
    static constexpr uint8_t REG_OFFSET_LSB1      = 0x35;
    static constexpr uint8_t REG_OFFSET_LSB2      = 0x36;
    static constexpr uint8_t REG_OFFSET_T         = 0x37;
    static constexpr uint8_t REG_OFFSET_X         = 0x38;
    static constexpr uint8_t REG_OFFSET_Y         = 0x39;
    static constexpr uint8_t REG_OFFSET_Z         = 0x3A;

    // CHIP_ID register 0x00 bits 2:0 = 0b011 (0x03).
    static constexpr uint8_t CHIP_ID_VALUE = 0x03;
    static constexpr uint8_t CHIP_ID_MASK  = 0x07;

    // CTRL_REG0 bits.
    static constexpr uint8_t CTRL_REG0_EE_W       = 0x10;
    static constexpr uint8_t CTRL_REG0_RESET_INT  = 0x40;
    static constexpr uint8_t CTRL_REG0_UPDATE_IMG = 0x20;
    static constexpr uint8_t CTRL_REG0_ST0          = 0x04;
    static constexpr uint8_t CTRL_REG0_SLEEP      = 0x02;

    // Soft-reset code (write to RESET 0x10).
    static constexpr uint8_t SOFT_RESET_CMD = 0xB6;

    // Range bits in OFFSET_LSB1 (0x35) bits 3:1 — 111 not authorised.
    // Code for the matching range_g.
    static constexpr uint8_t RANGE_1G    = 0x00;
    static constexpr uint8_t RANGE_1_5G  = 0x02;
    static constexpr uint8_t RANGE_2G    = 0x04;
    static constexpr uint8_t RANGE_3G    = 0x06;
    static constexpr uint8_t RANGE_4G    = 0x08;
    static constexpr uint8_t RANGE_8G    = 0x0A;
    static constexpr uint8_t RANGE_16G   = 0x0C;

    // Sensitivity (LSB/g) by range.
    static constexpr float SCALE_1G   = 8192.0f;
    static constexpr float SCALE_1_5G = 5460.0f;
    static constexpr float SCALE_2G   = 4096.0f;
    static constexpr float SCALE_3G   = 2730.0f;
    static constexpr float SCALE_4G   = 2048.0f;
    static constexpr float SCALE_8G   = 1024.0f;
    static constexpr float SCALE_16G  = 512.0f;

    RegisterConnection& _connection;
    float _range_g = 2.0f;
    uint8_t _range_bits = RANGE_2G;

    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, size_t len);
    void    _delay_ms(uint32_t ms);
    static  uint8_t _nearest_bandwidth(uint16_t bandwidth_hz);
};

/** @brief BMA180 full interface — extends BMA180Minimal with configuration,
 *  range/bandwidth selection, mode control, temperature, new-data, shadow,
 *  sample-skip, low-g/high-g/slope/alert/tap interrupts with per-axis
 *  enable and filter selection, latched or self-resetting interrupts,
 *  self-wake-up, sleep, soft reset, electrostatic self-test, offset
 *  regulation, version register, and the two CUSTOMER scratch bytes.
 *
 *  @param connection Configured RegisterConnection (I²C, SMBus, or SPI)
 *                    pointing at the device (I²C address 0x40 or 0x41).
 */
class BMA180Full : public BMA180Minimal {
public:
    // Interrupt source bits.
    static constexpr uint8_t SOURCE_LOW_G    = 0x01;
    static constexpr uint8_t SOURCE_HIGH_G   = 0x02;
    static constexpr uint8_t SOURCE_SLOPE    = 0x04;
    static constexpr uint8_t SOURCE_ALERT    = 0x08;
    static constexpr uint8_t SOURCE_TAP      = 0x10;
    static constexpr uint8_t SOURCE_NEW_DATA = 0x20;

    // STATUS_REG3 latched interrupt flag bits.
    static constexpr uint8_t STATUS_HIGH_G    = 0x80;
    static constexpr uint8_t STATUS_LOW_G     = 0x40;
    static constexpr uint8_t STATUS_SLOPE     = 0x20;
    static constexpr uint8_t STATUS_TAP       = 0x10;
    static constexpr uint8_t STATUS_X_FIRST   = 0x04;
    static constexpr uint8_t STATUS_Y_FIRST   = 0x02;
    static constexpr uint8_t STATUS_Z_FIRST   = 0x01;

    // CTRL_REG3 bits (interrupt enables).
    static constexpr uint8_t CR3_SLOPE_ALERT  = 0x80;
    static constexpr uint8_t CR3_SLOPE_INT    = 0x40;
    static constexpr uint8_t CR3_HIGH_INT     = 0x20;
    static constexpr uint8_t CR3_LOW_INT      = 0x10;
    static constexpr uint8_t CR3_TAP_INT      = 0x08;
    static constexpr uint8_t CR3_ADV_INT      = 0x04;
    static constexpr uint8_t CR3_NEW_DATA_INT = 0x02;
    static constexpr uint8_t CR3_LAT_INT      = 0x01;

    // HIGH_LOW_INFO bits: high axes 7:5, high_filt 4, low axes 3:1, low_filt 0.
    static constexpr uint8_t HLI_HIGH_AXIS_SHIFT = 5;
    static constexpr uint8_t HLI_LOW_AXIS_SHIFT  = 1;
    static constexpr uint8_t HLI_HIGH_FILT_BIT   = 0x10;
    static constexpr uint8_t HLI_LOW_FILT_BIT    = 0x01;

    // SLOPE_TAPSENS_INFO bits: slope axes 7:5, slope_filt 4, tap axes 3:1, tap_filt 0.
    static constexpr uint8_t STI_SLOPE_AXIS_SHIFT = 5;
    static constexpr uint8_t STI_TAP_AXIS_SHIFT   = 1;
    static constexpr uint8_t STI_SLOPE_FILT_BIT   = 0x10;
    static constexpr uint8_t STI_TAP_FILT_BIT     = 0x01;

    // HY register bits: high_hy 7:3, low_hy 2:0.
    static constexpr uint8_t HY_HIGH_SHIFT = 3;
    static constexpr uint8_t HY_LOW_MASK   = 0x07;

    // CTRL_REG4 bits: low_hy<1:0> 7:6, mot_cd_r 5:4, ff_cd_r 3:2, offset_finetuning 1:0.
    static constexpr uint8_t CR4_LOW_HY_SHIFT = 6;
    static constexpr uint8_t CR4_MOT_CD_SHIFT = 4;
    static constexpr uint8_t CR4_FF_CD_SHIFT  = 2;

    // OFFSET_LSB1: offset_x LSBs 7:4, range 3:1, smp_skip 0.
    static constexpr uint8_t OLSB1_RANGE_MASK = 0x0E;
    static constexpr uint8_t OLSB1_SMP_SKIP   = 0x01;

    // GAIN_X: gain_x 7:1, dis_reg 0; GAIN_Y: gain_y 7:1, shadow_dis 0;
    // GAIN_Z: gain_z 7:1, wake_up 0.
    static constexpr uint8_t GY_SHADOW    = 0x01;
    static constexpr uint8_t GZ_WAKE_UP   = 0x01;

    // BW_TCS: bw 7:4, tcs 3:0.
    static constexpr uint8_t BW_HIGH_PASS_1HZ = 0x80;
    static constexpr uint8_t BW_BAND_PASS     = 0x90;

    // TCO_X: tco_x 7:2, slope_dur 1:0; TCO_Y: tco_y 7:2, wake_up_dur 1:0;
    // TCO_Z: tco_z 7:2, mode_config 1:0.
    static constexpr uint8_t TCO_X_SLOPE_MASK = 0x03;
    static constexpr uint8_t TCO_Y_WAKE_MASK  = 0x03;
    static constexpr uint8_t TCO_Z_MODE_MASK  = 0x03;

    // GAIN_T: gain_t 7:3, tapsens_dur 2:0.
    static constexpr uint8_t GT_TAP_MASK = 0x07;

    // LOW_DUR: low_dur 7:1, tco_range 0 (preserve!); HIGH_DUR: high_dur 7:1, dis_i2c 0.
    static constexpr uint8_t LOW_DUR_MASK  = 0xFE;
    static constexpr uint8_t HIGH_DUR_MASK = 0xFE;

    // OFFSET_T: offset_t 7:1, readout_12bit 0.
    static constexpr uint8_t OT_12BIT_MASK = 0x01;

    explicit BMA180Full(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration in *g*. Delegates to BMA180Minimal::read. */
    void read(float& x, float& y, float& z) { BMA180Minimal::read(x, y, z); }

    /** @brief Set the measurement range to ±1/±1.5/±2/±3/±4/±8/±16 *g*. */
    void set_range(float range_g);

    /** @brief Set the low-pass bandwidth to the nearest supported value (10..1200 Hz). */
    void set_bandwidth(uint16_t bandwidth_hz);

    /** @brief Set the filter mode: 0 = low-pass, 1 = high-pass 1 Hz, 2 = band-pass 0.2..300 Hz. */
    void set_filter_mode(uint8_t mode);

    /** @brief Set the noise/power sub-mode (0..3). Offsets shift; recalibrate afterwards. */
    void set_mode(uint8_t mode);

    /** @brief Set the data resolution: 12 or 14 bits. */
    void set_resolution(uint8_t bits);

    /** @brief Read raw 14-bit two's-complement acceleration counts.
     *
     *  @param x Output X counts in [-8192, 8191].
     *  @param y Output Y counts in [-8192, 8191].
     *  @param z Output Z counts in [-8192, 8191].
     */
    void read_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read on-chip temperature.
     *
     *  @return Temperature in °C, computed as 25.0 + (int8(raw) - 2) * 0.5.
     */
    float read_temperature();

    /** @brief Return True if all three new_data_X/Y/Z bits are set. */
    bool new_data_available();

    /** @brief Enable or disable MSB-only reads (shadow_dis = not enabled). */
    void set_shadow(bool enabled);

    /** @brief Toggle the sample-skip bit (only useful with the new-data interrupt). */
    void set_sample_skip(bool enabled);

    /** @brief Configure the low-g (free-fall) interrupt and enable it. */
    void set_low_g(float threshold_g, uint16_t duration_ms,
                   float hysteresis_g = 0.0f,
                   uint8_t axes = 0x07, uint8_t counter = 0, bool filtered = true);

    /** @brief Configure the high-g (shock) interrupt and enable it. */
    void set_high_g(float threshold_g, uint16_t duration_ms,
                    float hysteresis_g = 0.0f,
                    uint8_t axes = 0x07, uint8_t counter = 0, bool filtered = true);

    /** @brief Configure the slope (any-motion) interrupt and enable it (exclusive with alert). */
    void set_slope(float threshold_g, uint8_t samples = 1,
                   uint8_t axes = 0x07, bool filtered = true);

    /** @brief Enable alert mode (mutually exclusive with slope). */
    void set_alert(bool enabled);

    /** @brief Configure the double-tap interrupt and enable it. */
    void set_tap(float threshold_g, uint16_t window_ms = 250,
                 uint8_t axes = 0x07, bool filtered = true);

    /** @brief Enable latched interrupts (cleared by clear_interrupt()). */
    void set_latch(bool enabled);

    /** @brief Clear latched interrupts (writes reset_INT to CTRL_REG0). */
    void clear_interrupt();

    /** @brief Enable one interrupt source (see SOURCE_*). */
    void enable_interrupt(uint8_t source);

    /** @brief Disable one interrupt source. */
    void disable_interrupt(uint8_t source);

    /** @brief Read STATUS_REG3 (latched flags) without clearing them. */
    uint8_t poll_interrupt();

    /** @brief Read all four status registers. */
    void read_status(uint8_t& s1, uint8_t& s2, uint8_t& s3, uint8_t& s4);

    /** @brief Run self-wake-up mode. */
    void set_wake_up(bool enabled, uint16_t pause_ms = 20);

    /** @brief Enter sleep mode. */
    void sleep();

    /** @brief Leave sleep mode. Waits 2 ms for analog to settle. */
    void wake();

    /** @brief Issue a power-on-equivalent reset; waits 30 ms; restores defaults. */
    void soft_reset();

    /** @brief Run the electrostatic self-test. Returns true if every axis gives > 200 LSB. */
    bool self_test();

    /** @brief Run the in-field zero-g offset calibration (volatile). */
    void calibrate_offset(uint8_t axes = 0x07, uint8_t mode = 1);

    /** @brief Read VERSION split into (al_version, ml_version). */
    void read_version(uint8_t& al_version, uint8_t& ml_version);

    /** @brief Read one of the two CUSTOMER scratch bytes. */
    uint8_t read_customer(uint8_t index);

    /** @brief Write one of the two CUSTOMER scratch bytes. */
    void write_customer(uint8_t index, uint8_t value);

private:
    uint8_t _enabled_sources = 0;
    bool    _sleeping = false;

    void _write_threshold(uint8_t reg, float threshold_g);
    void _write_slope_threshold(uint8_t reg, float threshold_g);
    void _write_low_dur(uint16_t duration_ms);
    void _write_high_dur(uint16_t duration_ms);
    void _write_low_hy(float hysteresis_g);
    void _write_high_hy(float hysteresis_g);
    void _write_low_axis_enables(uint8_t axes);
    void _write_high_axis_enables(uint8_t axes);
    void _write_slope_axis_enables(uint8_t axes);
    void _write_tap_axis_enables(uint8_t axes);
    void _write_filt_bit(uint8_t reg, uint8_t bit, bool enabled);
    void _write_debounce(const char* kind, uint8_t counter);
    void _write_slope_dur(uint8_t samples);
    void _write_tap_dur(uint16_t window_ms);
    void _write_cr3_bit(uint8_t bit, bool enabled);
    void _enable_source(uint8_t source);
    void _disable_source(uint8_t source);
    void _read_raw_counts(int16_t& x, int16_t& y, int16_t& z);
    static int16_t _decode_axis(const uint8_t* p);
    static uint8_t _nearest_tap_dur(uint16_t window_ms);
};