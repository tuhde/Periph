#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief LIS3DH 3-axis ultra-low-power MEMS accelerometer — minimal interface.
 *
 *  Triaxial accelerometer with 16-bit left-justified output and four
 *  selectable full-scale ranges (±2, ±4, ±8, ±16 *g*). Three operating
 *  modes trade resolution for current: low-power (8-bit), normal (10-bit)
 *  and high-resolution (12-bit), across output data rates from 1 Hz to
 *  5.376 kHz. Communicates over I²C at address 0x18 (SA0 = GND) or 0x19
 *  (SA0 = VDD_IO); the chip also supports 3-/4-wire SPI but that is out
 *  of scope for this driver.
 *
 *  After the slave address the master sends an 8-bit sub-address: the
 *  7 LSbs are the register address and the MSb enables address
 *  auto-increment, so every multi-byte transfer ORs 0x80 into the
 *  register address (single-register accesses do not).
 *
 *  Default configuration (baked in at construction):
 *  - Range ±2 *g* (1 mg/LSB)
 *  - 100 Hz output data rate
 *  - High-resolution (12-bit) mode, all three axes enabled
 *  - BDU = 1 (block data update)
 *  - High-pass filter bypassed, FIFO bypassed
 *  - All interrupt routing cleared
 *  - Aux ADC and temperature sensor off
 *  - CTRL_REG0 untouched
 *
 *  @param connection Configured RegisterConnection (I²C) pointing at the
 *                    device (address 0x18 with SA0 = GND, 0x19 with
 *                    SA0 = VDD_IO).
 */
class LIS3DHMinimal {
public:
    explicit LIS3DHMinimal(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration.
     *
     *  Burst-reads the six output bytes (0x28..0x2D) with auto-increment
     *  so X, Y and Z come from a single sample (BDU = 1), sign-extends
     *  the left-justified 16-bit pairs and scales them with the
     *  sensitivity of the current mode and range.
     *
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read(float& x, float& y, float& z);

protected:
    // Register map.
    static constexpr uint8_t REG_STATUS_AUX  = 0x07;
    static constexpr uint8_t REG_OUT_ADC1_L  = 0x08;
    static constexpr uint8_t REG_OUT_ADC1_H  = 0x09;
    static constexpr uint8_t REG_OUT_ADC2_L  = 0x0A;
    static constexpr uint8_t REG_OUT_ADC2_H  = 0x0B;
    static constexpr uint8_t REG_OUT_ADC3_L  = 0x0C;
    static constexpr uint8_t REG_OUT_ADC3_H  = 0x0D;
    static constexpr uint8_t REG_WHO_AM_I    = 0x0F;
    static constexpr uint8_t REG_CTRL_REG0   = 0x1E;
    static constexpr uint8_t REG_TEMP_CFG    = 0x1F;
    static constexpr uint8_t REG_CTRL_REG1   = 0x20;
    static constexpr uint8_t REG_CTRL_REG2   = 0x21;
    static constexpr uint8_t REG_CTRL_REG3   = 0x22;
    static constexpr uint8_t REG_CTRL_REG4   = 0x23;
    static constexpr uint8_t REG_CTRL_REG5   = 0x24;
    static constexpr uint8_t REG_CTRL_REG6   = 0x25;
    static constexpr uint8_t REG_REFERENCE   = 0x26;
    static constexpr uint8_t REG_STATUS      = 0x27;
    static constexpr uint8_t REG_OUT_X_L     = 0x28;
    static constexpr uint8_t REG_OUT_X_H     = 0x29;
    static constexpr uint8_t REG_OUT_Y_L     = 0x2A;
    static constexpr uint8_t REG_OUT_Y_H     = 0x2B;
    static constexpr uint8_t REG_OUT_Z_L     = 0x2C;
    static constexpr uint8_t REG_OUT_Z_H     = 0x2D;
    static constexpr uint8_t REG_FIFO_CTRL   = 0x2E;
    static constexpr uint8_t REG_FIFO_SRC    = 0x2F;
    static constexpr uint8_t REG_INT1_CFG    = 0x30;
    static constexpr uint8_t REG_INT1_SRC    = 0x31;
    static constexpr uint8_t REG_INT1_THS    = 0x32;
    static constexpr uint8_t REG_INT1_DUR    = 0x33;
    static constexpr uint8_t REG_INT2_CFG    = 0x34;
    static constexpr uint8_t REG_INT2_SRC    = 0x35;
    static constexpr uint8_t REG_INT2_THS    = 0x36;
    static constexpr uint8_t REG_INT2_DUR    = 0x37;
    static constexpr uint8_t REG_CLICK_CFG   = 0x38;
    static constexpr uint8_t REG_CLICK_SRC   = 0x39;
    static constexpr uint8_t REG_CLICK_THS   = 0x3A;
    static constexpr uint8_t REG_TIME_LIMIT  = 0x3B;
    static constexpr uint8_t REG_TIME_LATENCY = 0x3C;
    static constexpr uint8_t REG_TIME_WINDOW = 0x3D;
    static constexpr uint8_t REG_ACT_THS     = 0x3E;
    static constexpr uint8_t REG_ACT_DUR     = 0x3F;

    // WHO_AM_I (0x0F) reset value.
    static constexpr uint8_t WHO_AM_I_VALUE = 0x33;

    // Control-register defaults of the Initialization Sequence.
    static constexpr uint8_t CTRL_REG4_DEFAULT = 0x88;  // BDU=1, FS=00 (±2 g), HR=1
    static constexpr uint8_t CTRL_REG1_DEFAULT = 0x57;  // ODR=0101 (100 Hz), LPen=0, X/Y/Z

    // Operating modes.
    static constexpr uint8_t MODE_LOW_POWER = 0;  // 8-bit
    static constexpr uint8_t MODE_NORMAL    = 1;  // 10-bit
    static constexpr uint8_t MODE_HIGH_RES  = 2;  // 12-bit

    RegisterConnection& _connection;
    uint8_t     _range_g = 2;
    uint8_t     _mode    = MODE_HIGH_RES;
    uint16_t    _rate_hz = 100;

    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, size_t len);
    void    _delay_ms(uint32_t ms);
    int16_t _decode_axis(uint8_t low, uint8_t high) const;
    float   _sensitivity_g() const;
    uint32_t _settle_ms() const;
};

