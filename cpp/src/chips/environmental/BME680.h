#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief BME680 4-in-1 environmental sensor: temperature, pressure, humidity, gas resistance — minimal interface.
 *
 *  Provides calibrated temperature (°C), pressure (hPa), humidity (%RH), and
 *  gas resistance (Ω) with no configuration beyond the transport.
 *  I²C address is 0x76 (SDO=GND) or 0x77 (SDO=VDDIO).
 *
 *  Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 *  heater profile 0 at 320 °C / 150 ms.
 *
 *  @param transport Configured I²C transport pointing at the device.
 */
class BME680Minimal {
public:
    explicit BME680Minimal(Transport& transport);

    /** @brief Read calibrated temperature.
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

    /** @brief Read calibrated pressure.
     *  @return Pressure in hPa.
     */
    float pressure();

    /** @brief Read calibrated humidity.
     *  @return Relative humidity in %RH.
     */
    float humidity();

    /** @brief Read gas sensor resistance.
     *  @return Gas resistance in Ohms, or NaN on invalid reading.
     */
    float gas_resistance();

    int32_t  _t_fine = 0;
    float    _ambient_temp = 25.0f;

    uint16_t _par_T1 = 0;
    int16_t  _par_T2 = 0;
    int8_t   _par_T3 = 0;
    uint16_t _par_P1 = 0;
    int16_t  _par_P2 = 0;
    int8_t   _par_P3 = 0;
    int16_t  _par_P4 = 0;
    int16_t  _par_P5 = 0;
    int8_t   _par_P6 = 0;
    int8_t   _par_P7 = 0;
    int16_t  _par_P8 = 0;
    int16_t  _par_P9 = 0;
    uint8_t  _par_P10 = 0;
    uint16_t _par_H1 = 0;
    uint16_t _par_H2 = 0;
    int8_t   _par_H3 = 0;
    int8_t   _par_H4 = 0;
    int8_t   _par_H5 = 0;
    uint8_t  _par_H6 = 0;
    int8_t   _par_H7 = 0;
    int8_t   _par_G1 = 0;
    int16_t  _par_G2 = 0;
    int8_t   _par_G3 = 0;
    int8_t   _res_heat_val = 0;
    uint8_t  _res_heat_range = 0;
    int8_t   _range_switching_error = 0;

    uint8_t  _osrs_t = 1;
    uint8_t  _osrs_p = 1;
    uint8_t  _osrs_h = 1;

    float    _compensate_temp(uint32_t adc_T);
    float    _compensate_pressure(uint32_t adc_P);
    float    _compensate_humidity(uint16_t hum_adc);
    float    _compensate_gas(uint16_t gas_adc, uint8_t gas_range);

protected:
    static constexpr uint8_t REG_RES_HEAT_VAL   = 0x00;
    static constexpr uint8_t REG_RES_HEAT_RANGE = 0x02;
    static constexpr uint8_t REG_RANGE_SW_ERR   = 0x04;
    static constexpr uint8_t REG_MEAS_STATUS    = 0x1D;
    static constexpr uint8_t REG_PRESS_MSB      = 0x1F;
    static constexpr uint8_t REG_CTRL_GAS_0     = 0x70;
    static constexpr uint8_t REG_CTRL_GAS_1     = 0x71;
    static constexpr uint8_t REG_CTRL_HUM       = 0x72;
    static constexpr uint8_t REG_CTRL_MEAS      = 0x74;
    static constexpr uint8_t REG_CONFIG         = 0x75;
    static constexpr uint8_t REG_CAL_BLOCK1     = 0x8A;
    static constexpr uint8_t REG_ID             = 0xD0;
    static constexpr uint8_t REG_RESET          = 0xE0;
    static constexpr uint8_t REG_CAL_BLOCK2     = 0xE1;

    static constexpr uint8_t CHIP_ID            = 0x61;
    static constexpr uint8_t RESET_CMD          = 0xB6;

    static constexpr uint32_t MEAS_TIME_MS      = 200;

    Transport& _transport;
    uint8_t   _filter = 0;
    uint8_t   _gas_enabled = 1;
    uint8_t   _nb_conv = 0;
    int16_t   _heat_temp = 320;
    uint16_t  _heat_dur = 150;

