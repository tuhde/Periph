#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/Register.h"

/** @brief MMA8451Q 3-axis MEMS accelerometer — minimal interface.
 *
 *  Low-power three-axis capacitive MEMS accelerometer with a 14-bit output
 *  (4096 counts/g at ±2 g) and a selectable ±2 / ±4 / ±8 g full-scale range.
 *  Communicates over I²C at 0x1C (SA0 = GND) or 0x1D (SA0 = VDDIO).
 *
 *  The constructor runs the chip's initialization sequence:
 *   -# wait >= 1 ms for the power-up (I²C ready ~350 us after VDDIO);
 *   -# verify WHO_AM_I (0x0D) == 0x1A;
 *   -# issue a software reset (CTRL_REG2.RST) and wait for it to clear;
 *   -# write XYZ_DATA_CFG = 0x00 (±2 g, high-pass output off);
 *   -# write CTRL_REG2 = 0x00 (normal oversampling in WAKE and SLEEP);
 *   -# write CTRL_REG1 = 0x19 (DR = 011 → 100 Hz, ACTIVE = 1);
 *   -# wait 2/ODR + 2 ms for the STANDBY → ACTIVE turn-on.
 *
 *  The WHO_AM_I check aborts on a mismatch (exceptions are disabled on every
 *  C++ target in this repo, so abort() is the failure convention used by the
 *  other C++ chip drivers — see BMA150 and MCP9808). Construct the driver
 *  only once the bus is up.
 *
 *  @param connection Configured RegisterConnection (I²C, SMBus, or SPI) pointing
 *                    at the device (I²C address 0x1C or 0x1D).
 */