/** @brief LIS3DH full interface — extends LIS3DHMinimal with the complete chip
 *  functionality.
 *
 *  Adds range / data-rate / mode selection, raw counts, status and
 *  temperature reads, the auxiliary ADC, the high-pass filter, the
 *  32-level FIFO, both inertial interrupt generators (free-fall, 6D / 4D
 *  orientation), single / double click detection, the sleep-to-wake
 *  activity function, interrupt routing and delivery on INT1 / INT2,
 *  memory reboot, SDO pull-up control and the electrostatic self-test.
 *
 *  @param connection Configured RegisterConnection (I²C) pointing at the
 *                    device (address 0x18 with SA0 = GND, 0x19 with
 *                    SA0 = VDD_IO).
 */
class LIS3DHFull : public LIS3DHMinimal {
public:
    // Interrupt source constants — match enable_interrupt() / disable_interrupt()
    // and the bit layout returned by poll_interrupt() for bits 0-6.
    // SOURCE_ACTIVITY and SOURCE_BOOT are pin-only: the chip has no register
    // flag for them, so poll_interrupt() never reports them.
    static constexpr uint16_t SOURCE_DATA_READY     = 0x0001;
    static constexpr uint16_t SOURCE_AUX_DATA_READY = 0x0002;
    static constexpr uint16_t SOURCE_WATERMARK      = 0x0004;
    static constexpr uint16_t SOURCE_OVERRUN        = 0x0008;
    static constexpr uint16_t SOURCE_IA1            = 0x0010;
    static constexpr uint16_t SOURCE_IA2            = 0x0020;
    static constexpr uint16_t SOURCE_CLICK          = 0x0040;
    static constexpr uint16_t SOURCE_ACTIVITY       = 0x0080;
    static constexpr uint16_t SOURCE_BOOT           = 0x0100;

    // FIFO modes.
    static constexpr uint8_t FIFO_BYPASS         = 0;
    static constexpr uint8_t FIFO_FIFO           = 1;
    static constexpr uint8_t FIFO_STREAM         = 2;
    static constexpr uint8_t FIFO_STREAM_TO_FIFO = 3;

