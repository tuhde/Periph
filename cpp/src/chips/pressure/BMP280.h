#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief BMP280 piezo-resistive pressure + temperature sensor — minimal interface.
 *
 *  Provides calibrated temperature (°C) and pressure (hPa) with no configuration
 *  beyond the transport. Default configuration: forced mode, oversampling ×1 for
 *  both channels, IIR filter off.
 *
 *  @param transport Configured I²C transport pointing at the device.
 *  @param addr 7-bit I²C address (default 0x76, alternate 0x77).
 */
class BMP280Minimal {
public:
    explicit BMP280Minimal(Transport& transport, uint8_t addr = 0x76);

    /** @brief Read calibrated temperature.
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

    /** @brief Read calibrated pressure.
     *
     *  Triggers a forced-mode conversion, re-reads both ADCs, refreshes t_fine,
     *  then returns pressure in hPa.
     *
     *  @return Pressure in hPa.
     */
    float pressure();

protected:
    static constexpr uint8_t  REG_ID          = 0xD0;
    static constexpr uint8_t  REG_RESET        = 0xE0;
    static constexpr uint8_t  REG_STATUS       = 0xF3;
    static constexpr uint8_t  REG_CTRL_MEAS    = 0xF4;
    static constexpr uint8_t  REG_CONFIG       = 0xF5;
    static constexpr uint8_t  REG_CAL_START    = 0x88;
    static constexpr uint8_t  REG_DATA         = 0xF7;

    static constexpr uint8_t  CHIP_ID          = 0x58;
    static constexpr uint8_t  RESET_CMD        = 0xB6;
    static constexpr uint8_t  CTRL_MEAS_DEFAULT = 0x25;

    Transport& _transport;
    uint8_t   _addr;

    int32_t   _t_fine;

    uint16_t _dig_T1 = 0;
    int16_t  _dig_T2 = 0;
    int16_t  _dig_T3 = 0;
    uint16_t _dig_P1 = 0;
    int16_t  _dig_P2 = 0;
    int16_t  _dig_P3 = 0;
    int16_t  _dig_P4 = 0;
    int16_t  _dig_P5 = 0;
    int16_t  _dig_P6 = 0;
    int16_t  _dig_P7 = 0;
    int16_t  _dig_P8 = 0;
    int16_t  _dig_P9 = 0;

    uint8_t  _ctrl_meas_cache;

    void     _load_calibration();
    void     _write_reg(uint8_t reg, uint8_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void     _write_ctrl_meas(uint8_t value);
    void     _write_config(uint8_t value);
    void     _trigger_measurement();
    void     _trigger_read_burst(int32_t& adc_T, int32_t& adc_P);
    float    _compensate_temp(int32_t adc_T);
    float    _compensate_pressure(int32_t adc_P);
};

/** @brief BMP280 full interface — extends BMP280Minimal with configuration and altitude helpers.
 *
 *  Adds power-mode control, oversampling settings, IIR filter, standby time,
 *  status read, altitude / sea-level helpers, chip_id, and reset.
 *
 *  @param transport Configured I²C transport pointing at the device.
 *  @param addr 7-bit I²C address (default 0x76).
 *  @param osrs_t Temperature oversampling 0–5 (default 1 = ×1).
 *  @param osrs_p Pressure oversampling 0–5 (default 1 = ×1).
 *  @param mode Power mode 0/1/3 (default 1 = forced).
 *  @param filter IIR filter coefficient 0–4 (default 0 = off).
 *  @param t_sb Standby time in normal mode 0–7 (default 0 = 0.5 ms).
 */
class BMP280Full : public BMP280Minimal {
public:
    static constexpr uint8_t OSRS_SKIP  = 0;
    static constexpr uint8_t OSRS_X1    = 1;
    static constexpr uint8_t OSRS_X2    = 2;
    static constexpr uint8_t OSRS_X4    = 3;
    static constexpr uint8_t OSRS_X8    = 4;
    static constexpr uint8_t OSRS_X16   = 5;