    void     _read_calibration();
    void     _write_reg(uint8_t reg, uint8_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void     _trigger_and_read(uint32_t& press_adc, uint32_t& temp_adc,
                               uint16_t& hum_adc, uint16_t& gas_adc,
                               uint8_t& gas_range, uint8_t& gas_valid,
                               uint8_t& heat_stab);
    uint8_t  _calc_heater_resistance(int16_t target_temp, float ambient_temp);
    uint8_t  _calc_gas_wait(uint16_t target_ms);
    void     _setup_heater(uint8_t index, int16_t temp_c, uint16_t dur_ms);
};

/** @brief BME680 full interface — extends BME680Minimal with configuration, multi-profile heater, and status.
 *
 *  Adds oversampling for all three TPH channels, IIR filter, multi-profile
 *  heater control, ambient-temperature override, read_all, and status queries.
 *
 *  @param transport Configured I²C transport pointing at the device.
 */
class BME680Full : public BME680Minimal {
public:
    static constexpr uint8_t OSRS_SKIP = 0;
    static constexpr uint8_t OSRS_X1   = 1;
    static constexpr uint8_t OSRS_X2   = 2;
    static constexpr uint8_t OSRS_X4   = 3;
    static constexpr uint8_t OSRS_X8   = 4;
    static constexpr uint8_t OSRS_X16  = 5;

    static constexpr uint8_t MODE_SLEEP  = 0;
    static constexpr uint8_t MODE_FORCED = 1;

    static constexpr uint8_t FILTER_0   = 0;
    static constexpr uint8_t FILTER_1   = 1;
    static constexpr uint8_t FILTER_3   = 2;
    static constexpr uint8_t FILTER_7   = 3;
    static constexpr uint8_t FILTER_15  = 4;
    static constexpr uint8_t FILTER_31  = 5;
    static constexpr uint8_t FILTER_63  = 6;
    static constexpr uint8_t FILTER_127 = 7;

    static constexpr uint8_t STATUS_NEW_DATA      = 0x80;
    static constexpr uint8_t STATUS_GAS_MEASURING  = 0x40;
    static constexpr uint8_t STATUS_MEASURING      = 0x20;
    static constexpr uint8_t STATUS_GAS_VALID      = 0x20;
    static constexpr uint8_t STATUS_HEATER_STABLE  = 0x10;

    explicit BME680Full(Transport& transport);

    /** @brief Write ctrl_hum, ctrl_meas, and config registers in the correct order.
     *  @param osrs_t Temperature oversampling (0–5).
     *  @param osrs_p Pressure oversampling (0–5).
     *  @param osrs_h Humidity oversampling (0–5).
     *  @param mode   Power mode (0=sleep, 1=forced).
     *  @param filter IIR filter coefficient (0–7).
     */
    void configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h, uint8_t mode, uint8_t filter);

    /** @brief Update oversampling for all three TPH channels.
     *  @param osrs_t Temperature oversampling (0–5).
     *  @param osrs_p Pressure oversampling (0–5).
     *  @param osrs_h Humidity oversampling (0–5).
     */
    void set_oversampling(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h);

    /** @brief Update IIR filter coefficient.
     *  @param coeff Filter coefficient (0–7).
     */
    void set_filter(uint8_t coeff);

    /** @brief Configure heater profile 0 and activate it.
     *  @param temp_c     Target heater temperature in degrees Celsius.
     *  @param duration_ms Heater on-time in milliseconds (1–4032).
     */
    void set_heater(int16_t temp_c, uint16_t duration_ms);

    /** @brief Configure one of the 10 heater profiles.
     *  @param index      Profile index (0–9).
     *  @param temp_c     Target heater temperature in degrees Celsius.
     *  @param duration_ms Heater on-time in milliseconds (1–4032).
     */
    void set_heater_profile(uint8_t index, int16_t temp_c, uint16_t duration_ms);

    /** @brief Select which heater profile to use in the next forced cycle.
     *  @param index Profile index (0–9).
     */
    void select_heater_profile(uint8_t index);

    /** @brief Enable or disable gas conversion.
     *  @param enabled True to enable gas measurement.
     */
    void set_gas_enabled(bool enabled);

    /** @brief Turn the heater off or on via ctrl_gas_0.
     *  @param off True to disable the heater.
     */
    void set_heater_off(bool off);

    /** @brief Override the ambient temperature used for heater-resistance calculation.
     *  @param temp_c Ambient temperature in degrees Celsius.
     */
    void set_ambient_temperature(float temp_c);

    /** @brief Read all four sensor values from a single TPHG cycle.
     *  @param[out] t Temperature in °C.
     *  @param[out] p Pressure in hPa.
     *  @param[out] h Humidity in %RH.
     *  @param[out] g Gas resistance in Ohms (NaN if invalid).
     */
    void read_all(float& t, float& p, float& h, float& g);

    /** @brief Check if the most recent gas reading is valid.
     *  @return True if gas_valid_r was set.
     */
    bool gas_valid();

    /** @brief Check if the heater reached its target temperature.
     *  @return True if heat_stab_r was set.
     */
    bool heater_stable();

    /** @brief Read the measurement status register.
     *  @return Status byte with flags.
     */
    uint8_t status();

    /** @brief Read the chip ID register.
     *  @return Chip ID; expect 0x61.
     */
    uint8_t chip_id();

    /** @brief Perform a soft reset, re-read calibration, and re-apply configuration. */
    void reset();
};
