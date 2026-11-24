#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

/** @brief Si1132 UV index and ambient light sensor (Silicon Labs) — minimal interface.
 *
 * The chip is a small sequencer: configuration lives partly in registers and partly in a parameter
 * RAM written with PARAM_WR + PARAM_SET. init() waits 25 ms, verifies PART_ID = 0x32, writes
 * HW_KEY, the default UV coefficients and enables the UV, infrared and visible channels in forced
 * mode (gain 0, normal range). read() starts a forced measurement, waits for ALS_INT (timeout
 * 500 ms) and returns the UV index (AUX_DATA / 100) plus the raw 16-bit visible and infrared counts.
 *
 * @param connection Register-capable I²C connection pointing at the device (address 0x60). The
 *        constructor does no bus access.
 */
class Si1132Minimal {
public:
    /** Fixed 7-bit I²C address of the chip. */
    static constexpr uint8_t I2C_ADDRESS = 0x60;

    explicit Si1132Minimal(RegisterConnection& connection);

    /** @brief Verify PART_ID and configure forced UV + visible + infrared measurements.
     *  @return true on success; false on a bus error, a PART_ID mismatch or a command error.
     */
    bool init();

    /** @brief Run one forced measurement.
     *  @param[out] uv_index  UV index (AUX_DATA / 100).
     *  @param[out] visible   Raw 16-bit visible ambient-light count.
     *  @param[out] infrared  Raw 16-bit infrared ambient-light count.
     *  @return true on success; false on a bus error or when the measurement did not finish in time.
     */
    bool read(float& uv_index, uint16_t& visible, uint16_t& infrared);

protected:
    static constexpr uint8_t REG_PART_ID    = 0x00;
    static constexpr uint8_t REG_SEQ_ID     = 0x02;
    static constexpr uint8_t REG_INT_CFG    = 0x03;
    static constexpr uint8_t REG_IRQ_ENABLE = 0x04;
    static constexpr uint8_t REG_HW_KEY     = 0x07;
    static constexpr uint8_t REG_MEAS_RATE0 = 0x08;
    static constexpr uint8_t REG_MEAS_RATE1 = 0x09;
    static constexpr uint8_t REG_MEAS_RATE_SWAPPED = 0x0A;
    static constexpr uint8_t REG_UCOEF      = 0x13;
    static constexpr uint8_t REG_PARAM_WR   = 0x17;
    static constexpr uint8_t REG_COMMAND    = 0x18;
    static constexpr uint8_t REG_RESPONSE   = 0x20;
    static constexpr uint8_t REG_IRQ_STATUS = 0x21;
    static constexpr uint8_t REG_DATA       = 0x22;
    static constexpr uint8_t REG_PARAM_RD   = 0x2E;

    static constexpr uint8_t PART_ID_VALUE  = 0x32;
    static constexpr uint8_t HW_KEY_VALUE   = 0x17;

    static constexpr uint8_t CMD_PARAM_QUERY = 0x80;
    static constexpr uint8_t CMD_PARAM_SET   = 0xA0;
    static constexpr uint8_t CMD_NOP         = 0x00;
    static constexpr uint8_t CMD_RESET       = 0x01;
    static constexpr uint8_t CMD_BUSADDR     = 0x02;
    static constexpr uint8_t CMD_ALS_FORCE   = 0x06;
    static constexpr uint8_t CMD_ALS_PAUSE   = 0x0A;
    static constexpr uint8_t CMD_ALS_AUTO    = 0x0E;
    static constexpr uint8_t CMD_GET_CAL     = 0x12;

    static constexpr uint8_t PARAM_I2C_ADDR     = 0x00;
    static constexpr uint8_t PARAM_CHLIST       = 0x01;
    static constexpr uint8_t PARAM_ALS_ENCODING = 0x06;
    static constexpr uint8_t PARAM_AUX_ADCMUX   = 0x0F;
    static constexpr uint8_t PARAM_VIS_COUNTER  = 0x10;
    static constexpr uint8_t PARAM_VIS_GAIN     = 0x11;
    static constexpr uint8_t PARAM_VIS_MISC     = 0x12;
    static constexpr uint8_t PARAM_IR_COUNTER   = 0x1D;
    static constexpr uint8_t PARAM_IR_GAIN      = 0x1E;
    static constexpr uint8_t PARAM_IR_MISC      = 0x1F;

    static constexpr uint8_t CHLIST_ALL     = 0xB0;
    static constexpr uint8_t CHLIST_AUX     = 0x40;
    static constexpr uint8_t IRQ_ALS_INT    = 0x01;
    static constexpr uint8_t IRQ_CLEAR      = 0x21;
    static constexpr uint8_t RESP_ERROR     = 0x80;

    RegisterConnection& _connection;
    uint8_t _chlist = CHLIST_ALL;
    uint8_t _seq_id = 0;

