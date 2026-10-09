#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief BMA150 3-axis MEMS accelerometer — minimal interface.
 *
 *  Triaxial low-g accelerometer with 10-bit digital output and ±2/±4/±8 *g*
 *  selectable full-scale range. Communicates over I²C at the fixed address
 *  0x38 (the chip also supports 3-/4-wire SPI; out of scope for this
 *  driver).
 *
 *  Default configuration (baked in at construction):
 *  - Range ±2 *g* (256 LSB/g)
 *  - Bandwidth 100 Hz
 *  - All interrupts left at EEPROM defaults but INT unused
 *  - Calibration bits 7:5 of RANGE_BW (0x14) preserved
 *  - shadow_dis = 0 (LSB-then-MSB ordering enforced)
 *
 *  @param connection Configured RegisterConnection (I²C, SMBus, or SPI) pointing
 *                    at the device (I²C address 0x38).
 */
class BMA150Minimal {
public:
    explicit BMA150Minimal(RegisterConnection& connection);

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
    // Register map (0x00..0x15).
    static constexpr uint8_t REG_CHIP_ID          = 0x00;
    static constexpr uint8_t REG_VERSION          = 0x01;
    static constexpr uint8_t REG_ACC_X_LSB        = 0x02;
    static constexpr uint8_t REG_ACC_X_MSB        = 0x03;
    static constexpr uint8_t REG_ACC_Y_LSB        = 0x04;
    static constexpr uint8_t REG_ACC_Y_MSB        = 0x05;
    static constexpr uint8_t REG_ACC_Z_LSB        = 0x06;
    static constexpr uint8_t REG_ACC_Z_MSB        = 0x07;
    static constexpr uint8_t REG_TEMP             = 0x08;
    static constexpr uint8_t REG_STATUS           = 0x09;
    static constexpr uint8_t REG_CTRL             = 0x0A;
    static constexpr uint8_t REG_INT_CTRL         = 0x0B;
    static constexpr uint8_t REG_LG_THRES         = 0x0C;
    static constexpr uint8_t REG_LG_DUR           = 0x0D;
    static constexpr uint8_t REG_HG_THRES         = 0x0E;
    static constexpr uint8_t REG_HG_DUR           = 0x0F;
    static constexpr uint8_t REG_ANY_MOTION_THRES = 0x10;
    static constexpr uint8_t REG_HYST_DUR         = 0x11;
    static constexpr uint8_t REG_CUSTOMER_1       = 0x12;
    static constexpr uint8_t REG_CUSTOMER_2       = 0x13;
    static constexpr uint8_t REG_RANGE_BW         = 0x14;
    static constexpr uint8_t REG_CONFIG           = 0x15;

    // CHIP_ID is bits 2:0 of register 0x00; the value is 0b010 (0x02).
    static constexpr uint8_t CHIP_ID_VALUE  = 0x02;
    static constexpr uint8_t CHIP_ID_MASK   = 0x07;

    // Range bits in RANGE_BW (0x14) bits 4:3.
    static constexpr uint8_t RANGE_2G_MASK  = 0x00;
    static constexpr uint8_t RANGE_4G_MASK  = 0x08;
    static constexpr uint8_t RANGE_8G_MASK  = 0x10;

    // Bandwidth bits in RANGE_BW (0x14) bits 2:0.
    static constexpr uint8_t BW_25HZ     = 0x00;
    static constexpr uint8_t BW_50HZ     = 0x01;
    static constexpr uint8_t BW_100HZ    = 0x02;
    static constexpr uint8_t BW_190HZ    = 0x03;
    static constexpr uint8_t BW_375HZ    = 0x04;
    static constexpr uint8_t BW_750HZ    = 0x05;
    static constexpr uint8_t BW_1500HZ   = 0x06;

    // Sensitivity (LSB/g) by range.
    static constexpr float SCALE_2G = 256.0f;
    static constexpr float SCALE_4G = 128.0f;
    static constexpr float SCALE_8G = 64.0f;

    RegisterConnection& _connection;
    uint8_t _range_g = 2;

    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, size_t len);
    void    _delay_ms(uint32_t ms);
};

/** @brief BMA150 full interface — extends BMA150Minimal with configuration,
 *  interrupt sources, low-g / high-g / any-motion / alert logic, sleep,
 *  soft reset, and self-test.
 *
 *  Adds range (±2/±4/±8 *g*) and bandwidth (25..1500 Hz) selection, raw
 *  reading, temperature, low-g (free-fall), high-g (shock), any-motion and
 *  alert thresholds with duration, hysteresis and debounce counters, latched
 *  or self-resetting interrupts, self-wake-up mode, sleep and soft reset,
 *  electrostatic self-test, version register, and the two CUSTOMER
 *  scratch bytes.
 *
 *  @param connection Configured RegisterConnection (I²C, SMBus, or SPI) pointing
 *                    at the device (I²C address 0x38).
 */
