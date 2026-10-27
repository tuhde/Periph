#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief LSM303 accelerometer + magnetometer e-compass — minimal interface.
 *
 *  The LSM303DLHC combines a three-axis MEMS accelerometer (0x19) and a
 *  three-axis magnetometer (0x1E) behind two independent I²C slave
 *  addresses on one bus. The accelerometer has four full-scale ranges
 *  (±2, ±4, ±8, ±16 *g*), left-justified 16-bit output, three operating
 *  modes (low-power / normal / high-resolution), a 32-level FIFO, a
 *  high-pass filter, two inertial interrupt generators and click
 *  detection. The magnetometer has seven gain settings (±1.3 to ±8.1
 *  gauss), 12-bit output, data rates from 0.75 Hz to 220 Hz and a
 *  relative-only temperature sensor.
 *
 *  Accelerometer multi-byte transfers set bit 7 of the sub-address
 *  (auto-increment); the magnetometer pointer always auto-increments and
 *  needs no such bit. Neither slave has a soft reset.
 *
 *  Default configuration (baked in at construction):
 *  - Accelerometer ±2 *g* (1 mg/LSB), 100 Hz, high-resolution 12-bit,
 *    all axes enabled, BDU = 1, high-pass and FIFO bypassed, all
 *    interrupt routing cleared
 *  - Magnetometer ±130 µT, 15 Hz, continuous conversion, temperature off
 *
 *  @param accel_connection RegisterConnection (I²C) bound to 0x19.
 *  @param mag_connection   RegisterConnection (I²C) bound to 0x1E.
 */
class LSM303Minimal {
public:
    LSM303Minimal(RegisterConnection& accel_connection, RegisterConnection& mag_connection);

    /** @brief Read 3-axis linear acceleration in *g*.
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read_acceleration(float& x, float& y, float& z);

    /** @brief Read 3-axis magnetic flux density in µT.
     *  @param x Output X flux density in µT.
     *  @param y Output Y flux density in µT.
     *  @param z Output Z flux density in µT.
     */
    void read_magnetic(float& x, float& y, float& z);

protected:
    // Accelerometer registers.
    static constexpr uint8_t REG_CTRL_REG1    = 0x20;
    static constexpr uint8_t REG_CTRL_REG2    = 0x21;
    static constexpr uint8_t REG_CTRL_REG3    = 0x22;
    static constexpr uint8_t REG_CTRL_REG4    = 0x23;
    static constexpr uint8_t REG_CTRL_REG5    = 0x24;
    static constexpr uint8_t REG_CTRL_REG6    = 0x25;
    static constexpr uint8_t REG_REFERENCE    = 0x26;
    static constexpr uint8_t REG_STATUS       = 0x27;
    static constexpr uint8_t REG_OUT_X_L      = 0x28;
    static constexpr uint8_t REG_OUT_X_H      = 0x29;
    static constexpr uint8_t REG_OUT_Y_L      = 0x2A;
    static constexpr uint8_t REG_OUT_Y_H      = 0x2B;
    static constexpr uint8_t REG_OUT_Z_L      = 0x2C;
    static constexpr uint8_t REG_OUT_Z_H      = 0x2D;
    static constexpr uint8_t REG_FIFO_CTRL    = 0x2E;
    static constexpr uint8_t REG_FIFO_SRC     = 0x2F;
    static constexpr uint8_t REG_INT1_CFG     = 0x30;
    static constexpr uint8_t REG_INT1_SRC     = 0x31;
    static constexpr uint8_t REG_INT1_THS     = 0x32;
    static constexpr uint8_t REG_INT1_DUR     = 0x33;
    static constexpr uint8_t REG_INT2_CFG     = 0x34;
    static constexpr uint8_t REG_INT2_SRC     = 0x35;
    static constexpr uint8_t REG_INT2_THS     = 0x36;
    static constexpr uint8_t REG_INT2_DUR     = 0x37;
    static constexpr uint8_t REG_CLICK_CFG    = 0x38;
    static constexpr uint8_t REG_CLICK_SRC    = 0x39;
    static constexpr uint8_t REG_CLICK_THS    = 0x3A;
    static constexpr uint8_t REG_TIME_LIMIT   = 0x3B;
    static constexpr uint8_t REG_TIME_LATENCY = 0x3C;
    static constexpr uint8_t REG_TIME_WINDOW  = 0x3D;

