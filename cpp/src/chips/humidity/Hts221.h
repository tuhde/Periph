#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

/** @brief Decoded HTS221 factory calibration coefficients. */
struct HTS221Calibration {
    float h0_rh;        /**< Humidity calibration point 0 in %rH. */
    float h1_rh;        /**< Humidity calibration point 1 in %rH. */
    float t0_degc;      /**< Temperature calibration point 0 in °C. */
    float t1_degc;      /**< Temperature calibration point 1 in °C. */
    int16_t h0_t0_out;  /**< Humidity ADC output at point 0. */
    int16_t h1_t0_out;  /**< Humidity ADC output at point 1. */
    int16_t t0_out;     /**< Temperature ADC output at point 0. */
    int16_t t1_out;     /**< Temperature ADC output at point 1. */
};

/** @brief HTS221 capacitive humidity and temperature sensor (STMicroelectronics) — minimal interface.
 *
 * The chip is factory calibrated: init() reads the per-device two-point calibration (registers
 * 0x30–0x3F) and writes CTRL_REG1 = 0x84 (active, block data update, one-shot). read() starts a
 * one-shot conversion, waits for it (timeout 100 ms) and converts the outputs by linear
 * interpolation. Multi-byte transfers set the sub-address MSb (register auto-increment), which the
 * driver does itself.
 *
 * @param connection Register-capable I²C connection pointing at the device (address 0x5F). The
 *        constructor does no bus access.
 */
class HTS221Minimal {
public:
    explicit HTS221Minimal(RegisterConnection& connection);

    /** @brief Read the calibration block and start one-shot mode (CTRL_REG1 = 0x84).
     *  @return true on success; false on a bus error.
     */
    bool init();

    /** @brief Measure temperature and relative humidity (one-shot).
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %rH, clamped to 0–100.
     *  @return true on success; false on a bus error or when the conversion did not finish within 100 ms.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t REG_WHO_AM_I     = 0x0F;
    static constexpr uint8_t REG_AV_CONF      = 0x10;
    static constexpr uint8_t REG_CTRL_REG1    = 0x20;
    static constexpr uint8_t REG_CTRL_REG2    = 0x21;
    static constexpr uint8_t REG_CTRL_REG3    = 0x22;
    static constexpr uint8_t REG_STATUS       = 0x27;
    static constexpr uint8_t REG_HUMIDITY_OUT = 0x28;
    static constexpr uint8_t REG_CALIB        = 0x30;

    static constexpr uint8_t AUTO_INC         = 0x80;
    static constexpr uint8_t CTRL1_PD         = 0x80;
    static constexpr uint8_t CTRL1_BDU        = 0x04;
    static constexpr uint8_t CTRL1_ODR_MASK   = 0x03;
    static constexpr uint8_t CTRL2_BOOT       = 0x80;
    static constexpr uint8_t CTRL2_HEATER     = 0x02;
    static constexpr uint8_t CTRL2_ONE_SHOT   = 0x01;
    static constexpr uint8_t STATUS_BOTH      = 0x03;

    RegisterConnection& _connection;
    HTS221Calibration _cal;

    bool _write_reg(uint8_t reg, uint8_t value);
    bool _read_reg(uint8_t reg, uint8_t& value);
    bool _read_burst(uint8_t reg, uint8_t* buf, size_t len);
    bool _load_calibration();
    bool _read_raw_pair(int16_t& raw_h, int16_t& raw_t);
    bool _one_shot_raw(int16_t& raw_h, int16_t& raw_t);
    float _humidity_from_raw(int16_t raw) const;
    float _temperature_from_raw(int16_t raw) const;
};

/** @brief HTS221 full interface — single values, raw outputs, output data rate, averaging, power
 *  modes, heater, DRDY pin, calibration access, dew point and data-ready interrupt support.
 *
 * Extends HTS221Minimal.
 *
 * @param connection Register-capable I²C connection pointing at the device (address 0x5F).
 */
class HTS221Full : public HTS221Minimal {
public:
    /** STATUS_REG flag: humidity data available. */
    static constexpr uint8_t STATUS_H_DA = 0x02;
    /** STATUS_REG flag: temperature data available. */
    static constexpr uint8_t STATUS_T_DA = 0x01;
    /** Expected WHO_AM_I value. */
    static constexpr uint8_t WHO_AM_I_VALUE = 0xBC;