    static constexpr uint8_t MODE_SLEEP   = 0;
    static constexpr uint8_t MODE_FORCED   = 1;
    static constexpr uint8_t MODE_NORMAL  = 3;

    static constexpr uint8_t FILTER_OFF  = 0;
    static constexpr uint8_t FILTER_2    = 1;
    static constexpr uint8_t FILTER_4    = 2;
    static constexpr uint8_t FILTER_8    = 3;
    static constexpr uint8_t FILTER_16   = 4;

    static constexpr uint8_t T_SB_0_5_MS   = 0;
    static constexpr uint8_t T_SB_62_5_MS  = 1;
    static constexpr uint8_t T_SB_125_MS   = 2;
    static constexpr uint8_t T_SB_250_MS   = 3;
    static constexpr uint8_t T_SB_500_MS   = 4;
    static constexpr uint8_t T_SB_1000_MS  = 5;
    static constexpr uint8_t T_SB_2000_MS  = 6;
    static constexpr uint8_t T_SB_4000_MS  = 7;

    static constexpr uint8_t STATUS_MEASURING = 0x08;
    static constexpr uint8_t STATUS_IM_UPDATE = 0x01;

    explicit BMP280Full(Transport& transport, uint8_t addr = 0x76,
                        uint8_t osrs_t = 1, uint8_t osrs_p = 1,
                        uint8_t mode = 1, uint8_t filter = 0, uint8_t t_sb = 0);

    /** @brief Update chip configuration.
     *  @param osrs_t Temperature oversampling 0–5.
     *  @param osrs_p Pressure oversampling 0–5.
     *  @param mode Power mode (0=sleep, 1=forced, 3=normal).
     *  @param filter IIR filter coefficient (0=off, 1, 2, 3, 4=×16).
     *  @param t_sb Standby time in normal mode (0–7).
     */
    void configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t mode, uint8_t filter, uint8_t t_sb);

    /** @brief Update oversampling settings.
     *  @param osrs_t Temperature oversampling 0–5.
     *  @param osrs_p Pressure oversampling 0–5.
     */
    void set_oversampling(uint8_t osrs_t, uint8_t osrs_p);

    /** @brief Update power mode.
     *  @param mode 0=sleep, 1=forced, 3=normal.
     */
    void set_mode(uint8_t mode);

    /** @brief Update IIR filter coefficient.
     *  @param coeff 0=off, 1=×2, 2=×4, 3=×8, 4=×16.
     */
    void set_filter(uint8_t coeff);

    /** @brief Update standby time (only relevant in normal mode).
     *  @param t_sb 0=0.5ms, 1=62.5ms, 2=125ms, 3=250ms, 4=500ms, 5=1s, 6=2s, 7=4s.
     */
    void set_standby(uint8_t t_sb);

    /** @brief Read status register.
     *  @return Status byte; check STATUS_MEASURING and STATUS_IM_UPDATE bits.
     */
    uint8_t status();

    /** @brief Compute altitude above sea level from current pressure.
     *  @param sea_level_hpa Reference sea-level pressure in hPa (default 1013.25).
     *  @return Altitude in metres.
     */
    float altitude(float sea_level_hpa = 1013.25f);

    /** @brief Compute sea-level pressure for a known altitude.
     *  @param altitude_m Altitude in metres.
     *  @return Sea-level pressure in hPa.
     */
    float sea_level_pressure(float altitude_m);

    /** @brief Read chip ID register.
     *  @return Chip ID; expect 0x58 for BMP280.
     */
    uint8_t chip_id();

    /** @brief Perform soft reset and re-read calibration coefficients.
     *
     *  Re-applies the current ctrl_meas and config settings.
     */
    void reset();

protected:
    uint8_t _osrs_t = 1;
    uint8_t _osrs_p = 1;
    uint8_t _mode = 1;
    uint8_t _filter = 0;
    uint8_t _t_sb = 0;

    uint8_t _ctrl_meas_value();
    uint8_t _config_value();
};