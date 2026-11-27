#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief One Si1145 PS + ALS measurement. */
struct Si1145Reading {
    uint16_t proximity = 0;  ///< raw PS1 count (rises as an object approaches)
    float uv_index = 0.0f;   ///< UV index (AUX_DATA / 100)
    uint16_t visible = 0;    ///< raw visible ambient count
    uint16_t infrared = 0;   ///< raw infrared ambient count
};

/** @brief Raw Si1145 data registers 0x22–0x2D. */
struct Si1145Raw {
    uint16_t visible = 0;   ///< ALS_VIS_DATA
    uint16_t infrared = 0;  ///< ALS_IR_DATA
    uint16_t ps1 = 0;       ///< PS1_DATA
    uint16_t ps2 = 0;       ///< PS2_DATA (Si1146/47)
    uint16_t ps3 = 0;       ///< PS3_DATA (Si1147)
    uint16_t aux = 0;       ///< AUX_DATA (UV index × 100 while UV is enabled)
};

/** @brief Si1145 proximity / UV index / ambient light sensor (Silicon Labs) — minimal interface.
 *
 *  One call runs a forced PS + ALS measurement and returns the proximity count, the UV index
 *  and the visible/infrared counts.
 *
 *  init() waits 25 ms, verifies PART_ID ∈ {0x45, 0x46, 0x47}, writes HW_KEY = 0x17,
 *  UCOEF0..3 = 7B 6B 01 00, CHLIST = 0xB1 (UV, ALS IR, ALS VIS, PS1), PSLED12_SELECT = 0x01,
 *  PS_LED21 = 0x03 (LED1 at level 3 of 15) and IRQ_ENABLE = ALS_IE | PS1_IE (INT stays
 *  undriven until Si1145Full::enable_interrupt()).
 *
 *  Configuration is split between registers and the parameter RAM (PARAM_SET / PARAM_QUERY);
 *  every command increments the RESPONSE counter. I²C address 0x60 (older silicon: 0x5A).
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address. The
 *  constructor does no bus access.
 */
class Si1145Minimal {
public:
    static constexpr uint8_t CH_PS1     = 0x01; ///< CHLIST.EN_PS1
    static constexpr uint8_t CH_PS2     = 0x02; ///< CHLIST.EN_PS2
    static constexpr uint8_t CH_PS3     = 0x04; ///< CHLIST.EN_PS3
    static constexpr uint8_t CH_ALS_VIS = 0x10; ///< CHLIST.EN_ALS_VIS
    static constexpr uint8_t CH_ALS_IR  = 0x20; ///< CHLIST.EN_ALS_IR
    static constexpr uint8_t CH_AUX     = 0x40; ///< CHLIST.EN_AUX
    static constexpr uint8_t CH_UV      = 0x80; ///< CHLIST.EN_UV

    static constexpr uint8_t SOURCE_ALS = 0x01; ///< ALS/UV measurement complete
    static constexpr uint8_t SOURCE_PS1 = 0x04; ///< PS1 complete
    static constexpr uint8_t SOURCE_PS2 = 0x08; ///< PS2 complete (Si1146/47)
    static constexpr uint8_t SOURCE_PS3 = 0x10; ///< PS3 complete (Si1147)
    static constexpr uint8_t SOURCE_CMD = 0x20; ///< command complete

    explicit Si1145Minimal(RegisterConnection& connection);

    /** @brief Verify the chip, unlock it and configure UV, ALS and PS1.
     *  @return true on success; false for a wrong PART_ID, a failed command or a bus error.
     */
    bool init();

    /** @brief Run one forced PS + ALS measurement (PSALS_FORCE).
     *  @param[out] reading Proximity, UV index, visible and infrared values.
     *  @return false if a command fails, on timeout or a bus error.
     */
    bool read(Si1145Reading& reading);

protected:
    static constexpr uint8_t REG_PART_ID    = 0x00;
    static constexpr uint8_t REG_SEQ_ID     = 0x02;
    static constexpr uint8_t REG_INT_CFG    = 0x03;
    static constexpr uint8_t REG_IRQ_ENABLE = 0x04;
    static constexpr uint8_t REG_HW_KEY     = 0x07;
    static constexpr uint8_t REG_MEAS_RATE0 = 0x08;
    static constexpr uint8_t REG_PS_RATE    = 0x0A;
    static constexpr uint8_t REG_PS_LED21   = 0x0F;
    static constexpr uint8_t REG_PS_LED3    = 0x10;
    static constexpr uint8_t REG_UCOEF0     = 0x13;
    static constexpr uint8_t REG_PARAM_WR   = 0x17;
    static constexpr uint8_t REG_COMMAND    = 0x18;
    static constexpr uint8_t REG_RESPONSE   = 0x20;
    static constexpr uint8_t REG_IRQ_STATUS = 0x21;
    static constexpr uint8_t REG_DATA       = 0x22;
    static constexpr uint8_t REG_PARAM_RD   = 0x2E;

