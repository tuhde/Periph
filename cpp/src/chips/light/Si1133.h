#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief Si1133 UV index and ambient light sensor (Silicon Labs) — minimal interface.
 *
 *  One call runs a forced measurement and returns the UV and white-light counts.
 *
 *  init() waits 25 ms, verifies PART_ID = 0x33, sends RESET_SW and configures channel 0 = UV
 *  (ADCMUX 0x18, HW_GAIN 9, 24-bit output), channel 1 = white (ADCMUX 0x0B, HW_GAIN 7, 24-bit),
 *  CHAN_LIST = 0x03 and IRQ_ENABLE = 0x3F (so IRQ_STATUS flags finished channels).
 *
 *  All configuration goes through the indirect parameter table (PARAM_SET / PARAM_QUERY);
 *  every command is confirmed by the RESPONSE0 counter. Results are raw ADC counts — the
 *  datasheet gives no lux or UV-index formula. INT is open-drain and must be high at power-up.
 *  I²C address 0x55 (AD pulled up) or 0x52 (AD pulled down).
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address. The
 *  constructor does no bus access.
 */
class Si1133Minimal {
public:
    static constexpr uint8_t RESP_RUNNING = 0x80; ///< RESPONSE0: sequencer running
    static constexpr uint8_t RESP_SUSPEND = 0x40; ///< RESPONSE0: suspended
    static constexpr uint8_t RESP_SLEEP   = 0x20; ///< RESPONSE0: sleeping (idle)
    static constexpr uint8_t RESP_CMD_ERR = 0x10; ///< RESPONSE0: command error

    static constexpr uint8_t MUX_SMALL_IR    = 0x00; ///< small IR photodiode
    static constexpr uint8_t MUX_MEDIUM_IR   = 0x01; ///< medium IR photodiode
    static constexpr uint8_t MUX_LARGE_IR    = 0x02; ///< large IR photodiode
    static constexpr uint8_t MUX_WHITE       = 0x0B; ///< white photodiode
    static constexpr uint8_t MUX_LARGE_WHITE = 0x0D; ///< large white photodiode
    static constexpr uint8_t MUX_UV          = 0x18; ///< UV photodiode
    static constexpr uint8_t MUX_UV_DEEP     = 0x19; ///< deep UV photodiode

    explicit Si1133Minimal(RegisterConnection& connection);

    /** @brief Verify the chip, reset it and configure the UV and white channels.
     *  @return true on success; false for a wrong PART_ID, a failed command or a bus error.
     */
    bool init();

    /** @brief Run one forced measurement and return the UV and white counts.
     *  @param[out] uv    Signed 24-bit raw count of channel 0.
     *  @param[out] white Signed 24-bit raw count of channel 1.
     *  @return false if channel 0 or 1 is disabled, a command fails, on timeout or a bus error.
     */
    bool read(int32_t& uv, int32_t& white);

protected:
    static constexpr uint8_t REG_PART_ID    = 0x00;
    static constexpr uint8_t REG_HOSTIN0    = 0x0A;
    static constexpr uint8_t REG_COMMAND    = 0x0B;
    static constexpr uint8_t REG_IRQ_ENABLE = 0x0F;
    static constexpr uint8_t REG_RESPONSE1  = 0x10;
    static constexpr uint8_t REG_RESPONSE0  = 0x11;
    static constexpr uint8_t REG_IRQ_STATUS = 0x12;
    static constexpr uint8_t REG_HOSTOUT0   = 0x13;

    static constexpr uint8_t PART_ID = 0x33;

    static constexpr uint8_t CMD_RESET_CMD_CTR = 0x00;
    static constexpr uint8_t CMD_RESET_SW      = 0x01;
    static constexpr uint8_t CMD_FORCE         = 0x11;
    static constexpr uint8_t CMD_PAUSE         = 0x12;
    static constexpr uint8_t CMD_START         = 0x13;
    static constexpr uint8_t CMD_PARAM_QUERY   = 0x40;
    static constexpr uint8_t CMD_PARAM_SET     = 0x80;

    static constexpr uint8_t PARAM_I2C_ADDR     = 0x00;
    static constexpr uint8_t PARAM_CHAN_LIST    = 0x01;
    static constexpr uint8_t PARAM_ADCCONFIG0   = 0x02;
    static constexpr uint8_t PARAM_ADCSENS0     = 0x03;
    static constexpr uint8_t PARAM_ADCPOST0     = 0x04;
    static constexpr uint8_t PARAM_MEASCONFIG0  = 0x05;
    static constexpr uint8_t PARAM_MEASRATE_H   = 0x1A;
    static constexpr uint8_t PARAM_MEASRATE_L   = 0x1B;
    static constexpr uint8_t PARAM_MEASCOUNT0   = 0x1C;
    static constexpr uint8_t PARAM_THRESHOLD0_H = 0x25;
    static constexpr uint8_t PARAM_BURST        = 0x2B;