class MMA8451QMinimal {
public:
    explicit MMA8451QMinimal(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration.
     *
     *  Burst-reads the six data bytes (0x01..0x06) so the X, Y, Z samples
     *  come from a single measurement. In 14-bit mode the 16-bit MSB:LSB
     *  pair per axis is right-aligned by two bits (the LSB register's bits
     *  1:0 are always zero); in 8-bit fast-read mode only the three MSB
     *  registers are read.
     *
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read(float& x, float& y, float& z);

protected:
    // Register map.
    static constexpr uint8_t REG_STATUS       = 0x00;  // STATUS / F_STATUS
    static constexpr uint8_t REG_OUT_X_MSB    = 0x01;
    static constexpr uint8_t REG_OUT_Y_MSB    = 0x03;
    static constexpr uint8_t REG_OUT_Z_MSB    = 0x05;
    static constexpr uint8_t REG_F_SETUP      = 0x09;
    static constexpr uint8_t REG_TRIG_CFG     = 0x0A;
    static constexpr uint8_t REG_SYSMOD       = 0x0B;
    static constexpr uint8_t REG_INT_SOURCE   = 0x0C;
    static constexpr uint8_t REG_WHO_AM_I     = 0x0D;
    static constexpr uint8_t REG_XYZ_DATA_CFG = 0x0E;
    static constexpr uint8_t REG_HP_FILTER    = 0x0F;
    static constexpr uint8_t REG_PL_STATUS    = 0x10;
    static constexpr uint8_t REG_PL_CFG       = 0x11;
    static constexpr uint8_t REG_PL_COUNT     = 0x12;
    static constexpr uint8_t REG_PL_BF_ZCOMP  = 0x13;
    static constexpr uint8_t REG_P_L_THS      = 0x14;
    static constexpr uint8_t REG_FF_MT_CFG    = 0x15;
    static constexpr uint8_t REG_FF_MT_SRC    = 0x16;
    static constexpr uint8_t REG_FF_MT_THS    = 0x17;
    static constexpr uint8_t REG_FF_MT_COUNT  = 0x18;
    static constexpr uint8_t REG_TRANSIENT_CFG  = 0x1D;
    static constexpr uint8_t REG_TRANSIENT_SRC  = 0x1E;
    static constexpr uint8_t REG_TRANSIENT_THS  = 0x1F;
    static constexpr uint8_t REG_TRANSIENT_COUNT = 0x20;
    static constexpr uint8_t REG_PULSE_CFG    = 0x21;
    static constexpr uint8_t REG_PULSE_SRC    = 0x22;
    static constexpr uint8_t REG_PULSE_THSX   = 0x23;
    static constexpr uint8_t REG_PULSE_THSY   = 0x24;
    static constexpr uint8_t REG_PULSE_THSZ   = 0x25;
    static constexpr uint8_t REG_PULSE_TMLT   = 0x26;
    static constexpr uint8_t REG_PULSE_LTCY   = 0x27;
    static constexpr uint8_t REG_PULSE_WIND   = 0x28;
    static constexpr uint8_t REG_ASLP_COUNT   = 0x29;
    static constexpr uint8_t REG_CTRL_REG1    = 0x2A;
    static constexpr uint8_t REG_CTRL_REG2    = 0x2B;
    static constexpr uint8_t REG_CTRL_REG3    = 0x2C;
    static constexpr uint8_t REG_CTRL_REG4    = 0x2D;
    static constexpr uint8_t REG_CTRL_REG5    = 0x2E;
    static constexpr uint8_t REG_OFF_X        = 0x2F;
    static constexpr uint8_t REG_OFF_Y        = 0x30;
    static constexpr uint8_t REG_OFF_Z        = 0x31;

    /** @brief Expected WHO_AM_I value. */
    static constexpr uint8_t WHO_AM_I_VALUE = 0x1A;

    // CTRL_REG1 (0x2A) bit positions.
    static constexpr uint8_t CTRL1_ASLP_RATE_SHIFT = 6;
    static constexpr uint8_t CTRL1_ASLP_RATE_MASK  = 0xC0;
    static constexpr uint8_t CTRL1_DR_SHIFT        = 3;
    static constexpr uint8_t CTRL1_DR_MASK         = 0x38;
    static constexpr uint8_t CTRL1_LNOISE          = 0x04;
    static constexpr uint8_t CTRL1_F_READ          = 0x02;
    static constexpr uint8_t CTRL1_ACTIVE          = 0x01;

    // CTRL_REG2 (0x2B) bit positions.
    static constexpr uint8_t CTRL2_ST    = 0x80;
    static constexpr uint8_t CTRL2_RST   = 0x40;
    static constexpr uint8_t CTRL2_SMODS_SHIFT = 3;
    static constexpr uint8_t CTRL2_SMODS_MASK  = 0x18;
    static constexpr uint8_t CTRL2_SLPE  = 0x04;
    static constexpr uint8_t CTRL2_MODS_SHIFT = 0;
    static constexpr uint8_t CTRL2_MODS_MASK  = 0x03;

    // CTRL_REG3 (0x2C) bit positions.
    static constexpr uint8_t CTRL3_FIFO_GATE  = 0x80;
    static constexpr uint8_t CTRL3_WAKE_TRANS = 0x40;
    static constexpr uint8_t CTRL3_WAKE_LNDPRT = 0x20;
    static constexpr uint8_t CTRL3_WAKE_PULSE = 0x10;
    static constexpr uint8_t CTRL3_WAKE_FF_MT = 0x08;
    static constexpr uint8_t CTRL3_IPOL       = 0x02;
    static constexpr uint8_t CTRL3_PP_OD      = 0x01;

    // STATUS (0x00) bits.
    static constexpr uint8_t STATUS_ZYXDR = 0x08;

    // PL_STATUS (0x10) bits.
    static constexpr uint8_t PL_STATUS_NEWLP = 0x80;
    static constexpr uint8_t PL_STATUS_LO    = 0x40;
    static constexpr uint8_t PL_STATUS_LAPO_MASK = 0x06;
    static constexpr uint8_t PL_STATUS_LAPO_SHIFT = 1;
    static constexpr uint8_t PL_STATUS_BAFRO = 0x01;

    // FF_MT_SRC (0x16) / TRANSIENT_SRC (0x1E) / PULSE_SRC (0x22) event bits.
    static constexpr uint8_t SRC_EVENT_ACTIVE = 0x80;

    // Sensitivity (counts/g) in 14-bit mode.
    static constexpr float SCALE_2G_14BIT = 4096.0f;
    static constexpr float SCALE_4G_14BIT = 2048.0f;
    static constexpr float SCALE_8G_14BIT = 1024.0f;
    // Sensitivity (counts/g) in 8-bit fast-read mode.
    static constexpr float SCALE_2G_8BIT = 64.0f;
    static constexpr float SCALE_4G_8BIT = 32.0f;
    static constexpr float SCALE_8G_8BIT = 16.0f;

    /** @brief Threshold LSB: 0.063 g per code (FF_MT_THS, TRANSIENT_THS, PULSE_THS*). */
    static constexpr float THRESHOLD_STEP_G = 0.063f;
    /** @brief Offset LSB: 2 mg per code (OFF_X / OFF_Y / OFF_Z). */
    static constexpr float OFFSET_STEP_G = 0.002f;

    // FIFO modes (F_SETUP.F_MODE).
    /** @brief FIFO disabled. */
    static constexpr uint8_t FIFO_DISABLED = 0;

    RegisterConnection& _connection;

    // Cached configuration, kept in sync with the chip's registers.
    uint8_t _range_g    = 2;      // 2, 4 or 8
    float   _odr_hz     = 100.0f; // 800 … 1.56
    uint8_t _oversample = 0;      // MODS: 0 normal, 1 low-noise low-power,
                                  // 2 high resolution, 3 low power
    uint8_t _sleep_oversample = 0;  // SMODS
    bool    _low_noise  = false;  // LNOISE
    bool    _fast_read  = false;  // F_READ (8-bit mode)
    uint8_t _fifo_mode  = 0;      // F_MODE

    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void    _read_burst(uint8_t reg, uint8_t* buf, size_t len);
    void    _delay_ms(uint32_t ms);

    /** @brief Counts per *g* at the current range and resolution. */
    float _scale() const;

    /** @brief Turn-on time in ms (2/ODR + 2 ms). */
    uint32_t _turn_on_ms() const;

    /** @brief Decode one axis from a raw MSB/LSB pair (14-bit mode). */
    static int16_t _decode_14bit(uint8_t msb, uint8_t lsb);

    /** @brief Decode one axis from a raw MSB byte (8-bit mode). */
    static int8_t _decode_8bit(uint8_t msb);

    /** @brief Read the raw axis counts at the current resolution. */
    void _read_counts(int16_t& x, int16_t& y, int16_t& z);
};

/** @brief MMA8451Q full interface — extends MMA8451QMinimal with the complete
 *  chip API: range / data-rate / oversampling configuration, 8-bit fast read,
 *  low-noise mode, high-pass filtered output, per-axis offsets, the 32-sample
 *  FIFO, the freefall/motion, transient, tap and orientation detectors,
 *  Auto-SLEEP / Auto-WAKE, interrupt routing and the Level-3 interrupt API.
 *
 *  Most configuration registers may only be written while the chip is in
 *  STANDBY (CTRL_REG1.ACTIVE = 0). The driver handles that internally: it
 *  reads CTRL_REG1, clears ACTIVE, writes the target register(s), restores
 *  ACTIVE and waits the turn-on time (2/ODR + 2 ms). The caller never has to
 *  manage the mode.
 *
 *  @param connection Configured RegisterConnection (I²C, SMBus, or SPI) pointing
 *                    at the device (I²C address 0x1C or 0x1D).
 */
class MMA8451QFull : public MMA8451QMinimal {
public:
    // Interrupt source bits — the bit position in INT_SOURCE / CTRL_REG4 /
    // CTRL_REG5 for each of the chip's seven sources.
    /** @brief New X/Y/Z sample available (INT_SOURCE bit 0). */
    static constexpr uint8_t SOURCE_DATA_READY  = 0x01;
    /** @brief Freefall / motion detected (INT_SOURCE bit 2). */
    static constexpr uint8_t SOURCE_FF_MT       = 0x04;
    /** @brief Single / double tap detected (INT_SOURCE bit 3). */
    static constexpr uint8_t SOURCE_PULSE       = 0x08;
    /** @brief Orientation change detected (INT_SOURCE bit 4). */
    static constexpr uint8_t SOURCE_ORIENTATION = 0x10;
    /** @brief Transient (high-pass filtered motion) detected (INT_SOURCE bit 5). */
    static constexpr uint8_t SOURCE_TRANSIENT   = 0x20;
    /** @brief FIFO watermark reached or overflow (INT_SOURCE bit 6). */
    static constexpr uint8_t SOURCE_FIFO        = 0x40;
    /** @brief Auto-SLEEP / Auto-WAKE transition (INT_SOURCE bit 7). */
    static constexpr uint8_t SOURCE_AUTO_SLEEP  = 0x80;

