#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief MPL3115A2 barometric pressure / altitude / temperature sensor (NXP) — minimal interface.
 *
 *  One-shot barometer readings with no configuration beyond the connection. The I²C address
 *  is fixed at 0x60. init() resets the chip, enables the data-ready flags (PT_DATA_CFG = 0x07)
 *  and selects barometer mode with oversample ratio 128 (about 512 ms per sample) in STANDBY.
 *
 *  @param connection Connection (I²C) pointing at the device. The constructor does no bus access.
 */
class MPL3115A2Minimal {
public:
    explicit MPL3115A2Minimal(Connection& connection);

    /** @brief Check WHO_AM_I (0xC4), soft reset and apply the default configuration.
     *  @return true on success; false if the chip ID is wrong or a bus transfer failed.
     */
    bool init();

    /** @brief Run one conversion and return the pressure.
     *  @param[out] pressure_pa Absolute pressure in pascals.
     *  @return true on success.
     */
    bool read_pressure(float& pressure_pa);

    /** @brief Run one conversion and return the temperature.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @return true on success.
     */
    bool read_temperature(float& temperature_c);

    /** @brief Run one conversion and return pressure and temperature together.
     *  @param[out] pressure_pa   Absolute pressure in pascals.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @return true on success.
     */
    bool read(float& pressure_pa, float& temperature_c);

protected:
    static constexpr uint8_t REG_STATUS      = 0x00;
    static constexpr uint8_t REG_OUT_P_MSB   = 0x01;
    static constexpr uint8_t REG_OUT_P_DELTA = 0x07;
    static constexpr uint8_t REG_DR_STATUS   = 0x06;
    static constexpr uint8_t REG_WHO_AM_I    = 0x0C;
    static constexpr uint8_t REG_F_STATUS    = 0x0D;
    static constexpr uint8_t REG_F_DATA      = 0x0E;
    static constexpr uint8_t REG_F_SETUP     = 0x0F;
    static constexpr uint8_t REG_SYSMOD      = 0x11;
    static constexpr uint8_t REG_INT_SOURCE  = 0x12;
    static constexpr uint8_t REG_PT_DATA_CFG = 0x13;
    static constexpr uint8_t REG_BAR_IN_MSB  = 0x14;
    static constexpr uint8_t REG_P_TGT_MSB   = 0x16;
    static constexpr uint8_t REG_T_TGT       = 0x18;
    static constexpr uint8_t REG_P_WND_MSB   = 0x19;
    static constexpr uint8_t REG_T_WND       = 0x1B;
    static constexpr uint8_t REG_P_MIN       = 0x1C;
    static constexpr uint8_t REG_T_MIN       = 0x1F;
    static constexpr uint8_t REG_P_MAX       = 0x21;
    static constexpr uint8_t REG_T_MAX       = 0x24;
    static constexpr uint8_t REG_CTRL_REG1   = 0x26;
    static constexpr uint8_t REG_CTRL_REG2   = 0x27;
    static constexpr uint8_t REG_CTRL_REG3   = 0x28;
    static constexpr uint8_t REG_CTRL_REG4   = 0x29;
    static constexpr uint8_t REG_CTRL_REG5   = 0x2A;
    static constexpr uint8_t REG_OFF_P       = 0x2B;
    static constexpr uint8_t REG_OFF_T       = 0x2C;
    static constexpr uint8_t REG_OFF_H       = 0x2D;

    static constexpr uint8_t CHIP_ID         = 0xC4;
    static constexpr uint8_t CTRL1_ALT       = 0x80;
    static constexpr uint8_t CTRL1_RST       = 0x04;
    static constexpr uint8_t CTRL1_OST       = 0x02;
    static constexpr uint8_t CTRL1_SBYB      = 0x01;
    static constexpr uint8_t DR_PTDR         = 0x08;

    Connection& _connection;
    uint8_t _os  = 7;
    bool    _alt = false;

    bool _write_reg(uint8_t reg, uint8_t value);
    bool _write_regs(uint8_t reg, const uint8_t* data, size_t len);
    bool _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    uint8_t _ctrl1() const;
    bool _soft_reset();
    bool _apply_config();
    bool _wait_ptdr();
    bool _one_shot(uint8_t* raw5);
    unsigned _timeout_ms() const;
    static float _pressure_from(const uint8_t* b);
    static float _altitude_from(const uint8_t* b);
    static float _temperature_from(const uint8_t* b);
};

/** @brief MPL3115A2 full interface — extends MPL3115A2Minimal with altimeter mode, oversampling,
 *  continuous acquisition, offsets, sea-level pressure, targets and windows, min/max capture,
 *  the 32-sample FIFO and interrupts.
 *
 *  @param connection Connection (I²C) pointing at the device.
 */
class MPL3115A2Full : public MPL3115A2Minimal {
public:
    static constexpr uint8_t SOURCE_DRDY = 0x80;
    static constexpr uint8_t SOURCE_FIFO = 0x40;
    static constexpr uint8_t SOURCE_PW   = 0x20;
    static constexpr uint8_t SOURCE_TW   = 0x10;
    static constexpr uint8_t SOURCE_PTH  = 0x08;
    static constexpr uint8_t SOURCE_TTH  = 0x04;
    static constexpr uint8_t SOURCE_PCHG = 0x02;
    static constexpr uint8_t SOURCE_TCHG = 0x01;