    static constexpr uint8_t HW_KEY = 0x17;

    static constexpr uint8_t CMD_NOP         = 0x00;
    static constexpr uint8_t CMD_RESET       = 0x01;
    static constexpr uint8_t CMD_BUSADDR     = 0x02;
    static constexpr uint8_t CMD_PS_FORCE    = 0x05;
    static constexpr uint8_t CMD_ALS_FORCE   = 0x06;
    static constexpr uint8_t CMD_PSALS_FORCE = 0x07;
    static constexpr uint8_t CMD_PSALS_PAUSE = 0x0B;
    static constexpr uint8_t CMD_PS_AUTO     = 0x0D;
    static constexpr uint8_t CMD_ALS_AUTO    = 0x0E;
    static constexpr uint8_t CMD_PSALS_AUTO  = 0x0F;
    static constexpr uint8_t CMD_GET_CAL     = 0x12;
    static constexpr uint8_t CMD_PARAM_QUERY = 0x80;
    static constexpr uint8_t CMD_PARAM_SET   = 0xA0;

    static constexpr uint8_t PARAM_I2C_ADDR       = 0x00;
    static constexpr uint8_t PARAM_CHLIST         = 0x01;
    static constexpr uint8_t PARAM_PSLED12_SELECT = 0x02;
    static constexpr uint8_t PARAM_PSLED3_SELECT  = 0x03;
    static constexpr uint8_t PARAM_PS_ENCODING    = 0x05;
    static constexpr uint8_t PARAM_ALS_ENCODING   = 0x06;
    static constexpr uint8_t PARAM_PS1_ADCMUX     = 0x07;
    static constexpr uint8_t PARAM_PS_ADC_COUNTER = 0x0A;
    static constexpr uint8_t PARAM_PS_ADC_GAIN    = 0x0B;
    static constexpr uint8_t PARAM_PS_ADC_MISC    = 0x0C;
    static constexpr uint8_t PARAM_AUX_ADCMUX     = 0x0F;
    static constexpr uint8_t PARAM_ALS_VIS_ADC_COUNTER = 0x10;
    static constexpr uint8_t PARAM_ALS_VIS_ADC_GAIN    = 0x11;
    static constexpr uint8_t PARAM_ALS_VIS_ADC_MISC    = 0x12;
    static constexpr uint8_t PARAM_ALS_IR_ADC_COUNTER  = 0x1D;
    static constexpr uint8_t PARAM_ALS_IR_ADC_GAIN     = 0x1E;
    static constexpr uint8_t PARAM_ALS_IR_ADC_MISC     = 0x1F;

    static constexpr uint8_t SOURCE_MASK = 0x3D;
    static constexpr uint8_t CH_ALS = CH_ALS_VIS | CH_ALS_IR | CH_AUX | CH_UV;

    static constexpr unsigned CMD_POLLS     = 50;    ///< 1 ms each
    static constexpr unsigned MEASURE_POLLS = 1000;  ///< 1 ms each

    RegisterConnection& _connection;
    uint8_t _part_id = 0x45;
    uint8_t _seq_id = 0;
    uint8_t _chlist = 0;      ///< shadow of CHLIST
    uint8_t _irq_enable = 0;  ///< shadow of IRQ_ENABLE

    bool _read_reg(uint8_t reg, uint8_t& value);
    bool _write_reg(uint8_t reg, uint8_t value);
    bool _apply_defaults();
    bool _counter(uint8_t& counter);
    bool _command(uint8_t cmd);
    bool _param_set(uint8_t offset, uint8_t value);
    bool _param_query(uint8_t offset, uint8_t& value);
    bool _clear_irq(uint8_t& status);
    bool _measure(bool proximity, bool als);
    bool _read_data(uint16_t* values);
};