    // Generator selector.
    static constexpr uint8_t GENERATOR_1 = 1;
    static constexpr uint8_t GENERATOR_2 = 2;

    explicit LIS3DHFull(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration in *g*. Delegates to LIS3DHMinimal::read. */
    void read(float& x, float& y, float& z) { LIS3DHMinimal::read(x, y, z); }

    /** @brief Set the full-scale measurement range.
     *  @param range_g One of 2, 4, 8, 16 (*g*). */
    void set_range(uint8_t range_g);

    /** @brief Set the output data rate (or power down).
     *  @param rate_hz One of 0 (power-down), 1, 10, 25, 50, 100, 200, 400,
     *                1344, 1620, 5376. 1620 and 5376 exist in low-power mode
     *                only; 1344 in normal / high-resolution mode only. */
    void set_data_rate(uint16_t rate_hz);

    /** @brief Set the operating mode (resolution / power trade-off).
     *  @param mode 0 = low-power (8-bit), 1 = normal (10-bit),
     *              2 = high-resolution (12-bit). */
    void set_mode(uint8_t mode);

    /** @brief Enable or disable the individual measurement axes. */
    void set_axes(bool x = true, bool y = true, bool z = true);

    /** @brief Stop conversions (ODR = 0000); the register contents are retained. */
    void power_down();

    /** @brief Read the raw acceleration counts at the current resolution.
     *  @param x Output X counts (signed).
     *  @param y Output Y counts (signed).
     *  @param z Output Z counts (signed). */
    void read_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Return true when STATUS_REG.ZYXDA indicates a fresh sample set. */
    bool new_data_available();

    /** @brief Read the STATUS register (overrun and data-available flags). */
    uint8_t read_status();

    /** @brief Read the relative on-chip temperature (1 digit/°C, signed delta).
     *
     *  Enables ADC_EN and TEMP_EN in TEMP_CFG_REG on first use. The
     *  datasheet specifies no zero-degree reference, so the value is a
     *  relative delta, never an absolute temperature.
     *  @return Relative temperature in °C (8-bit two's complement).
     */
    int16_t read_temperature_delta();

    /** @brief Read one auxiliary ADC channel.
     *  @param channel 1, 2 or 3. Channel 3 is the temperature-sensor input
     *                 while TEMP_EN = 1.
     *  @return Input voltage in V (1.200 V centre, ±400 mV range; the
     *          mapping is derived — see the spec's Implementation Notes).
     */
    float read_adc(uint8_t channel);

    /** @brief Enable or disable the auxiliary ADC (ADC_EN in TEMP_CFG_REG). */
    void enable_adc(bool enabled);

    /** @brief Configure the high-pass filter (CTRL_REG2).
     *  @param mode HPM: 0 normal (reset by reading REFERENCE), 1 reference
     *              signal for filtering, 2 normal, 3 auto-reset on interrupt.
     *  @param cutoff HPCF<2:1> code 0-3 (the datasheet tabulates no Hz
     *                values; they scale with ODR).
     *  @param filter_output Route the filtered data to the output registers
     *                       and FIFO (FDS).
     *  @param on_ia1 Apply the filter to interrupt generator 1.
     *  @param on_ia2 Apply the filter to interrupt generator 2.
     *  @param on_click Apply the filter to the click function. */
    void set_high_pass(uint8_t mode = 0, uint8_t cutoff = 0, bool filter_output = false,
                       bool on_ia1 = false, bool on_ia2 = false, bool on_click = false);

    /** @brief Reset the filter by reading REFERENCE (HPM = 00).
     *  @return The REFERENCE register value that was read. */
    uint8_t reset_high_pass();

    /** @brief Write the high-pass reference value (HPM = 01).
     *  @param value 0-255. */
    void set_reference(uint8_t value);

    /** @brief Configure the 32-level FIFO (FIFO_CTRL_REG).
     *
     *  Sets FIFO_EN in CTRL_REG5 for modes 1-3; returning to bypass
     *  (mode 0) clears the buffer. Only bypass mode empties the buffer —
     *  restart a FIFO mode through bypass after a full drain.
     *
     *  @param mode 0 = bypass, 1 = FIFO, 2 = stream, 3 = stream-to-FIFO.
     *  @param watermark Watermark level FTH 0-31.
     *  @param trigger_int2 Trigger source for stream-to-FIFO: false =
     *                      INT1, true = INT2 (TR).
     */
    void set_fifo(uint8_t mode, uint8_t watermark = 0, bool trigger_int2 = false);

    /** @brief Read the number of unread sample sets in the FIFO (FSS<4:0>, 0-32). */
    uint8_t fifo_level();

    /** @brief Drain sample sets from the FIFO into caller-provided arrays.
     *
     *  Each sample set is one 6-byte burst of 0x28..0x2D; the address
     *  auto-increment wraps back to 0x28 so a full drain is 32 reads.
     *
     *  @param x_buf Output X samples in *g*.
     *  @param y_buf Output Y samples in *g*.
     *  @param z_buf Output Z samples in *g*.
     *  @param max_samples Capacity of each output array.
     *  @return Number of sample sets read (never more than the FIFO holds). */
    uint8_t read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples);