    // Oversampling modes (CTRL_REG2.MODS / SMODS).
    /** @brief Normal oversampling mode. */
    static constexpr uint8_t OVERSAMPLE_NORMAL             = 0;
    /** @brief Low-noise low-power oversampling mode. */
    static constexpr uint8_t OVERSAMPLE_LOW_NOISE_LOW_POWER = 1;
    /** @brief High-resolution oversampling mode. */
    static constexpr uint8_t OVERSAMPLE_HIGH_RESOLUTION    = 2;
    /** @brief Low-power oversampling mode. */
    static constexpr uint8_t OVERSAMPLE_LOW_POWER          = 3;

    // FIFO modes (F_SETUP.F_MODE) — inherited from MMA8451QMinimal.
    /** @brief FIFO circular buffer (oldest sample discarded). */
    static constexpr uint8_t FIFO_CIRCULAR  = 1;
    /** @brief FIFO fill mode (stops accepting samples when full). */
    static constexpr uint8_t FIFO_FILL     = 2;
    /** @brief FIFO trigger mode. */
    static constexpr uint8_t FIFO_TRIGGER  = 3;

    // Orientation decoding (PL_STATUS.LAPO).
    /** @brief Portrait, up side up. */
    static constexpr uint8_t ORIENTATION_PORTRAIT_UP       = 0;
    /** @brief Portrait, up side down. */
    static constexpr uint8_t ORIENTATION_PORTRAIT_DOWN     = 1;
    /** @brief Landscape, right side down. */
    static constexpr uint8_t ORIENTATION_LANDSCAPE_RIGHT   = 2;
    /** @brief Landscape, left side down. */
    static constexpr uint8_t ORIENTATION_LANDSCAPE_LEFT    = 3;