    // Magnetometer registers.
    static constexpr uint8_t MAG_REG_CRA     = 0x00;
    static constexpr uint8_t MAG_REG_CRB     = 0x01;
    static constexpr uint8_t MAG_REG_MR      = 0x02;
    static constexpr uint8_t MAG_REG_OUT_X_H = 0x03;
    static constexpr uint8_t MAG_REG_OUT_X_L = 0x04;
    static constexpr uint8_t MAG_REG_OUT_Z_H = 0x05;
    static constexpr uint8_t MAG_REG_OUT_Z_L = 0x06;
    static constexpr uint8_t MAG_REG_OUT_Y_H = 0x07;
    static constexpr uint8_t MAG_REG_OUT_Y_L = 0x08;
    static constexpr uint8_t MAG_REG_SR      = 0x09;
    static constexpr uint8_t MAG_REG_IRA     = 0x0A;
    static constexpr uint8_t MAG_REG_IRB     = 0x0B;
    static constexpr uint8_t MAG_REG_IRC     = 0x0C;
    static constexpr uint8_t MAG_REG_TEMP_H  = 0x31;
    static constexpr uint8_t MAG_REG_TEMP_L  = 0x32;

    // Control-register defaults of the Initialization Sequence.
    static constexpr uint8_t CTRL_REG4_DEFAULT = 0x88;  // BDU=1, FS=00 (±2 g), HR=1
    static constexpr uint8_t CTRL_REG1_DEFAULT = 0x57;  // ODR=0101 (100 Hz), LPen=0, X/Y/Z

    // Operating modes.
    static constexpr uint8_t MODE_LOW_POWER = 0;  // 8-bit
    static constexpr uint8_t MODE_NORMAL    = 1;  // 10-bit
    static constexpr uint8_t MODE_HIGH_RES  = 2;  // 12-bit

    RegisterConnection& _accel;
    RegisterConnection& _mag;

    uint8_t  _range_g = 2;
    uint8_t  _mode    = MODE_HIGH_RES;
    uint16_t _rate_hz = 100;
    uint8_t  _mag_gain_code = 0x20;  // ±1.3 gauss
    float    _mag_rate_hz = 15.0f;

    // Accelerometer helpers.
    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, size_t len);
    void    _delay_ms(uint32_t ms);
    int16_t _decode_axis(uint8_t low, uint8_t high) const;
    float   _sensitivity_g() const;
    uint32_t _settle_ms() const;
    void    _run_init_sequence();

    // Magnetometer helpers.
    void    _mag_write_reg(uint8_t reg, uint8_t value);
    uint8_t _mag_read_reg(uint8_t reg);
    void    _mag_read_burst(uint8_t reg, uint8_t* buf, size_t len);
    float   _mag_xy_gain() const;
    float   _mag_z_gain() const;
    int16_t _decode_mag_axis(uint8_t high, uint8_t low) const;
};

/** @brief LSM303 full interface — extends LSM303Minimal with the complete
 *  chip functionality.
 *
 *  Adds accelerometer range / data-rate / mode selection, raw counts,
 *  status, the high-pass filter, the 32-level FIFO, both inertial interrupt
 *  generators, click detection, interrupt routing and delivery on INT1 /
 *  INT2, memory reboot, and magnetometer gain / rate / mode, temperature
 *  and compass heading.
 *
 *  @param accel_connection RegisterConnection (I²C) bound to 0x19.
 *  @param mag_connection   RegisterConnection (I²C) bound to 0x1E.
 */
class LSM303Full : public LSM303Minimal {
public:
    // Interrupt sources.
    static constexpr uint16_t SOURCE_DATA_READY     = 0x0001;
    static constexpr uint16_t SOURCE_WATERMARK      = 0x0002;
    static constexpr uint16_t SOURCE_OVERRUN        = 0x0004;
    static constexpr uint16_t SOURCE_IA1            = 0x0008;
    static constexpr uint16_t SOURCE_IA2            = 0x0010;
    static constexpr uint16_t SOURCE_CLICK          = 0x0020;
    static constexpr uint16_t SOURCE_BOOT           = 0x0040;
    static constexpr uint16_t SOURCE_MAG_DATA_READY = 0x0080;