    /** @brief Decode FIFO_SRC_REG.
     *  @param watermark Output WTM flag.
     *  @param overrun Output OVRN_FIFO flag.
     *  @param empty Output EMPTY flag.
     *  @param level Output FSS<4:0> (0-32). */
    void read_fifo_status(bool& watermark, bool& overrun, bool& empty, uint8_t& level);

    /** @brief Configure an inertial interrupt generator (motion / threshold).
     *  @param generator 1 or 2 — the INT1 or INT2 register block.
     *  @param events 6-bit ZHIE..XLIE event-enable mask (0-0x3F).
     *  @param threshold_g Threshold in *g*; scaled with the current
     *                     full-scale range (16 / 32 / 62 / 186 mg per LSb).
     *  @param duration_ms Minimum duration the events must hold, in ms
     *                     (INTx_DURATION, 1 LSb = 1/ODR).
     *  @param and_mode True for an AND combination of the enabled events
     *                  (AOI = 1), false for OR. */
    void set_motion_interrupt(uint8_t generator, uint8_t events, float threshold_g,
                              uint16_t duration_ms = 0, bool and_mode = false);

    /** @brief Configure the free-fall detector: all three axes low, AND mode.
     *  @param generator 1 or 2.
     *  @param threshold_g Free-fall threshold in *g*.
     *  @param duration_ms Minimum time below the threshold in ms. */
    void set_free_fall(uint8_t generator = 1, float threshold_g = 0.35f,
                       uint16_t duration_ms = 30);

    /** @brief Configure 6D / 4D orientation detection.
     *  @param generator 1 or 2.
     *  @param position False = 6D movement recognition, true = 6D position
     *                  recognition.
     *  @param four_d Enable 4D detection (D4D_INTx) — disables the Z-axis
     *                zones. */
    void set_orientation(uint8_t generator = 1, bool position = false, bool four_d = false);

    /** @brief Latch a generator's interrupt until its INTx_SRC is read.
     *  @param generator 1 or 2.
     *  @param enabled True to latch (LIR_INTx), false for self-resetting. */
    void set_latch(uint8_t generator, bool enabled);

    /** @brief Read a generator's INTx_SRC — clears the interrupt.
     *  @param generator 1 or 2.
     *  @return INTx_SRC byte (IA = bit 6, per-axis event flags in bits 5-0). */
    uint8_t read_interrupt_source(uint8_t generator);

    /** @brief Configure single / double click detection (CLICK_*).
     *  @param single Axis mask of single-click enables (X = 1, Y = 2, Z = 4).
     *  @param double_ Axis mask of double-click enables (X = 1, Y = 2, Z = 4).
     *  @param threshold_g Click threshold in *g* (same scale as INTx_THS).
     *  @param time_limit_ms Maximum time between the two clicks of a double click.
     *  @param latency_ms Minimum time between two consecutive clicks.
     *  @param window_ms Maximum time after the first click of a double click.
     *  @param latch Keep the interrupt high until CLICK_SRC is read (LIR_Click). */
    void set_click(uint8_t single = 0, uint8_t double_ = 0, float threshold_g = 0.5f,
                   uint16_t time_limit_ms = 10, uint16_t latency_ms = 20,
                   uint16_t window_ms = 50, bool latch = false);