    /** @brief Maximum number of samples the FIFO can hold. */
    static constexpr uint8_t FIFO_CAPACITY = 32;

    explicit MMA8451QFull(RegisterConnection& connection);

    /** @brief Read 3-axis linear acceleration in *g*. Delegates to MMA8451QMinimal::read. */
    void read(float& x, float& y, float& z) { MMA8451QMinimal::read(x, y, z); }

    /** @brief Set the full-scale range.
     *
     *  @param range_g 2, 4 or 8. ±8 g is rejected while low-noise mode is on
     *                 (the chip limits low-noise mode to ±4 g) — the call is
     *                 ignored in that case.
     */
    void set_range(uint8_t range_g);

    /** @brief Set the output data rate.
     *
     *  @param rate_hz 800, 400, 200, 100, 50, 12.5, 6.25 or 1.56 Hz. Values
     *                 that do not match a supported rate are ignored.
     */
    void set_data_rate(float rate_hz);

    /** @brief Set the oversampling mode.
     *
     *  @param mode      MODS: 0 normal, 1 low-noise low-power, 2 high
     *                   resolution, 3 low power.
     *  @param sleep_mode Optional SMODS value (same encoding); when negative
     *                   the SLEEP oversampling is left unchanged.
     */
    void set_oversampling(uint8_t mode, int sleep_mode = -1);