    // FIFO modes.
    static constexpr uint8_t FIFO_BYPASS         = 0;
    static constexpr uint8_t FIFO_FIFO           = 1;
    static constexpr uint8_t FIFO_STREAM         = 2;
    static constexpr uint8_t FIFO_STREAM_TO_FIFO = 3;

    // Generator selector.
    static constexpr uint8_t GENERATOR_1 = 1;
    static constexpr uint8_t GENERATOR_2 = 2;

    LSM303Full(RegisterConnection& accel_connection, RegisterConnection& mag_connection);

    /** @brief Set the accelerometer full-scale range.
     *  @param range_g One of 2, 4, 8, 16 (*g*). */
    void set_range(uint8_t range_g);

    /** @brief Set the accelerometer output data rate (or power down).
     *  @param rate_hz One of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620,
     *                5376. 1620 / 5376 are low-power only; 1344 is not. */
    void set_data_rate(uint16_t rate_hz);

    /** @brief Set the accelerometer operating mode.
     *  @param mode 0 = low-power, 1 = normal, 2 = high-resolution. */
    void set_mode(uint8_t mode);

    /** @brief Enable or disable the individual accelerometer axes. */
    void set_axes(bool x = true, bool y = true, bool z = true);

    /** @brief Stop accelerometer conversions (ODR = 0000). */
    void power_down();

    /** @brief Read the raw accelerometer counts at the current resolution. */
    void read_acceleration_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Return true when STATUS_REG_A.ZYXDA indicates a fresh sample. */
    bool new_data_available();

    /** @brief Read STATUS_REG_A (overrun and data-available flags). */
    uint8_t read_status();

    /** @brief Configure the accelerometer high-pass filter (CTRL_REG2_A). */
    void set_high_pass(uint8_t mode = 0, uint8_t cutoff = 0, bool filter_output = false,
                       bool on_ia1 = false, bool on_ia2 = false, bool on_click = false);

    /** @brief Reset the high-pass filter by reading REFERENCE_A.
     *  @return The REFERENCE_A value that was read. */
    uint8_t reset_high_pass();

    /** @brief Write the high-pass reference value.
     *  @param value 0-255. */
    void set_reference(uint8_t value);

    /** @brief Configure the 32-level accelerometer FIFO.
     *  @param mode 0 = bypass, 1 = FIFO, 2 = stream, 3 = trigger.
     *  @param watermark Watermark level FTH 0-31.
     *  @param trigger_int2 Trigger source (false = INT1, true = INT2). */
    void set_fifo(uint8_t mode, uint8_t watermark = 0, bool trigger_int2 = false);

    /** @brief Read the number of unread sample sets in the FIFO (0-32). */
    uint8_t fifo_level();

