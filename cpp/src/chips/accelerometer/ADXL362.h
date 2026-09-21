#pragma once
#include <stdint.h>
#include <stddef.h>
#include <math.h>
#include "../../connection/Connection.h"

/** @brief ADXL362 3-axis MEMS accelerometer — minimal interface (SPI).
 *
 *  Reads X, Y, Z acceleration in *g* with sensible defaults; no
 *  configuration is required beyond the connection. The ADXL362 is
 *  SPI-only — there is no I²C mode.
 *
 *  Default configuration (baked in at construction):
 *      - ±2 g measurement range
 *      - 100 Hz output data rate, ODR/4 antialiasing bandwidth
 *      - Normal noise mode (POWER_CTL.LOW_NOISE=00)
 *      - Continuous measurement mode (POWER_CTL.MEASURE=10)
 *      - FIFO disabled
 *      - No interrupts mapped; INT1/INT2 high-impedance
 *
 *  @param connection Configured SPI connection pointing at the device.
 */
class ADXL362Minimal {
public:
    explicit ADXL362Minimal(Connection& connection);

    /** @brief Run the chip's full power-up sequence.
     *
     *  Verifies DEVID_AD=0xAD, DEVID_MST=0x1D, PARTID=0xF2 (the latter is
     *  362 in octal); writes the reset FILTER_CTL and switches
     *  POWER_CTL into measurement mode.
     */
    void init();

    /** @brief Read 3-axis linear acceleration.
     *
     *  Burst-reads the 12-bit XDATA_L/H, YDATA_L/H, ZDATA_L/H sextet so
     *  the X, Y, Z samples come from a single measurement.
     *
     *  @param x Output X acceleration in *g*.
     *  @param y Output Y acceleration in *g*.
     *  @param z Output Z acceleration in *g*.
     */
    void read(float& x, float& y, float& z);

protected:
    // SPI command bytes (per spec § Transport Configuration / SPI).
    static constexpr uint8_t CMD_WRITE_REG = 0x0A;
    static constexpr uint8_t CMD_READ_REG  = 0x0B;
    static constexpr uint8_t CMD_READ_FIFO = 0x0D;

    // Soft-reset key (ASCII 'R').
    static constexpr uint8_t SOFT_RESET_KEY = 0x52;

    // Per-range sensitivity (g/LSB), typical. ±8 g is intentionally not 4× ±2 g.
    static constexpr float SENSITIVITY_G_PER_LSB[3] = { 0.001f, 0.002f, 0.004255f };

    // Temperature conversion (typical): bias=350 LSB @25 °C, sensitivity=0.065 °C/LSB.
    static constexpr int   TEMP_BIAS_LSB = 350;
    static constexpr float TEMP_BIAS_C   = 25.0f;
    static constexpr float TEMP_SCALE_C  = 0.065f;

    // Register map (6-bit addresses).
    static constexpr uint8_t REG_DEVID_AD        = 0x00;
    static constexpr uint8_t REG_DEVID_MST       = 0x01;
    static constexpr uint8_t REG_PARTID          = 0x02;
    static constexpr uint8_t REG_REVID           = 0x03;
    static constexpr uint8_t REG_XDATA           = 0x08;
    static constexpr uint8_t REG_YDATA           = 0x09;
    static constexpr uint8_t REG_ZDATA           = 0x0A;
    static constexpr uint8_t REG_STATUS          = 0x0B;
    static constexpr uint8_t REG_FIFO_ENTRIES_L  = 0x0C;
    static constexpr uint8_t REG_FIFO_ENTRIES_H  = 0x0D;
    static constexpr uint8_t REG_XDATA_L         = 0x0E;
    static constexpr uint8_t REG_XDATA_H         = 0x0F;
    static constexpr uint8_t REG_YDATA_L         = 0x10;
    static constexpr uint8_t REG_YDATA_H         = 0x11;
    static constexpr uint8_t REG_ZDATA_L         = 0x12;
    static constexpr uint8_t REG_ZDATA_H         = 0x13;
    static constexpr uint8_t REG_TEMP_L          = 0x14;
    static constexpr uint8_t REG_TEMP_H          = 0x15;
    static constexpr uint8_t REG_SOFT_RESET      = 0x1F;
    static constexpr uint8_t REG_THRESH_ACT_L    = 0x20;
    static constexpr uint8_t REG_THRESH_ACT_H    = 0x21;
    static constexpr uint8_t REG_TIME_ACT        = 0x22;
    static constexpr uint8_t REG_THRESH_INACT_L  = 0x23;
    static constexpr uint8_t REG_THRESH_INACT_H  = 0x24;
    static constexpr uint8_t REG_TIME_INACT_L    = 0x25;
    static constexpr uint8_t REG_TIME_INACT_H    = 0x26;
    static constexpr uint8_t REG_ACT_INACT_CTL   = 0x27;
    static constexpr uint8_t REG_FIFO_CONTROL    = 0x28;
    static constexpr uint8_t REG_FIFO_SAMPLES    = 0x29;
    static constexpr uint8_t REG_INTMAP1         = 0x2A;
    static constexpr uint8_t REG_INTMAP2         = 0x2B;
    static constexpr uint8_t REG_FILTER_CTL      = 0x2C;
    static constexpr uint8_t REG_POWER_CTL       = 0x2D;
    static constexpr uint8_t REG_SELF_TEST       = 0x2E;