    /** @brief Enable or disable low-noise (reduced-noise) mode.
     *
     *  Low-noise mode limits the dynamic range to ±4 g. Enabling it while the
     *  range is ±8 g is refused (the range is left at ±8 g and low-noise mode
     *  stays off).
     *
     *  @param enabled True to enable LNOISE.
     */
    void set_low_noise(bool enabled);

    /** @brief Select the output resolution.
     *
     *  @param bits 14 (normal, two bytes per axis) or 8 (fast read, one byte
     *               per axis). Changing the resolution while the FIFO is
     *               enabled is refused (the chip only allows F_READ to
     *               change while F_MODE = 0).
     */
    void set_resolution(uint8_t bits);

    /** @brief Put the chip into STANDBY (CTRL_REG1.ACTIVE = 0). */
    void standby();

    /** @brief Leave STANDBY (CTRL_REG1.ACTIVE = 1) and wait the turn-on time. */
    void activate();

    /** @brief Issue a software reset.
     *
     *  Writes CTRL_REG2.RST, waits >= 1 ms and polls until the chip answers
     *  again. All registers return to their reset values and the chip ends up
     *  in STANDBY — re-run the initialization sequence afterwards.
     */
    void reset();

    /** @brief Read the raw acceleration counts at the current resolution.
     *
     *  @param x Output X counts (14-bit right-aligned, or 8-bit signed).
     *  @param y Output Y counts.
     *  @param z Output Z counts.
     */
    void read_raw(int16_t& x, int16_t& y, int16_t& z);

    /** @brief Report whether a new sample is available (STATUS.ZYXDR).
     *
     *  Not valid while the FIFO is enabled — use fifo_level() instead.
     */
    bool new_data_available();

    /** @brief Read the STATUS register byte. */
    uint8_t read_status();

    /** @brief Read the current system mode (SYSMOD bits 1:0).
     *
     *  @return 0 = STANDBY, 1 = WAKE, 2 = SLEEP.
     */
    uint8_t read_system_mode();

    /** @brief Enable or disable the high-pass-filtered output.
     *
     *  @param enabled True to route the high-pass-filtered data to the output
     *                 registers and the FIFO (XYZ_DATA_CFG.HPF_OUT).
     *  @param cutoff  SEL code 0..3. The resulting cutoff in Hz depends on
     *                 the ODR and the oversampling mode (datasheet Table 23).
     */
    void set_high_pass_output(bool enabled, uint8_t cutoff = 0);

    /** @brief Set the per-axis zero-g offsets.
     *
     *  @param x_g X offset in *g*; written as round(x_g / 0.002), clamped to
     *             −128…127 (±0.256 g).
     *  @param y_g Y offset in *g*.
     *  @param z_g Z offset in *g*.
     */
    void set_offset(float x_g, float y_g, float z_g);

    /** @brief Read the per-axis zero-g offsets.
     *
     *  @param x Output X offset in *g*.
     *  @param y Output Y offset in *g*.
     *  @param z Output Z offset in *g*.
     */
    void get_offset(float& x, float& y, float& z);

    /** @brief Configure the FIFO.
     *
     *  Changing between two enabled modes requires passing through the
     *  disabled mode, which the driver does automatically.
     *
     *  @param mode      0 disabled, 1 circular, 2 fill, 3 trigger.
     *  @param watermark Watermark / pre-trigger sample count (0 disables the
     *                  watermark flag).
     */
    void set_fifo(uint8_t mode, uint8_t watermark = 0);

    /** @brief Select the events that trigger the FIFO.
     *
     *  @param transient  Transient detector triggers the FIFO (TRIG_CFG bit 5).
     *  @param orientation Orientation detector triggers the FIFO (bit 4).
     *  @param pulse      Pulse detector triggers the FIFO (bit 3).
     *  @param motion     Freefall / motion detector triggers the FIFO (bit 2).
     */
    void set_fifo_trigger(bool transient = false, bool orientation = false,
                          bool pulse = false, bool motion = false);

    /** @brief Read the number of samples currently in the FIFO (F_CNT, 0..32). */
    uint8_t fifo_level();

