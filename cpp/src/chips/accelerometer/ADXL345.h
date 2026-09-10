#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief ADXL345 3-axis MEMS accelerometer — minimal interface.
 *
 *  Reads X, Y, Z acceleration in *g* with sensible defaults; no
 *  configuration is required beyond the connection. I²C address is 0x53
 *  (SDO=GND) or 0x1D (SDO=VDDIO); SPI mode is selected via @p spi and uses
 *  CPOL=1/CPHA=1 (mode 3) with a 6-bit register address prefix.
 *
 *  Default configuration (baked in at construction):
 *  - Full-resolution mode (3.9 mg/LSB at any range)
 *  - ±2 g measurement range
 *  - 100 Hz output data rate, normal power
 *  - FIFO bypass, all interrupts disabled, no offsets
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi        Set true for SPI bus (prepends the R/W|MB|A5..A0 command byte).
 */
class ADXL345Minimal {
public:
    explicit ADXL345Minimal(Connection& connection, bool spi = false);

    /** @brief Read 3-axis linear acceleration.
     *
     *  Reads all six data bytes (DATAX0..DATAZ1) in one burst so the X, Y,
     *  Z samples are guaranteed to come from a single measurement.
     *
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read(float& x, float& y, float& z);

protected:
    static constexpr uint8_t REG_DEVID          = 0x00;
    static constexpr uint8_t REG_THRESH_TAP     = 0x1D;
    static constexpr uint8_t REG_OFSX           = 0x1E;
    static constexpr uint8_t REG_OFSY           = 0x1F;
    static constexpr uint8_t REG_OFSZ           = 0x20;
    static constexpr uint8_t REG_DUR            = 0x21;
    static constexpr uint8_t REG_LATENT         = 0x22;
    static constexpr uint8_t REG_WINDOW         = 0x23;
    static constexpr uint8_t REG_THRESH_ACT     = 0x24;
    static constexpr uint8_t REG_THRESH_INACT   = 0x25;
    static constexpr uint8_t REG_TIME_INACT     = 0x26;
    static constexpr uint8_t REG_ACT_INACT_CTL  = 0x27;
    static constexpr uint8_t REG_THRESH_FF      = 0x28;
    static constexpr uint8_t REG_TIME_FF        = 0x29;
    static constexpr uint8_t REG_TAP_AXES       = 0x2A;
    static constexpr uint8_t REG_BW_RATE        = 0x2C;
    static constexpr uint8_t REG_POWER_CTL      = 0x2D;
    static constexpr uint8_t REG_INT_ENABLE     = 0x2E;
    static constexpr uint8_t REG_INT_MAP        = 0x2F;
    static constexpr uint8_t REG_INT_SOURCE     = 0x30;
    static constexpr uint8_t REG_DATA_FORMAT    = 0x31;
    static constexpr uint8_t REG_DATAX0         = 0x32;
    static constexpr uint8_t REG_FIFO_CTL       = 0x38;
    static constexpr uint8_t REG_FIFO_STATUS    = 0x39;

    static constexpr uint8_t DEVID_VALUE        = 0xE5;
    static constexpr uint8_t DATA_FORMAT_DEFAULT = 0x08;  // FULL_RES=1, ±2 g
    static constexpr uint8_t BW_RATE_DEFAULT     = 0x0A;  // 100 Hz, normal power
    static constexpr uint8_t POWER_CTL_DEFAULT   = 0x08;  // Measure=1

    static constexpr float FULL_RES_SCALE_G_PER_LSB = 0.0039f;

    Connection& _connection;
    bool        _spi;
    uint8_t     _range_bits = 0;  // 0..3: 0=±2, 1=±4, 2=±8, 3=±16 g
    bool        _full_res   = true;

    uint8_t _cmd_byte(uint8_t reg, bool read, bool multi) const;
    void    _write_reg(uint8_t reg, uint8_t value);
    void    _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void    _delay_ms(uint32_t ms);
    static int16_t _decode_signed(const uint8_t* p);
};

/** @brief ADXL345 full interface — extends ADXL345Minimal with configuration, FIFO,
 *  tap / activity / inactivity / free-fall detection, and interrupt routing.
 *
 *  Adds range and data-rate selection, low-power mode, self-test,
 *  per-axis offset calibration (in *g*), single/double-tap detection,
 *  activity and inactivity detection, free-fall detection, 32-level FIFO,
 *  interrupt routing (INT1 / INT2), and sleep / auto-sleep / link mode.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi        Set true for SPI bus (prepends the R/W|MB|A5..A0 command byte).
 */
class ADXL345Full : public ADXL345Minimal {
public:
    // Interrupt source bits — match INT_ENABLE / INT_MAP / INT_SOURCE layout.
    static constexpr uint8_t INT_DATA_READY  = 0x80;
    static constexpr uint8_t INT_SINGLE_TAP  = 0x40;
    static constexpr uint8_t INT_DOUBLE_TAP  = 0x20;
    static constexpr uint8_t INT_ACTIVITY    = 0x10;
    static constexpr uint8_t INT_INACTIVITY  = 0x08;
    static constexpr uint8_t INT_FREE_FALL   = 0x04;
    static constexpr uint8_t INT_WATERMARK   = 0x02;
    static constexpr uint8_t INT_OVERRUN     = 0x01;