    static constexpr uint8_t DEVID_AD_VALUE  = 0xAD;
    static constexpr uint8_t DEVID_MST_VALUE = 0x1D;
    static constexpr uint8_t PARTID_VALUE    = 0xF2;

    // FILTER_CTL reset value: RANGE=±2 g, HALF_BW=1, ODR=100 Hz.
    static constexpr uint8_t FILTER_CTL_DEFAULT = 0x13;
    // POWER_CTL measurement-mode value: MEASURE=10.
    static constexpr uint8_t POWER_CTL_MEASURE  = 0x02;

    // STATUS bits (1 << position).
    static constexpr uint8_t STATUS_DATA_READY     = 0x01;
    static constexpr uint8_t STATUS_FIFO_READY     = 0x02;
    static constexpr uint8_t STATUS_FIFO_WATERMARK = 0x04;
    static constexpr uint8_t STATUS_FIFO_OVERRUN   = 0x08;
    static constexpr uint8_t STATUS_ACT            = 0x10;
    static constexpr uint8_t STATUS_INACT          = 0x20;
    static constexpr uint8_t STATUS_AWAKE          = 0x40;
    static constexpr uint8_t STATUS_ERR_USER_REGS  = 0x80;

    Connection& _connection;
    uint8_t     _range_bits = 0x00;  // 0=±2 g, 1=±4 g, 2=±8 g
    float       _odr_hz     = 100.0f;

    void     _write_reg(uint8_t reg, uint8_t value);
    uint8_t  _read_reg(uint8_t reg);
    void     _read_burst(uint8_t reg, uint8_t* buf, uint8_t len);
    void     _read_fifo(uint8_t* buf, uint8_t len);
    uint16_t _read_fifo_entries();
    float    _sensitivity() const;
    static int16_t _sign_extend_12(uint16_t v);
    static void    _delay_ms(unsigned long ms);
};

/** @brief ADXL362 full interface — extends ADXL362Minimal with the full chip API.
 *
 *  Adds the device-ID triple read, soft-reset, range/ODR/antialiasing/noise
 *  configuration, wake-up mode and external clock / external sample sync,
 *  8-bit low-resolution read, on-chip temperature, STATUS accessors,
 *  512-sample FIFO, activity / inactivity thresholds and timers
 *  (referenced or absolute, default/linked/loop modes), per-pin (INT1/INT2)
 *  source mapping and active-high/active-low polarity, and self-test.
 */
class ADXL362Full : public ADXL362Minimal {
public:
    // Interrupt source constants (used by set_interrupt).
    static constexpr uint8_t SOURCE_DATA_READY    = 0;
    static constexpr uint8_t SOURCE_FIFO_READY    = 1;
    static constexpr uint8_t SOURCE_FIFO_WATERMARK = 2;
    static constexpr uint8_t SOURCE_FIFO_OVERRUN  = 3;
    static constexpr uint8_t SOURCE_ACT           = 4;
    static constexpr uint8_t SOURCE_INACT         = 5;
    static constexpr uint8_t SOURCE_AWAKE         = 6;

    // Noise mode constants (POWER_CTL.LOW_NOISE[5:4]).
    static constexpr uint8_t NOISE_NORMAL   = 0;
    static constexpr uint8_t NOISE_LOW      = 1;
    static constexpr uint8_t NOISE_ULTRALOW = 2;

    // Link/loop mode constants (ACT_INACT_CTL.LINKLOOP[5:4]).
    static constexpr uint8_t LINKLOOP_DEFAULT = 0;
    static constexpr uint8_t LINKLOOP_LINKED  = 1;
    static constexpr uint8_t LINKLOOP_LOOP    = 3;

    // FIFO mode constants (FIFO_CONTROL.FIFO_MODE[1:0]).
    static constexpr uint8_t FIFO_DISABLED     = 0;
    static constexpr uint8_t FIFO_OLDEST_SAVED = 1;
    static constexpr uint8_t FIFO_STREAM       = 2;
    static constexpr uint8_t FIFO_TRIGGERED    = 3;