    /** @brief Read the decoded FIFO status.
     *
     *  Reading F_STATUS clears SRC_FIFO in INT_SOURCE.
     *
     *  @param overflow  Output: true when the FIFO overflowed.
     *  @param watermark Output: true when the watermark was reached.
     *  @param count     Output: number of samples in the FIFO (0..32).
     */
    void read_fifo_status(bool& overflow, bool& watermark, uint8_t& count);

    /** @brief Drain the FIFO.
     *
     *  Each sample is read with its own transaction starting at OUT_X_MSB, so
     *  the driver never relies on the auto-increment pointer behaviour across
     *  samples.
     *
     *  @param out       Output buffer; three floats per sample (x, y, z in *g*).
     *  @param max_in    Maximum number of samples to read (capacity of @p out
     *                   divided by three).
     *  @return Number of samples written to @p out.
     */
    uint8_t read_fifo(float* out, uint8_t max_in);

    /** @brief Configure the freefall / motion detector.
     *
     *  @param motion        true = motion detection (any enabled axis over
     *                       the threshold), false = freefall (all enabled
     *                       axes under the threshold).
     *  @param axes          Axis mask: X = 1, Y = 2, Z = 4.
     *  @param threshold_g   Threshold in *g* (0.063 g per code, valid to 8 g
     *                       regardless of the selected full-scale range).
     *  @param debounce_ms   Debounce time in ms; converted with the ODR- and
     *                       oversampling-dependent debounce step.
     *  @param latch         true to latch the event flags until
     *                       FF_MT_SRC is read.
     *  @param debounce_clear true to clear the debounce counter when the
     *                       condition is lost (FF_MT_THS.DBCNTM).
     */
    void set_freefall_motion(bool motion, uint8_t axes = 7, float threshold_g = 0.0f,
                              float debounce_ms = 0.0f, bool latch = false,
                              bool debounce_clear = false);

    /** @brief Read the FF_MT_SRC byte.
     *
     *  With the events latched (ELE = 1) reading this register clears them.
     */
    uint8_t read_motion_source();

    /** @brief Configure the transient (high-pass filtered motion) detector.
     *
     *  @param axes        Axis mask: X = 1, Y = 2, Z = 4.
     *  @param threshold_g Threshold in *g* (0.063 g per code).
     *  @param debounce_ms Debounce time in ms.
     *  @param bypass_hpf  true to bypass the high-pass filter, which turns
     *                     the detector into a motion detector.
     *  @param latch       true to latch the event flags until TRANSIENT_SRC
     *                     is read.
     */
    void set_transient(uint8_t axes = 7, float threshold_g = 0.0f,
                       float debounce_ms = 0.0f, bool bypass_hpf = false,
                       bool latch = false);

    /** @brief Read the TRANSIENT_SRC byte; reading it clears the interrupt. */
    uint8_t read_transient_source();

    /** @brief Configure the pulse (tap) detector.
     *
     *  @param single        Axis mask for single-tap detection (X = 1, Y = 2, Z = 4).
     *  @param double_tap    Axis mask for double-tap detection.
     *  @param threshold_x_g X threshold in *g* (0.063 g per code).
     *  @param threshold_y_g Y threshold in *g*.
     *  @param threshold_z_g Z threshold in *g*.
     *  @param time_limit_ms Pulse time limit in ms.
     *  @param latency_ms    Pulse latency in ms.
     *  @param window_ms     Second-pulse window in ms.
     *  @param latch         true to latch the event flags until PULSE_SRC is read.
     *  @param double_abort  true to abort double-tap detection when a pulse
     *                       starts inside the latency window.
     *  @param lpf           true to enable the pulse low-pass filter.
     *  @param hpf           true to enable the pulse high-pass filter.
     */
    void set_pulse(uint8_t single = 0, uint8_t double_tap = 0,
                   float threshold_x_g = 0.5f, float threshold_y_g = 0.5f,
                   float threshold_z_g = 0.5f, float time_limit_ms = 30.0f,
                   float latency_ms = 100.0f, float window_ms = 300.0f,
                   bool latch = false, bool double_abort = false,
                   bool lpf = true, bool hpf = true);