    explicit HTS221Full(RegisterConnection& connection);

    /** @brief Measure relative humidity only (one-shot); %rH clamped to 0–100, NAN on failure. */
    float read_humidity();

    /** @brief Measure temperature only (one-shot); °C, NAN on failure. */
    float read_temperature();

    /** @brief Measure and return the signed 16-bit raw humidity output (one-shot). @return true on success. */
    bool read_raw_humidity(int16_t& raw);

    /** @brief Measure and return the signed 16-bit raw temperature output (one-shot). @return true on success. */
    bool read_raw_temperature(int16_t& raw);

    /** @brief Read the latest sample without starting a conversion (continuous output data rate).
     *  @return true on success.
     */
    bool read_continuous(float& temperature_c, float& humidity_pct);

    /** @brief Measure once and compute the dew point (Magnus formula); °C, NAN on failure. */
    float dew_point();

    /** @brief Set the output data rate.
     *  @param hz 0 (one-shot), 1, 7 or 12.5.
     *  @return false (nothing written) for another value or on a bus error.
     */
    bool set_output_data_rate(float hz);

    /** @brief Read the output data rate (0, 1, 7 or 12.5 Hz). @return true on success. */
    bool get_output_data_rate(float& hz);

    /** @brief Set the internal averaging.
     *  @param temperature_samples 2, 4, 8, 16, 32, 64, 128 or 256.
     *  @param humidity_samples    4, 8, 16, 32, 64, 128, 256 or 512.
     *  @return false (nothing written) for other values or on a bus error.
     */
    bool set_averaging(uint16_t temperature_samples, uint16_t humidity_samples);

    /** @brief Read the internal averaging. @return true on success. */
    bool get_averaging(uint16_t& temperature_samples, uint16_t& humidity_samples);

    /** @brief Enable or disable block data update (CTRL_REG1.BDU). @return true on success. */
    bool set_block_data_update(bool enabled);

    /** @brief Enter power-down mode (CTRL_REG1.PD = 0). @return true on success. */
    bool power_down();

    /** @brief Leave power-down mode (CTRL_REG1.PD = 1). @return true on success. */
    bool power_up();

    /** @brief Switch the internal heater on or off; do not measure while it is on. @return true on success. */
    bool set_heater(bool on);

    /** @brief True if the heater is on; false when off or on a bus error. */
    bool is_heater_on();

    /** @brief Reload the trimming registers (CTRL_REG2.BOOT), wait for it to clear (timeout 100 ms)
     *  and re-read the calibration. @return true on success; false on timeout or bus error.
     */
    bool reboot();

    /** @brief Read WHO_AM_I (0xBC for the HTS221); 0 on a bus error. */
    uint8_t who_am_i();

    /** @brief Read STATUS_REG (STATUS_H_DA / STATUS_T_DA); 0 on a bus error. */
    uint8_t status();

    /** @brief True when both humidity and temperature data are available. */
    bool data_ready();

    /** @brief Copy the decoded calibration coefficients read by init() / reboot(). */
    void read_calibration(HTS221Calibration& out) const;

    /** @brief Configure the DRDY output pin (CTRL_REG3).
     *  @param enabled     Route data-ready to the DRDY pin.
     *  @param active_high false selects active-low polarity.
     *  @param open_drain  true selects an open-drain output (default push-pull).
     *  @return true on success.
     */
    bool configure_drdy(bool enabled, bool active_high = true, bool open_drain = false);

    /** @brief Read STATUS_REG; the flags clear when the output registers are read. */
    uint8_t poll_interrupt();

    /** @brief Enable DRDY (active high, push-pull) and subscribe to the DRDY pin (rising edge).
     *  @param callback Called with STATUS_REG on each DRDY edge.
     *  @param intPin   Optional InputPin overriding connection.intPin().
     *  @return true if subscribed; false when no pin is available (poll with poll_interrupt()).
     */
    bool on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from the DRDY pin and disable the DRDY output. */
    void off_interrupt();

private:
    static void _edge_trampoline();

    void (*_callback)(uint8_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static HTS221Full* _active_instance;
};