    // FIFO entry-axis constants (top 2 bits of each 16-bit FIFO entry).
    static constexpr uint8_t AXIS_X    = 0;
    static constexpr uint8_t AXIS_Y    = 1;
    static constexpr uint8_t AXIS_Z    = 2;
    static constexpr uint8_t AXIS_TEMP = 3;

    explicit ADXL362Full(Connection& connection);

    /** @brief Return raw device-ID bytes (DEVID_AD, DEVID_MST, PARTID, REVID). */
    void device_id(uint8_t& devid_ad, uint8_t& devid_mst, uint8_t& partid, uint8_t& revid);

    /** @brief Soft-reset the chip (writes 0x52 to SOFT_RESET, waits ≥0.5 ms). */
    void soft_reset();

    /** @brief Set the measurement range to ±2/±4/±8 g. */
    void set_range(uint8_t range_g);

    /** @brief Set the output data rate to the nearest supported value (12.5–400 Hz). */
    void set_odr(float odr_hz);

    /** @brief Set FILTER_CTL.HALF_BW (antialiasing bandwidth = ODR/4 or ODR/2). */
    void set_half_bandwidth(bool enabled);

    /** @brief Set POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise). */
    void set_noise_mode(uint8_t mode);

    /** @brief Set POWER_CTL.WAKEUP (270 nA idle mode). */
    void set_wakeup_mode(bool enabled);

    /** @brief Set POWER_CTL.AUTOSLEEP; effective only in linked/loop mode. */
    void set_autosleep(bool enabled);

    /** @brief Set POWER_CTL.EXT_CLK; INT1 is repurposed as clock input. */
    void set_external_clock(bool enabled);

    /** @brief Set FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as sync trigger input. */
    void set_external_sample_trigger(bool enabled);

    /** @brief Read 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA registers. */
    void read_8bit(float& x, float& y, float& z);

    /** @brief Read the on-chip temperature sensor (typical bias/sensitivity). */
    float temperature();

    /** @brief Read the raw STATUS register byte. */
    uint8_t status();

    /** @brief Return STATUS.AWAKE. */
    bool awake();

    /** @brief Return STATUS.DATA_READY. */
    bool data_ready();

    /** @brief Return the 10-bit FIFO entry count (0–512). */
    uint16_t fifo_entries();

    /** @brief Configure the FIFO mode, optional temperature storage, and watermark.
     *
     *  @param mode       0=disabled, 1=oldest saved, 2=stream, 3=triggered.
     *  @param store_temp True to interleave temperature samples.
     *  @param watermark  9-bit watermark value (0–511).
     */
    void configure_fifo(uint8_t mode, bool store_temp = false, uint16_t watermark = 128);

    /** @brief Read all available FIFO entries.
     *
     *  Each 16-bit entry's top two bits encode the axis (0=X, 1=Y,
     *  2=Z, 3=temperature); the low 12 bits are signed axis/temperature
     *  data, scaled with the current range.
     *
     *  @param out    Output buffer; entries are appended.
     *  @param max_in Maximum entries to write (capacity of @p out).
     *  @return Number of entries written.
     */
    uint16_t read_fifo(uint8_t* axis_out, float* value_out, uint16_t max_in);

    /** @brief Set the activity threshold in *g* (clamped to 10-bit range). */
    void set_activity_threshold(float threshold_g, bool referenced = false);

    /** @brief Set the activity-time filter (0–255 samples). */
    void set_activity_time(uint8_t samples);

    /** @brief Set the inactivity threshold in *g* (clamped to 10-bit range). */
    void set_inactivity_threshold(float threshold_g, bool referenced = false);

    /** @brief Set the inactivity-time filter (0–65535 samples). */
    void set_inactivity_time(uint16_t samples);

    /** @brief Set ACT_INACT_CTL.ACT_EN. */
    void enable_activity_detection(bool enabled);

    /** @brief Set ACT_INACT_CTL.INACT_EN. */
    void enable_inactivity_detection(bool enabled);

    /** @brief Set ACT_INACT_CTL.LINKLOOP (0=default, 1=linked, 3=loop). */
    void set_link_loop_mode(uint8_t mode);

    /** @brief Map one interrupt source to the named INT pin.
     *
     *  @param pin     1 or 2.
     *  @param source  One of SOURCE_DATA_READY/SOURCE_FIFO_READY/...
     *  @param enabled True to map, False to clear.
     */
    void set_interrupt(uint8_t pin, uint8_t source, bool enabled);

    /** @brief Set the active-low polarity for one INT pin. */
    void set_interrupt_polarity(uint8_t pin, bool active_low);

    /** @brief Enable or disable the electrostatic self-test force on all axes. */
    void self_test(bool enabled);
};