    /** @brief Read the PULSE_SRC byte; reading it clears the interrupt. */
    uint8_t read_pulse_source();

    /** @brief Configure the portrait / landscape orientation detector.
     *
     *  @param enabled      true to enable the detector (PL_CFG.PL_EN).
     *  @param debounce_ms  Debounce time in ms.
     *  @param threshold_deg Trip angle; one of 15, 20, 30, 35, 40, 45, 55,
     *                      60, 70 or 75 degrees. Other values are ignored.
     *  @param hysteresis   Hysteresis code 0..7 (0, ±4, ±7, ±11, ±14, ±17,
     *                      ±21, ±24 degrees). THS + HYS must stay in 1..31,
     *                      otherwise the call is ignored.
     *  @param z_lock       Z-lock angle code 0..7 (14, 18, 21, 25, 29, 33, 37,
     *                      42 degrees).
     *  @param back_front   Back/front angle code 0..3.
     */
    void set_orientation(bool enabled = true, float debounce_ms = 0.0f,
                         uint8_t threshold_deg = 45, uint8_t hysteresis = 4,
                         uint8_t z_lock = 4, uint8_t back_front = 1);

    /** @brief Read the decoded orientation status.
     *
     *  Reading PL_STATUS clears NEWLP.
     *
     *  @param changed           Output: true when the orientation changed.
     *  @param lockout           Output: true while the orientation is frozen.
     *  @param portrait_landscape Output: 0 portrait up, 1 portrait down,
     *                           2 landscape right, 3 landscape left.
     *  @param back              Output: true when the device is face down.
     */
    void read_orientation(bool& changed, bool& lockout,
                          uint8_t& portrait_landscape, bool& back);

    /** @brief Configure Auto-SLEEP / Auto-WAKE.
     *
     *  @param enabled      true to enable Auto-SLEEP (CTRL_REG2.SLPE).
     *  @param sleep_rate_hz Sleep-mode ODR: 50, 12.5, 6.25 or 1.56 Hz.
     *  @param timeout_s    Inactivity timeout in seconds (0…81 s, or 0…162 s
     *                      at 1.56 Hz).
     *  @param wake_sources Mask of the sources that may wake the chip:
     *                      SOURCE_TRANSIENT, SOURCE_ORIENTATION, SOURCE_PULSE
     *                      and SOURCE_FF_MT.
     */
    void set_auto_sleep(bool enabled, float sleep_rate_hz = 50.0f,
                        float timeout_s = 10.0f, uint8_t wake_sources = 0);

    /** @brief Enable or disable FIFO_GATE (block FIFO input around sleep/wake). */
    void set_fifo_gate(bool enabled);

    /** @brief Configure the interrupt output pins.
     *
     *  @param active_high true for active-high interrupts (IPOL = 1).
     *  @param open_drain  true for open-drain outputs (PP_OD = 1), which
     *                     allows several interrupt lines to be wired-ORed.
     */
    void set_interrupt_pins(bool active_high = false, bool open_drain = false);

    /** @brief Enable one interrupt source and route it to an interrupt pin.
     *
     *  @param source One of SOURCE_DATA_READY, SOURCE_FF_MT, SOURCE_PULSE,
     *                SOURCE_ORIENTATION, SOURCE_TRANSIENT, SOURCE_FIFO or
     *                SOURCE_AUTO_SLEEP.
     *  @param pin    1 for INT1, 2 for INT2.
     */
    void enable_interrupt(uint8_t source, uint8_t pin = 1);

    /** @brief Disable one interrupt source.
     *
     *  @param source One of the SOURCE_* constants.
     */
    void disable_interrupt(uint8_t source);