    static constexpr uint8_t FIFO_OFF      = 0;
    static constexpr uint8_t FIFO_CIRCULAR = 1;
    static constexpr uint8_t FIFO_STOP     = 2;

    explicit MPL3115A2Full(Connection& connection);

    /** @brief Run one barometer conversion (switches back from altimeter mode if needed). */
    bool read(float& pressure_pa, float& temperature_c);
    /** @brief Pressure only, see read(). */
    bool read_pressure(float& pressure_pa);
    /** @brief Temperature only, see read(). */
    bool read_temperature(float& temperature_c);

    /** @brief Select barometer (false) or altimeter (true) mode; drops to STANDBY and restores. */
    bool set_mode(bool altimeter);

    /** @brief Switch to altimeter mode if needed and run one conversion.
     *  @param[out] altitude_m Altitude in metres relative to the sea-level pressure (BAR_IN).
     */
    bool read_altitude(float& altitude_m);

    /** @brief Set the oversample ratio (1, 2, 4, ... 128). @return false for any other value. */
    bool set_oversample(uint8_t ratio);

    /** @brief Current oversample ratio (1..128). */
    uint8_t get_oversample() const { return (uint8_t)(1u << _os); }

    /** @brief Start periodic acquisition (ACTIVE mode).
     *  @param step_exp Auto-acquisition step exponent 0-15 (2^n seconds). @return false if out of range.
     */
    bool start_continuous(uint8_t step_exp = 0);

    /** @brief Stop periodic acquisition (back to STANDBY). */
    bool stop_continuous();

    /** @brief True if new pressure/altitude or temperature data is ready (DR_STATUS.PTDR). */
    bool data_ready();

    /** @brief Wait for PTDR and read the latest sample.
     *  @param[out] value         Pascals (barometer) or metres (altimeter).
     *  @param[out] temperature_c Degrees Celsius.
     */
    bool read_continuous(float& value, float& temperature_c);

    /** @brief Read the change since the previous sample (pascals or metres, and °C). */
    bool read_delta(float& delta_value, float& delta_temperature_c);

    /** @brief Write BAR_IN, the altitude reference (2 Pa resolution, default 101326 Pa). */
    bool set_sea_level_pressure(float pa);

    /** @brief Write the offset trims: pressure (4 Pa/LSB), temperature (0.0625 °C/LSB), altitude (m).
     *  @return false if a value is outside its register range.
     */
    bool set_offsets(float pressure_pa = 0.0f, float temperature_c = 0.0f, int8_t altitude_m = 0);

    /** @brief Program threshold/window targets (pascals or metres per mode; temperature in °C). */
    bool set_target(float value, float window, int8_t temperature_c = 0, uint8_t temperature_window = 0);

    /** @brief Use the current output as target: ALARM_SEL = 1 and pulse LOAD_OUTPUT. */
    bool load_current_as_target();

    /** @brief Read the captured minimum/maximum (pascals or metres, °C). */
    bool read_min_max(float& p_min, float& p_max, float& t_min, float& t_max);

    /** @brief Clear the min/max registers. */
    bool clear_min_max();

    /** @brief Configure the FIFO: mode 0 off, 1 circular, 2 stop when full; watermark 0-63.
     *  @return false for an invalid mode or a direct 1 ↔ 2 switch.
     */
    bool fifo_setup(uint8_t mode, uint8_t watermark = 0);

    /** @brief Read F_STATUS: overflow flag, watermark flag and sample count. */
    bool fifo_status(bool& overflow, bool& watermark, uint8_t& count);

    /** @brief Drain up to max_samples FIFO samples.
     *  @param[out] values        Pascals or metres per sample.
     *  @param[out] temperatures  Degrees Celsius per sample.
     *  @return number of samples read.
     */
    uint8_t fifo_read(float* values, float* temperatures, uint8_t max_samples);

    /** @brief Software reset and restore the default configuration. */
    bool reset();

    /** @brief WHO_AM_I register (0xC4). */
    uint8_t who_am_i();

    /** @brief True if SYSMOD reports ACTIVE mode. */
    bool is_active();

    /** @brief Write CTRL_REG3: INT polarity (true = active high) and open-drain per pin. */
    bool set_interrupt_pins(bool polarity1 = false, bool open_drain1 = false,
                            bool polarity2 = false, bool open_drain2 = false);

    /** @brief Enable an interrupt source (SOURCE_* mask) and route it to INT1 or INT2. */
    bool enable_interrupt(uint8_t source, uint8_t pin = 1);

    /** @brief Disable an interrupt source (clears it in CTRL_REG4). */
    bool disable_interrupt(uint8_t source);

    /** @brief Read INT_SOURCE (bitmask of SOURCE_* flags). */
    uint8_t poll_interrupt();

    /** @brief Subscribe to INT1/INT2 falling edges; the callback receives INT_SOURCE filtered to the
     *  sources routed to that pin. Without any pin (no intPin argument and none on the connection)
     *  nothing is delivered — poll with poll_interrupt().
     *  @param callback Called with the status bitmask (non-zero).
     *  @param pin 1 or 2.
     *  @param intPin Optional pin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), uint8_t pin = 1, InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    uint8_t _fifo_mode = 0;
    void (*_callback)(uint8_t) = nullptr;
    uint8_t _int_pin_no = 1;
    InputPin* _intPinUsed = nullptr;
    static MPL3115A2Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
    bool _standby(bool& was_active);
    bool _restore(bool was_active);
};