    bool _write_reg(uint8_t reg, uint8_t value);
    bool _write_regs(uint8_t reg, const uint8_t* data, size_t len);
    bool _read_reg(uint8_t reg, uint8_t& value);
    bool _wait_response(uint8_t before);
    bool _command(uint8_t cmd);
    bool _param_set(uint8_t offset, uint8_t value);
    bool _start_measurement();
    bool _wait_ready();
    bool _read_data(uint16_t& visible, uint16_t& infrared, uint16_t& aux);
    bool _measure(uint16_t& visible, uint16_t& infrared, uint16_t& aux);
};

/** @brief Si1132 full interface — single channels, gain/range, autonomous mode, calibration,
 *  auxiliary channels and the INT pin.
 *
 * Extends Si1132Minimal.
 *
 * @param connection Register-capable I²C connection pointing at the device (address 0x60).
 */
class Si1132Full : public Si1132Minimal {
public:
    explicit Si1132Full(RegisterConnection& connection);

    /** @brief Forced UV measurement; UV index (AUX_DATA / 100), NAN on failure. */
    float read_uv_index();

    /** @brief Forced measurement; raw visible count. @return true on success. */
    bool read_visible(uint16_t& visible);

    /** @brief Forced measurement; raw infrared count. @return true on success. */
    bool read_infrared(uint16_t& infrared);

    /** @brief Read the latest data registers without starting a measurement. @return true on success. */
    bool read_raw(uint16_t& visible, uint16_t& infrared, uint16_t& aux);

    /** @brief Auxiliary channel as die temperature (AUX_ADCMUX = 0x65, relative only). @return true on success. */
    bool read_temperature_raw(uint16_t& raw);

    /** @brief Auxiliary channel as supply voltage (AUX_ADCMUX = 0x75). @return true on success. */
    bool read_vdd_raw(uint16_t& raw);

    /** @brief Select the measured channels (CHLIST).
     *  @param uv       Enable the UV channel (cannot be combined with aux).
     *  @param visible  Enable the visible channel.
     *  @param infrared Enable the infrared channel.
     *  @param aux      Enable the auxiliary channel.
     *  @return false on a bus error, if no channel is selected, or if both uv and aux are set.
     */
    bool set_channels(bool uv, bool visible, bool infrared, bool aux = false);

    /** @brief Set the ADC gain (integration time x 2^n) and the recommended recovery count.
     *  @param visible  Visible gain 0–7.
     *  @param infrared Infrared gain 0–7.
     *  @return false on a bus error or a gain above 7.
     */
    bool set_gain(uint8_t visible, uint8_t infrared);

    /** @brief Select the high signal range (sensitivity ÷ 14.5, direct sunlight). */
    bool set_high_range(bool visible, bool infrared);

    /** @brief Select the reported ADC bits: true = least significant 16 bits, false = 16 MSBs. */
    bool set_alignment(bool visible_low, bool infrared_low);

    /** @brief Write the four UV coefficients UCOEF0..3. */
    bool set_uv_coefficients(const uint8_t ucoef[4]);

    /** @brief GET_CAL, then read the 12 calibration bytes from 0x22–0x2D. */
    bool read_calibration(uint8_t out[12]);

    /** @brief Start autonomous measurements.
     *  @param interval_s Wake-up interval in seconds (multiples of 31.25 µs, at most ~2.05 s); 0 returns to forced mode.
     *  @return false on a bus error or an out-of-range interval.
     */
    bool start_autonomous(float interval_s);

    /** @brief ALS_PAUSE. */
    bool pause();

    /** @brief ALS_FORCE without waiting. */
    bool force();

    /** @brief IRQ_STATUS.ALS_INT set (measurement complete). */
    bool measurement_ready();

    /** @brief Decode RESPONSE: 4-bit counter and error code (0 = none). @return true on success. */
    bool read_response(uint8_t& counter, uint8_t& error);

    /** @brief Change the I²C address (volatile): I2C_ADDR parameter then BUSADDR. */
    bool set_i2c_address(uint8_t address);

    /** @brief RESET command, wait, then write HW_KEY again. */
    bool reset();

    /** @brief INT_OE and ALS_IE = 1: INT driven low on each completed measurement. */
    bool enable_interrupt();

    /** @brief Release INT (INT_OE = 0); ALS_IE stays set because read() polls ALS_INT. */
    bool disable_interrupt();

    /** @brief Read IRQ_STATUS, write it back to clear it, return the value (0 = none). */
    uint8_t poll_interrupt();

    /** @brief Enable the INT output and subscribe to the INT pin (falling edge).
     *  @param callback Called with the IRQ_STATUS value on each INT edge.
     *  @param intPin   Optional InputPin overriding connection.intPin().
     *  @return true if subscribed; false when no pin is available (poll with poll_interrupt()).
     */
    bool on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from INT and release the output. */
    void off_interrupt();

private:
    bool _read_aux(uint8_t mux, uint16_t& raw);
    static void _edge_trampoline();

    void (*_callback)(uint8_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static Si1132Full* _active_instance;
};