    /** @brief Subscribe to interrupt edges.
     *
     *  The edge direction follows the configured polarity (falling for the
     *  active-low default, rising for active-high); call
     *  set_interrupt_pins() first.
     *
     *  Requires an InputPin — the one passed here, or the connection's INT
     *  pin. When neither is available the call is a no-op and the caller has
     *  to poll pollInterrupt() instead.
     *
     *  @param callback Called with the interrupt status byte on every edge.
     *  @param pin      1 for INT1, 2 for INT2.
     *  @param intPin   Optional InputPin overriding connection.intPin().
     */
    void onInterrupt(void (*callback)(uint8_t status), uint8_t pin = 1,
                     InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges on @p pin. */
    void offInterrupt(uint8_t pin = 1);

    /** @brief Read the interrupt status and clear the latched source flags.
     *
     *  Reads INT_SOURCE. For every set bit except SOURCE_DATA_READY the
     *  matching source register is read as well, so that the flag (and the
     *  interrupt pin) is cleared. SOURCE_DATA_READY stays set — read the
     *  X/Y/Z data to clear it.
     *
     *  @return The INT_SOURCE byte.
     */
    uint8_t pollInterrupt();

    /** @brief Alias for onInterrupt() matching the snake_case chip-spec API. */
    void on_interrupt(void (*callback)(uint8_t status), uint8_t pin = 1,
                      InputPin* intPin = nullptr) {
        onInterrupt(callback, pin, intPin);
    }

    /** @brief Alias for offInterrupt() matching the snake_case chip-spec API. */
    void off_interrupt(uint8_t pin = 1) { offInterrupt(pin); }

    /** @brief Alias for pollInterrupt() matching the snake_case chip-spec API. */
    uint8_t poll_interrupt() { return pollInterrupt(); }

    /** @brief Run the electrostatic self-test.
     *
     *  Puts the device into STANDBY, configures ±4 g at 100 Hz with normal
     *  oversampling, averages a few samples, enables CTRL_REG2.ST, waits
     *  >= 2/ODR, averages again and computes the per-axis difference in
     *  14-bit counts. The previous configuration is restored afterwards.
     *
     *  @param ok    Output: true when every axis moved by more than half of
     *               the datasheet's typical self-test delta.
     *  @param dx    Output: X delta in counts.
     *  @param dy    Output: Y delta in counts.
     *  @param dz    Output: Z delta in counts.
     */
    void self_test(bool& ok, int16_t& dx, int16_t& dy, int16_t& dz);

private:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    InputPin* _intPinUsed2 = nullptr;

    static MMA8451QFull* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();

    // Cached interrupt state.
    uint8_t _enabled_sources = 0;

    /** @brief Write a STANDBY-only register, handling the mode switch. */
    void _write_standby_reg(uint8_t reg, uint8_t value);

    /** @brief Write several STANDBY-only registers in one mode switch. */
    void _write_standby_regs(const uint8_t* regs, const uint8_t* values, uint8_t count);

    /** @brief Debounce step in ms for the current ODR and oversampling mode. */
    float _debounce_step_ms() const;

    /** @brief Pulse time-limit step in ms for the current ODR/mode (lpf on). */
    float _pulse_ttmlt_step_ms() const;

    /** @brief Pulse latency / window step in ms for the current ODR/mode (lpf on). */
    float _pulse_ltcy_step_ms() const;

    /** @brief Convert a threshold in *g* to the chip's 7-bit threshold code. */
    static uint8_t _threshold_code(float threshold_g);

    /** @brief Convert an offset in *g* to the chip's signed 8-bit offset code. */
    static uint8_t _offset_code(float offset_g);

    /** @brief Convert a debounce time in ms to the chip's 8-bit counter. */
    uint8_t _debounce_count(float debounce_ms) const;

    /** @brief Convert a time in ms to the chip's 8-bit pulse counter. */
    uint8_t _pulse_count(float time_ms, float step_ms) const;

    /** @brief Look up the P_L_THS code for a trip angle; 0xFF when unsupported. */
    static uint8_t _orientation_ths_code(uint8_t degrees);

    /** @brief Average a few samples in 14-bit counts. */
    void _average_counts(int16_t& x, int16_t& y, int16_t& z, uint8_t samples);
};