/** @brief Si1145 full interface — extends Si1145Minimal with per-channel reads, channel list,
 *  LED and photodiode selection, gains, ranges, alignment, UV coefficients, calibration,
 *  autonomous mode, the I²C address, reset and the INT interrupt.
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address.
 */
class Si1145Full : public Si1145Minimal {
public:
    static constexpr uint8_t AUX_TEMPERATURE = 0x65; ///< AUX_ADCMUX: die temperature
    static constexpr uint8_t AUX_VDD         = 0x75; ///< AUX_ADCMUX: supply voltage

    explicit Si1145Full(RegisterConnection& connection);

    /** @brief Read PART_ID, REV_ID and SEQ_ID.
     *  @param[out] part_id 0x45 (Si1145), 0x46 (Si1146) or 0x47 (Si1147).
     *  @param[out] rev_id  Hardware revision.
     *  @param[out] seq_id  Sequencer firmware revision (0x01 swaps the MEAS_RATE bytes).
     */
    bool read_id(uint8_t& part_id, uint8_t& rev_id, uint8_t& seq_id);

    /** @brief Forced PS measurement (PS_FORCE) and one proximity channel.
     *  @param[out] value Raw proximity count.
     *  @param channel    PS channel 1–3 (Si1145: 1); must be enabled in CHLIST.
     *  @return false if the channel is not supported or not enabled, or on failure.
     */
    bool read_proximity(uint16_t& value, uint8_t channel = 1);

    /** @brief Forced ALS measurement and the UV index (AUX_DATA / 100).
     *  @param[out] value UV index.
     *  @return false if UV is not enabled, or on failure.
     */
    bool read_uv_index(float& value);

    /** @brief Forced ALS measurement and the raw visible count.
     *  @param[out] value ALS_VIS_DATA.
     */
    bool read_visible(uint16_t& value);

    /** @brief Forced ALS measurement and the raw infrared count.
     *  @param[out] value ALS_IR_DATA.
     */
    bool read_infrared(uint16_t& value);

    /** @brief Read the latest data registers without measuring.
     *  @param[out] raw Visible, infrared, PS1–PS3 and AUX values.
     */
    bool read_raw(Si1145Raw& raw);

    /** @brief Die temperature on the auxiliary channel (AUX_ADCMUX 0x65); CHLIST is switched
     *  to EN_AUX temporarily and restored.
     *  @param[out] value Raw relative temperature count.
     */
    bool read_temperature_raw(uint16_t& value);

    /** @brief Supply voltage on the auxiliary channel (AUX_ADCMUX 0x75); CHLIST restored.
     *  @param[out] value Raw VDD count.
     */
    bool read_vdd_raw(uint16_t& value);

    /** @brief Select the measured channels (CHLIST).
     *  @param uv       UV index (in AUX_DATA); excludes @p aux.
     *  @param visible  Visible ambient light.
     *  @param infrared Infrared ambient light.
     *  @param ps1      Proximity channel 1.
     *  @param ps2      Proximity channel 2 (Si1146/47).
     *  @param ps3      Proximity channel 3 (Si1147).
     *  @param aux      Auxiliary channel (AUX_ADCMUX).
     *  @return false for uv together with aux, an unsupported PS channel or a failed command.
     */
    bool set_channels(bool uv, bool visible, bool infrared, bool ps1, bool ps2 = false,
                      bool ps3 = false, bool aux = false);

    /** @brief Set an LED drive current (PS_LED21 / PS_LED3).
     *  @param led   LED 1–3 (Si1145: 1 only).
     *  @param level Current level 0 (off) … 15 (maximum, ~359 mA).
     */
    bool set_led_current(uint8_t led, uint8_t level);

    /** @brief Select the LEDs a PS channel drives (PSLED12_SELECT / PSLED3_SELECT).
     *  @param channel PS channel 1–3.
     *  @param leds    LED mask (bit 0 LED1, bit 1 LED2, bit 2 LED3), only the part's LEDs.
     */
    bool set_ps_led(uint8_t channel, uint8_t leds);

    /** @brief Select the photodiode of a PS channel (PSn_ADCMUX).
     *  @param channel PS channel 1–3.
     *  @param large   Large IR photodiode (0x03) or small (0x00).
     */
    bool set_ps_photodiode(uint8_t channel, bool large);