    static constexpr unsigned CMD_POLLS     = 50;     ///< 1 ms each
    static constexpr unsigned MEASURE_POLLS = 15000;  ///< 1 ms each

    RegisterConnection& _connection;
    uint8_t _chan_list = 0;       ///< shadow of CHAN_LIST
    uint8_t _bits24 = 0;          ///< bit c = channel c has 24-bit output
    uint8_t _irq_enable = 0;      ///< shadow of IRQ_ENABLE

    bool _read_reg(uint8_t reg, uint8_t& value);
    bool _write_reg(uint8_t reg, uint8_t value);
    bool _software_reset();
    bool _apply_defaults();
    bool _counter(uint8_t& counter);
    bool _wait_counter(uint8_t before);
    bool _command(uint8_t cmd);
    bool _param_set(uint8_t address, uint8_t value);
    bool _param_query(uint8_t address, uint8_t& value);
    bool _set_channel(uint8_t channel, uint8_t adcmux, uint8_t hw_gain, uint8_t sw_gain,
                      uint8_t decim_rate, bool high_signal, bool bits24, uint8_t postshift);
    bool _read_outputs(int32_t* values, size_t& count);
    bool _measure(int32_t* values, size_t& count);
};

/** @brief Si1133 full interface — extends Si1133Minimal with per-channel configuration,
 *  single-photodiode reads, autonomous and burst modes, thresholds, direct parameter access,
 *  the I²C address and the INT interrupt.
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address.
 */
class Si1133Full : public Si1133Minimal {
public:
    static constexpr uint8_t SOURCE_CHANNEL0 = 0x01; ///< channel 0 result ready
    static constexpr uint8_t SOURCE_CHANNEL1 = 0x02; ///< channel 1 result ready
    static constexpr uint8_t SOURCE_CHANNEL2 = 0x04; ///< channel 2 result ready
    static constexpr uint8_t SOURCE_CHANNEL3 = 0x08; ///< channel 3 result ready
    static constexpr uint8_t SOURCE_CHANNEL4 = 0x10; ///< channel 4 result ready
    static constexpr uint8_t SOURCE_CHANNEL5 = 0x20; ///< channel 5 result ready

    explicit Si1133Full(RegisterConnection& connection);

    /** @brief Read PART_ID, HW_ID and REV_ID.
     *  @param[out] part_id 0x33.
     *  @param[out] hw_id   Bits 4:0 implementation (0x03 = Si1133-AA00), bits 7:5 silicon revision.
     *  @param[out] rev_id  Product revision.
     */
    bool read_id(uint8_t& part_id, uint8_t& hw_id, uint8_t& rev_id);

    /** @brief Configure one channel (ADCCONFIG, ADCSENS, ADCPOST); clears its threshold select.
     *  @param channel     Channel 0–5.
     *  @param adcmux      Photodiode (MUX_* constant).
     *  @param hw_gain     Integration time code 0–11 (48.8 µs × 2^n at decimation 0).
     *  @param sw_gain     Accumulate 2^n samples, n = 0–7.
     *  @param decim_rate  Decimation rate 0–3.
     *  @param high_signal Divide the sensitivity by 14.5 (direct sunlight).
     *  @param bits24      24-bit signed output (true) or 16-bit unsigned.
     *  @param postshift   Right shift 0–7 of the accumulated result.
     *  @return false for out-of-range values or a failed command.
     */
    bool configure_channel(uint8_t channel, uint8_t adcmux, uint8_t hw_gain = 0,
                           uint8_t sw_gain = 0, uint8_t decim_rate = 0, bool high_signal = false,
                           bool bits24 = true, uint8_t postshift = 0);

    /** @brief Select the channels that run (CHAN_LIST).
     *  @param mask Bit c enables channel c (0x00–0x3F).
     */
    bool enable_channels(uint8_t mask);

    /** @brief Read the results of all enabled channels in channel order.
     *  @param[out] values Array of at least 6 entries.
     *  @param[out] count  Number of enabled channels written to @p values.
     *  @param force       Run a forced measurement first (true) or only decode HOSTOUT.
     */
    bool read_channels(int32_t* values, size_t& count, bool force = true);

    /** @brief Measure the UV photodiode once (channel 5 as scratch, HW_GAIN 9).
     *  @param[out] value Signed 24-bit raw count.
     */
    bool read_uv(int32_t& value);

    /** @brief Measure the white photodiode once (channel 5 as scratch, HW_GAIN 7).
     *  @param[out] value Signed 24-bit raw count.
     */
    bool read_white(int32_t& value);