    // FIFO mode values — match FIFO_CTL bits 7:6.
    static constexpr uint8_t FIFO_BYPASS  = 0x00;
    static constexpr uint8_t FIFO_FIFO    = 0x40;
    static constexpr uint8_t FIFO_STREAM  = 0x80;
    static constexpr uint8_t FIFO_TRIGGER = 0xC0;

    // Sleep-mode wakeup sample rates (POWER_CTL Wakeup bits 2:1).
    static constexpr uint8_t WAKEUP_8_HZ = 0x00;
    static constexpr uint8_t WAKEUP_4_HZ = 0x02;
    static constexpr uint8_t WAKEUP_2_HZ = 0x04;
    static constexpr uint8_t WAKEUP_1_HZ = 0x06;

    explicit ADXL345Full(Connection& connection, bool spi = false);

    /** @brief Set the measurement range to ±2/±4/±8/±16 g.
     *  @param range_g One of 2, 4, 8, 16. FULL_RES is preserved.
     */
    void set_range(uint8_t range_g);

    /** @brief Set the output data rate to the nearest supported value (6.25 Hz–3200 Hz). */
    void set_data_rate(float rate_hz);

    /** @brief Enable or disable low-power mode (higher noise). */
    void set_low_power(bool enabled);

    /** @brief Set per-axis offset in *g*. */
    void set_offset(float x, float y, float z);

    /** @brief Measure and write per-axis offsets to null sensor bias.
     *
     *  Averages @p samples reads with the sensor stationary and writes the
     *  negative residual into the offset registers so subsequent readings
     *  track @p target_x, @p target_y, @p target_z.
     */
    void calibrate_offset(float target_x = 0.0f, float target_y = 0.0f,
                          float target_z = 1.0f, uint16_t samples = 128);

    /** @brief Configure single-tap detection and enable the SINGLE_TAP interrupt.
     *
     *  @param threshold_g Tap acceleration threshold in *g* (62.5 mg/LSB).
     *  @param duration_ms Maximum tap duration in ms (625 µs/LSB).
     *  @param axes        Bitmask of participating axes (bit 2=X, 1=Y, 0=Z; default 0x07).
     *  @param suppress    Suppress double-tap if acceleration persists between taps.
     */
    void set_tap_detection(float threshold_g, float duration_ms,
                           uint8_t axes = 0x07, bool suppress = false);

    /** @brief Configure double-tap latency and window; enable DOUBLE_TAP interrupt.
     *
     *  @param latency_ms Time after a tap when a second tap is expected (1.25 ms/LSB).
     *  @param window_ms  Time window after latency during which the second tap must occur.
     */
    void set_double_tap(float latency_ms, float window_ms);

    /** @brief Configure activity detection. */
    void set_activity(float threshold_g, uint8_t axes = 0x70, bool ac_coupled = true);

    /** @brief Configure inactivity detection. */
    void set_inactivity(float threshold_g, float time_sec,
                        uint8_t axes = 0x07, bool ac_coupled = false);

    /** @brief Configure free-fall detection and enable the FREE_FALL interrupt. */
    void set_free_fall(float threshold_g, float time_ms);

    /** @brief Enable or disable an interrupt source and route it to INT1 or INT2.
     *
     *  @param source  One of the ``INT_*`` constants.
     *  @param enabled True to enable, false to disable.
     *  @param pin     1 (default) for INT1, 2 for INT2.
     */
    void set_interrupt(uint8_t source, bool enabled, uint8_t pin = 1);

    /** @brief Read the INT_SOURCE register; clears latched interrupts. */
    uint8_t read_interrupt_source();

    /** @brief Configure the FIFO. */
    void set_fifo_mode(uint8_t mode, uint8_t samples = 16);

    /** @brief Number of FIFO entries currently available (0–32). */
    uint8_t fifo_count();

    /** @brief Drain the FIFO, returning all available (x, y, z) samples in *g*. */
    uint8_t read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples);

    /** @brief Enter or leave sleep mode. */
    void set_sleep(bool enabled, uint8_t wakeup_hz = 8);

    /** @brief Enable or disable the activity/inactivity serial-link mode. */
    void set_link_mode(bool enabled);

    /** @brief Enable or disable auto-sleep on inactivity (requires Link=1). */
    void set_auto_sleep(bool enabled);

    /** @brief Enable or disable the electrostatic self-test force on all axes. */
    void self_test(bool enabled);

private:
    uint8_t _encode_offset(float offset_g);
    void    _enable_interrupt(uint8_t source);
};