    /** @brief Drain sample sets from the FIFO into caller-provided arrays. */
    uint8_t read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples);

    /** @brief Decode FIFO_SRC_REG_A. */
    void read_fifo_status(bool& watermark, bool& overrun, bool& empty, uint8_t& level);

    /** @brief Configure an inertial interrupt generator. */
    void set_motion_interrupt(uint8_t generator, uint8_t events, float threshold_g,
                              uint16_t duration_ms = 0, bool and_mode = false);

    /** @brief Configure the free-fall detector (all axes low, AND mode). */
    void set_free_fall(uint8_t generator = 1, float threshold_g = 0.35f,
                       uint16_t duration_ms = 30);

    /** @brief Configure 6D / 4D orientation detection. */
    void set_orientation(uint8_t generator = 1, bool position = false, bool four_d = false);

    /** @brief Latch a generator's interrupt until its source register is read. */
    void set_latch(uint8_t generator, bool enabled);

    /** @brief Read a generator's INTx_SOURCE_A — clears the interrupt. */
    uint8_t read_interrupt_source(uint8_t generator);

    /** @brief Configure single / double click detection.
     *  @param single Axis mask of single-click enables (X=1, Y=2, Z=4).
     *  @param double_ Axis mask of double-click enables (X=1, Y=2, Z=4).
     *  @param threshold_g Click threshold in *g*.
     *  @param time_limit_ms Click time limit.
     *  @param latency_ms Click latency.
     *  @param window_ms Click window. */
    void set_click(uint8_t single = 0, uint8_t double_ = 0, float threshold_g = 0.5f,
                   uint16_t time_limit_ms = 10, uint16_t latency_ms = 20,
                   uint16_t window_ms = 50);

    /** @brief Read CLICK_SRC_A — clears the click interrupt. */
    uint8_t read_click_source();

    /** @brief Set the shared INT1 / INT2 polarity (H_LACTIVE). */
    void set_interrupt_polarity(bool active_low);

    /** @brief Route one interrupt source to INT@p pin. */
    void enable_interrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Remove one interrupt source from INT@p pin. */
    void disable_interrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Subscribe to INT@p pin assertions. */
    void on_interrupt(void (*callback)(uint16_t status), uint8_t pin = 1);

    /** @brief Unsubscribe from INT@p pin assertions. */
    void off_interrupt(uint8_t pin = 1);

    /** @brief Read every enabled source and return the combined status. */
    uint16_t poll_interrupt();

    /** @brief Set the magnetometer gain / full-scale range.
     *  @param range_ut One of 130, 190, 250, 400, 470, 560, 810 (µT). */
    void set_mag_range(uint16_t range_ut);

    /** @brief Set the magnetometer output data rate.
     *  @param rate_hz One of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220. */
    void set_mag_data_rate(float rate_hz);

    /** @brief Set the magnetometer operating mode.
     *  @param mode 0 = continuous, 1 = single, 2 = sleep. */
    void set_mag_mode(uint8_t mode);

    /** @brief Read raw magnetometer counts, reordered to X, Y, Z. */
    void read_magnetic_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Return true when SR_REG_M.DRDY indicates a new sample. */
    bool mag_data_ready();

    /** @brief Decode SR_REG_M. */
    void read_mag_status(bool& drdy, bool& lock);

    /** @brief Read the magnetometer identity bytes. */
    void read_mag_identity(uint8_t out[3]);

    /** @brief Enable or disable the magnetometer temperature sensor. */
    void enable_temperature(bool enabled);

    /** @brief Read the relative magnetometer temperature.
     *  @return Relative temperature in °C (8 LSB/°C, no absolute reference). */
    float read_temperature_delta();

    /** @brief Compute the compass heading.
     *  @param tilt_compensate Use the accelerometer for pitch / roll.
     *  @return Heading in degrees (0-360). */
    float heading(bool tilt_compensate = true);

    /** @brief Reboot the accelerometer memory and re-run the init sequence. */
    void reboot();

private:
    uint8_t  _enabled1 = 0;
    uint8_t  _enabled2 = 0;

    void (*_callback1)(uint16_t status) = nullptr;
    void (*_callback2)(uint16_t status) = nullptr;
    InputPin* _intPin1 = nullptr;
    InputPin* _intPin2 = nullptr;

    void    _write_reg_rmw(uint8_t reg, uint8_t set_mask, uint8_t clear_mask);
    void    _write_reg_field(uint8_t reg, uint8_t value, uint8_t shift, uint8_t mask);
    uint8_t _generator_cfg_reg(uint8_t generator);
    uint8_t _generator_src_reg(uint8_t generator);
    uint8_t _generator_ths_reg(uint8_t generator);
    uint8_t _generator_dur_reg(uint8_t generator);
    uint8_t _source_bit(uint16_t source);
    void    _route(uint16_t source, uint8_t pin, uint8_t& reg, uint8_t& mask, uint8_t& track);
    uint8_t _threshold_code(float threshold_g);
    uint8_t _duration_code(uint16_t duration_ms, uint8_t limit);
    void    _handle_edge(uint8_t pin);

    static LSM303Full* _activeInstance;
    static uint8_t    _activePin;
    static void _edge_trampoline1();
    static void _edge_trampoline2();
};