class BMA150Full : public BMA150Minimal {
public:
    // Interrupt source bits — the driver uses these to enable / disable
    // one of the five sources. STATUS register latched bits are separate
    // (see read_interrupt_source()).
    static constexpr uint8_t SOURCE_LOW_G     = 0x01;
    static constexpr uint8_t SOURCE_HIGH_G    = 0x02;
    static constexpr uint8_t SOURCE_ANY_MOTION = 0x04;
    static constexpr uint8_t SOURCE_ALERT     = 0x08;
    static constexpr uint8_t SOURCE_NEW_DATA  = 0x10;

    // STATUS register bits.
    static constexpr uint8_t STATUS_ST_RESULT    = 0x80;
    static constexpr uint8_t STATUS_ALERT_PHASE  = 0x10;
    static constexpr uint8_t STATUS_LG_LATCHED   = 0x08;
    static constexpr uint8_t STATUS_HG_LATCHED   = 0x04;
    static constexpr uint8_t STATUS_LG           = 0x02;
    static constexpr uint8_t STATUS_HG           = 0x01;

    explicit BMA150Full(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration in *g*. Delegates to BMA150Minimal::read. */
    void read(float& x, float& y, float& z) { BMA150Minimal::read(x, y, z); }

    /** @brief Set the measurement range to ±2/±4/±8 *g*. */
    void set_range(uint8_t range_g);

    /** @brief Set the digital low-pass bandwidth to the nearest supported value (25..1500 Hz). */
    void set_bandwidth(uint16_t bandwidth_hz);

    /** @brief Read raw 10-bit two's-complement acceleration counts.
     *
     *  @param x Output X counts in [-512, 511].
     *  @param y Output Y counts in [-512, 511].
     *  @param z Output Z counts in [-512, 511].
     */
    void read_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Read on-chip temperature.
     *
     *  @return Temperature in °C, computed as raw * 0.5 - 30.
     */
    float read_temperature();

    /** @brief Return True if all three new_data_X/Y/Z bits are set. */
    bool new_data_available();

    /** @brief Enable or disable MSB-only reads (shadow_dis). */
    void set_shadow(bool enabled);

    /** @brief Configure the low-g (free-fall) interrupt and enable it. */
    void set_low_g(float threshold_g, uint16_t duration_ms,
                   float hysteresis_g = 0.0f, uint8_t counter = 0);

    /** @brief Configure the high-g (shock) interrupt and enable it. */
    void set_high_g(float threshold_g, uint16_t duration_ms,
                    float hysteresis_g = 0.0f, uint8_t counter = 0);

    /** @brief Configure the any-motion interrupt and enable it. */
    void set_any_motion(float threshold_g, uint8_t samples = 1);

    /** @brief Toggle alert mode (mutually exclusive with any-motion). */
    void set_alert(bool enabled);

    /** @brief Enable latched interrupts (cleared by clear_interrupt()). */
    void set_latch(bool enabled);

    /** @brief Clear latched interrupts (writes reset_INT to CTRL). */
    void clear_interrupt();

    /** @brief Enable one interrupt source. Disables mutually exclusive partners. */
    void enable_interrupt(uint8_t source);

    /** @brief Disable one interrupt source. */
    void disable_interrupt(uint8_t source);

    /** @brief Read STATUS without clearing latched bits. */
    uint8_t poll_interrupt();

    /** @brief Configure self-wake-up mode. */
    void set_wake_up(bool enabled, uint16_t pause_ms = 20);

    /** @brief Enter sleep mode. */
    void sleep();

    /** @brief Leave sleep mode. */
    void wake();

    /** @brief Issue a power-on-equivalent reset; waits 30 ms; restores range. */
    void soft_reset();

    /** @brief Run the electrostatic self-test, return true on pass. */
    bool self_test();

    /** @brief Read the STATUS register. */
    uint8_t read_status();

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
    void _write_hyst(const char* kind, float hysteresis_g);
    void _write_int_counter(const char* kind, uint8_t counter);
    void _enable_source(uint8_t source);
    void _disable_source(uint8_t source);
    void _read_raw_counts(int16_t& x, int16_t& y, int16_t& z);
    static uint8_t _nearest_bandwidth(uint16_t bandwidth_hz);
    static int16_t _decode_axis(const uint8_t* p);
};