    /** @brief Set the ADC gains (integration time × 2^n) and the recommended recovery counts
     *  (one's complement of the gain). Do not combine a high PS gain with a high LED current.
     *  @param visible   ALS_VIS_ADC_GAIN 0–7.
     *  @param infrared  ALS_IR_ADC_GAIN 0–7.
     *  @param proximity PS_ADC_GAIN 0–7.
     */
    bool set_gain(uint8_t visible = 0, uint8_t infrared = 0, uint8_t proximity = 0);

    /** @brief Select the high signal range (sensitivity ÷ 14.5) per measurement.
     *  @param visible   VIS_RANGE.
     *  @param infrared  IR_RANGE.
     *  @param proximity PS_RANGE (PS_ADC_MODE stays 1).
     */
    bool set_high_range(bool visible, bool infrared, bool proximity);

    /** @brief Report the 16 LSBs instead of the 16 MSBs of the ADC result.
     *  @param visible  ALS_VIS_ALIGN.
     *  @param infrared ALS_IR_ALIGN.
     *  @param ps1      PS1_ALIGN.
     *  @param ps2      PS2_ALIGN.
     *  @param ps3      PS3_ALIGN.
     */
    bool set_alignment(bool visible = false, bool infrared = false, bool ps1 = false,
                       bool ps2 = false, bool ps3 = false);

    /** @brief Write UCOEF0..3 (default 7B 6B 01 00 gives the UV index × 100).
     *  @param ucoef Four coefficient bytes.
     */
    bool set_uv_coefficients(const uint8_t ucoef[4]);

    /** @brief Factory calibration (GET_CAL); overwrites the data registers.
     *  @param[out] cal 12 calibration bytes.
     */
    bool read_calibration(uint8_t cal[12]);

    /** @brief Start autonomous measurements: MEAS_RATE = round(interval / 31.25 µs) (bytes
     *  swapped when SEQ_ID = 0x01), then PS_AUTO / ALS_AUTO / PSALS_AUTO.
     *  @param interval_s Interval in seconds (31.25 µs … 2.048 s).
     *  @param proximity  Run the PS channels.
     *  @param als        Run the ALS/UV channels.
     */
    bool start_autonomous(float interval_s, bool proximity = true, bool als = true);

    /** @brief Pause the autonomous loop (PSALS_PAUSE). */
    bool pause();

    /** @brief Start a forced measurement without waiting (PS/ALS/PSALS_FORCE).
     *  @param proximity Measure the PS channels.
     *  @param als       Measure the ALS/UV channels.
     */
    bool force(bool proximity = true, bool als = true);

    /** @brief Decode RESPONSE.
     *  @param[out] counter 4-bit command counter (0 on error).
     *  @param[out] error   0 = none, 0x80 invalid setting, 0x88–0x8E ADC overflow.
     */
    bool read_response(uint8_t& counter, uint8_t& error);

    /** @brief Change the I²C address (I2C_ADDR, then BUSADDR; volatile). The chip answers on
     *  the new address at once; use a new connection afterwards.
     *  @param address New 7-bit address 0x08–0x77.
     */
    bool set_i2c_address(uint8_t address);

    /** @brief Software reset, 10 ms wait, HW_KEY and the Minimal defaults again. */
    bool reset();

    /** @brief Drive INT for sources (INT_CFG.INT_OE and IRQ_ENABLE).
     *  @param source SOURCE_* mask.
     */
    bool enable_interrupt(uint8_t source);

    /** @brief Clear sources from IRQ_ENABLE. Forced reads only wait for enabled sources
     *  (none: a fixed 100 ms wait).
     *  @param source SOURCE_* mask.
     */
    bool disable_interrupt(uint8_t source);

    /** @brief Read IRQ_STATUS and write it back to clear it (releases INT).
     *  @return Pending SOURCE_* mask among the enabled sources, 0 = none or a bus error.
     */
    uint8_t poll_interrupt();

    /** @brief Subscribe to INT (falling edge); the callback receives the SOURCE_* mask.
     *  Without any pin (no @p intPin and none on the connection) nothing is delivered — poll
     *  with poll_interrupt().
     *  @param callback Called with the source mask.
     *  @param intPin   Optional pin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    void (*_callback)(uint8_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static Si1145Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
    uint8_t _led_count() const { return (uint8_t)(_part_id - 0x44); }
    bool _read_aux(uint8_t mux, uint16_t& value);
};