    /** @brief Measure an IR photodiode once (channel 5 as scratch, HW_GAIN 7).
     *  @param[out] value      Signed 24-bit raw count.
     *  @param photodiode      MUX_SMALL_IR, MUX_MEDIUM_IR or MUX_LARGE_IR.
     */
    bool read_ir(int32_t& value, uint8_t photodiode = MUX_SMALL_IR);

    /** @brief Send FORCE without waiting for the results. */
    bool force();

    /** @brief Assign a rate counter to a channel (MEASCONFIG.COUNTER_INDEX).
     *  @param channel Channel 0–5.
     *  @param index   0 = none (forced only), 1–3 = MEASCOUNT0..2.
     */
    bool set_channel_counter(uint8_t channel, uint8_t index);

    /** @brief Start autonomous measurements: MEASRATE (800 µs units), MEASCOUNT0..2, counter 1
     *  for every enabled channel without one, then START. Channel period = rate × MEASCOUNTk.
     *  @param rate_s Main period in seconds (800 µs … 52.4 s).
     *  @param count0 MEASCOUNT0 (1–255).
     *  @param count1 MEASCOUNT1 (1–255).
     *  @param count2 MEASCOUNT2 (1–255).
     */
    bool start_autonomous(float rate_s, uint8_t count0 = 1, uint8_t count1 = 1, uint8_t count2 = 1);

    /** @brief Pause autonomous measurements (PAUSE). */
    bool pause();

    /** @brief Configure burst mode (BURST).
     *  @param count Readings 1–127; 0 disables burst mode.
     */
    bool set_burst(uint8_t count);

    /** @brief Set one of the three 16-bit thresholds.
     *  @param index Threshold 0–2.
     *  @param value Threshold count.
     */
    bool set_threshold(uint8_t index, uint16_t value);

    /** @brief Select the threshold a channel's interrupt compares against (ADCPOST.THRESH_SEL).
     *  @param channel Channel 0–5.
     *  @param index   0 = off, 1–3 = THRESHOLD0..2.
     */
    bool set_channel_threshold(uint8_t channel, uint8_t index);

    /** @brief Read a parameter-table byte (PARAM_QUERY).
     *  @param address    Parameter address 0x00–0x3F.
     *  @param[out] value Parameter value.
     */
    bool read_parameter(uint8_t address, uint8_t& value);

    /** @brief Write a parameter-table byte (PARAM_SET). Writing channel parameters directly
     *  bypasses the driver's record of the channel layout; prefer configure_channel().
     *  @param address Parameter address 0x00–0x3F.
     *  @param value   Byte value.
     */
    bool write_parameter(uint8_t address, uint8_t value);

    /** @brief Decode RESPONSE0.
     *  @param[out] state   RESP_RUNNING / RESP_SUSPEND / RESP_SLEEP bits.
     *  @param[out] error   CMD_ERR flag.
     *  @param[out] counter Command counter (the error code when @p error is true).
     */
    bool read_response(uint8_t& state, bool& error, uint8_t& counter);

    /** @brief Read IRQ_STATUS (clears it).
     *  @param[out] mask Bit c set when channel c has a new result.
     */
    bool data_ready(uint8_t& mask);

    /** @brief Change the I²C address (volatile). The chip answers on the new address at once,
     *  so no completion check is made; use a new connection afterwards.
     *  @param address New 7-bit address 0x08–0x77.
     */
    bool set_i2c_address(uint8_t address);

    /** @brief Software reset (RESET_SW), wait for SLEEP, re-apply the Minimal defaults. */
    bool reset();

    /** @brief Enable INT for channels (IRQ_ENABLE).
     *  @param source SOURCE_CHANNEL* mask.
     */
    bool enable_interrupt(uint8_t source);

    /** @brief Disable INT for channels. Forced reads only wait for channels whose interrupt is
     *  enabled (none: a fixed 100 ms wait).
     *  @param source SOURCE_CHANNEL* mask.
     */
    bool disable_interrupt(uint8_t source);

    /** @brief Read IRQ_STATUS (clears it, releases INT).
     *  @return Channel mask, 0 = nothing pending or a bus error.
     */
    uint8_t poll_interrupt();

    /** @brief Subscribe to INT (falling edge); the callback receives the IRQ_STATUS mask.
     *  Without any pin (no @p intPin and none on the connection) nothing is delivered — poll
     *  with poll_interrupt().
     *  @param callback Called with the channel mask.
     *  @param intPin   Optional pin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    static constexpr uint8_t SCRATCH = 5;

    uint8_t _counter_index[6] = {0, 0, 0, 0, 0, 0};
    void (*_callback)(uint8_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static Si1133Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
    bool _scratch_read(uint8_t adcmux, uint8_t hw_gain, int32_t& value);
};