    /** @brief Read CLICK_SRC — clears the click interrupt.
     *  @return CLICK_SRC byte (IA = bit 6, DCLICK = bit 5, SCLICK = bit 4,
     *          Sign = bit 3, axis flags in bits 2-0). */
    uint8_t read_click_source();

    /** @brief Configure the sleep-to-wake / return-to-sleep activity function.
     *  @param threshold_g Activation threshold in *g* (ACT_THS).
     *  @param duration_ms Activation duration in ms (ACT_DUR, where
     *                     1 LSb = (8·ACT_DUR + 1)/ODR). */
    void set_activity(float threshold_g, uint16_t duration_ms);

    /** @brief Set the shared INT1 / INT2 output polarity (INT_POLARITY).
     *  @param active_low True for active-low, false for active-high. */
    void set_interrupt_polarity(bool active_low);

    /** @brief Route one interrupt source to INT`pin`.
     *
     *  Only routes the source — the generator or function behind it is
     *  configured with the set_* methods. `pin` is ignored for INT1-only
     *  (data-ready, aux data-ready, watermark, overrun) and INT2-only
     *  (activity, boot) sources.
     *
     *  @param source One of the SOURCE_* constants.
     *  @param pin 1 or 2. */
    void enable_interrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Remove one interrupt source from INT`pin`.
     *  @param source One of the SOURCE_* constants.
     *  @param pin 1 or 2. */
    void disable_interrupt(uint16_t source, uint8_t pin = 1);

    /** @brief Subscribe to INT`pin` assertions.
     *
     *  The callback receives the combined status int returned by
     *  poll_interrupt(), and is only invoked for a non-zero status.
     *
     *  @param callback Function called with the status int.
     *  @param pin 1 or 2 — the INT line the connection's intPin is wired to. */
    void on_interrupt(void (*callback)(uint16_t status), uint8_t pin = 1);

    /** @brief Unsubscribe from INT`pin` assertions.
     *  @param pin 1 or 2. */
    void off_interrupt(uint8_t pin = 1);

    /** @brief Read the source registers of every currently enabled source.
     *
     *  Reading INT1_SRC / INT2_SRC / CLICK_SRC clears their latched flags
     *  (and the INT pin).
     *
     *  @return Combined status int — bit 0 SOURCE_DATA_READY, bit 1
     *          SOURCE_AUX_DATA_READY, bit 2 SOURCE_WATERMARK, bit 3
     *          SOURCE_OVERRUN, bit 4 SOURCE_IA1, bit 5 SOURCE_IA2, bit 6
     *          SOURCE_CLICK. Sources that are not enabled read as 0;
     *          SOURCE_ACTIVITY and SOURCE_BOOT have no register flag and
     *          are never reported. */
    uint16_t poll_interrupt();

    /** @brief Reboot the memory content (BOOT = 1) and re-run the init sequence.
     *
     *  Reloads the factory trim and waits 5 ms. The datasheet does not
     *  say whether the control registers survive, so the Initialization
     *  Sequence is re-run afterwards — configuration returns to the
     *  Minimal defaults.
     */
    void reboot();

    /** @brief Connect or disconnect the pull-up on SDO/SA0 (CTRL_REG0).
     *  @param connected True to connect the pull-up (SDO_PU_DISC = 0),
     *                   false to disconnect it. */
    void set_sdo_pullup(bool connected);

    /** @brief Run the datasheet electrostatic self-test.
     *
     *  Configures ±2 *g*, normal (10-bit) mode, 50 Hz with BDU on, averages
     *  a few samples with ST = 00, enables ST (01 = self-test 0, 10 =
     *  self-test 1), waits 2 samples and averages again. Every axis must
     *  show a 17-360 LSb change (1 LSb = 4 mg at 10-bit / ±2 *g*). The
     *  previous CTRL_REG1 / CTRL_REG4 configuration is restored afterwards.
     *
     *  @param mode 1 = self-test 0, 2 = self-test 1.
     *  @return True if every axis showed a change inside 17-360 LSb. */
    bool self_test(uint8_t mode = 1);

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
    static void _self_test_average(LIS3DHFull& chip, int16_t* out, uint8_t samples, uint32_t sample_ms);
    void    _handle_edge(uint8_t pin);

    static LIS3DHFull* _activeInstance;
    static uint8_t    _activePin;
    static void _edge_trampoline1();
    static void _edge_trampoline2();
};
