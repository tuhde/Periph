#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief BME280 combined humidity + pressure + temperature sensor — minimal interface.
 *
 *  Provides calibrated temperature (°C), pressure (hPa), and humidity (%RH)
 *  with no configuration beyond the transport. I²C address is 0x76 (SDO=GND)
 *  or 0x77 (SDO=VDDIO). 0x77 collides with the BMP180/BMP280/BMP388.
 *
 *  Sibling of the BMP280 driver: register-compatible for pressure and
 *  temperature, plus an integrated humidity front-end (its own calibration
 *  block, control register, output registers, and compensation formula).
 *
 *  Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off.
 *
 *  @param transport Configured I²C or SPI transport pointing at the device.
 *  @param spi       Set true for SPI bus (masks bit 7 on writes).
 */
class BME280Minimal {
public:
    explicit BME280Minimal(Transport& transport, bool spi = false);

    /** @brief Read calibrated temperature.
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

    /** @brief Read calibrated pressure.
     *
     *  Reads all three ADCs and refreshes t_fine.
     *  Self-contained — may be called without a prior temperature() call.
     *
     *  @return Pressure in hPa.
     */
    float pressure();

    /** @brief Read calibrated humidity.
     *
     *  Reads all three ADCs and refreshes t_fine.
     *
     *  @return Relative humidity in %RH.
     */
    float humidity();

    // Calibration coefficients and compensation functions are public to allow
    // unit tests to inject known datasheet values and verify the algorithm.
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
    uint8_t  _dig_H1 = 0;
    int16_t  _dig_H2 = 0;
    uint8_t  _dig_H3 = 0;
    int16_t  _dig_H4 = 0;
    int16_t  _dig_H5 = 0;
    int8_t   _dig_H6 = 0;

    uint8_t   _osrs_t = 1;
    uint8_t   _osrs_p = 1;
    uint8_t   _osrs_h = 1;

    float    _compensate_temp(uint32_t adc_T);
    float    _compensate_pressure(uint32_t adc_P);
    float    _compensate_humidity(uint16_t adc_H);

protected:
    static constexpr uint8_t REG_CAL_START  = 0x88;
    static constexpr uint8_t REG_H1         = 0xA1;
    static constexpr uint8_t REG_ID         = 0xD0;
    static constexpr uint8_t REG_RESET      = 0xE0;
    static constexpr uint8_t REG_CAL_H2     = 0xE1;
    static constexpr uint8_t REG_CTRL_HUM   = 0xF2;
    static constexpr uint8_t REG_STATUS     = 0xF3;
    static constexpr uint8_t REG_CTRL_MEAS  = 0xF4;
    static constexpr uint8_t REG_CONFIG     = 0xF5;
    static constexpr uint8_t REG_DATA_START = 0xF7;

    static constexpr uint8_t CHIP_ID        = 0x60;
    static constexpr uint8_t RESET_CMD      = 0xB6;

    static constexpr uint32_t MEAS_TIME_MS  = 9;

    Transport& _transport;
    bool      _spi;
    uint8_t   _mode   = 0;
    uint8_t   _filter = 0;
    uint8_t   _t_sb   = 0;
    int32_t   _t_fine = 0;

    void     _read_calibration();
    void     _write_reg(uint8_t reg, uint8_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void     _trigger_and_read(uint32_t& adc_P, uint32_t& adc_T, uint16_t& adc_H);
};

/** @brief BME280 full interface — extends BME280Minimal with configuration, dew point, and altitude helpers.
 *
 *  Adds power-mode control, oversampling for all three channels, IIR filter,
 *  standby time, altitude / sea-level pressure conversion, dew point, and
 *  chip ID / soft reset.
 *
 *  @param transport Configured I²C or SPI transport pointing at the device.
 *  @param spi       Set true for SPI bus (masks bit 7 on writes).
 */
class BME280Full : public BME280Minimal {
public:
    static constexpr uint8_t OSRS_SKIP = 0;
    static constexpr uint8_t OSRS_X1   = 1;
    static constexpr uint8_t OSRS_X2   = 2;
    static constexpr uint8_t OSRS_X4   = 3;
    static constexpr uint8_t OSRS_X8   = 4;
    static constexpr uint8_t OSRS_X16  = 5;

    static constexpr uint8_t MODE_SLEEP  = 0;
    static constexpr uint8_t MODE_FORCED = 1;
    static constexpr uint8_t MODE_NORMAL = 3;

    static constexpr uint8_t FILTER_OFF = 0;
    static constexpr uint8_t FILTER_2   = 1;
    static constexpr uint8_t FILTER_4   = 2;
    static constexpr uint8_t FILTER_8   = 3;
    static constexpr uint8_t FILTER_16  = 4;

    static constexpr uint8_t T_SB_0_5_MS    = 0;
    static constexpr uint8_t T_SB_62_5_MS   = 1;
    static constexpr uint8_t T_SB_125_MS    = 2;
    static constexpr uint8_t T_SB_250_MS    = 3;
    static constexpr uint8_t T_SB_500_MS    = 4;
    static constexpr uint8_t T_SB_1000_MS   = 5;
    static constexpr uint8_t T_SB_10_MS     = 6;
    static constexpr uint8_t T_SB_20_MS     = 7;

    static constexpr uint8_t STATUS_MEASURING = 0x08;
    static constexpr uint8_t STATUS_IM_UPDATE = 0x01;

    explicit BME280Full(Transport& transport, bool spi = false);

    /** @brief Write ctrl_hum, config, and ctrl_meas registers in the correct order.
     *  @param osrs_t Temperature oversampling (0–5).
     *  @param osrs_p Pressure oversampling (0–5).
     *  @param osrs_h Humidity oversampling (0–5).
     *  @param mode   Power mode (0=sleep, 1=forced, 3=normal).
     *  @param filter IIR filter coefficient (0–4).
     *  @param t_sb   Standby time in normal mode (0–7; codes 6/7 mean 10 ms / 20 ms,
     *                not 2000 ms / 4000 ms as on the BMP280).
     */
    void configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h, uint8_t mode, uint8_t filter, uint8_t t_sb);

    /** @brief Update temperature, pressure, and humidity oversampling.
     *  @param osrs_t Temperature oversampling (0–5).
     *  @param osrs_p Pressure oversampling (0–5).
     *  @param osrs_h Humidity oversampling (0–5).
     */
    void set_oversampling(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h);

    /** @brief Update power mode.
     *  @param mode Power mode (0=sleep, 1=forced, 3=normal).
     */
    void set_mode(uint8_t mode);

    /** @brief Update IIR filter coefficient.
     *  @param coeff Filter coefficient (0–4).
     */
    void set_filter(uint8_t coeff);

    /** @brief Update standby time for normal mode.
     *  @param t_sb Standby time value (0–7). On the BME280 codes 6/7 mean
     *              10 ms / 20 ms (not 2000 ms / 4000 ms).
     */
    void set_standby(uint8_t t_sb);

    /** @brief Read the status register.
     *  @return Status byte; bit 3 = measuring, bit 0 = im_update.
     */
    uint8_t status();

    /** @brief Compute altitude above sea level from the current pressure.
     *  @param sea_level_hpa Reference sea-level pressure in hPa (default 1013.25).
     *  @return Altitude in metres.
     */
    float altitude(float sea_level_hpa = 1013.25f);

    /** @brief Compute sea-level pressure for a known altitude.
     *  @param altitude_m Altitude in metres.
     *  @return Sea-level pressure in hPa.
     */
    float sea_level_pressure(float altitude_m);

    /** @brief Compute dew point from current temperature and humidity.
     *  @return Dew point in degrees Celsius.
     */
    float dew_point();

    /** @brief Read the chip ID register.
     *  @return Chip ID; expect 0x60.
     */
    uint8_t chip_id();

    /** @brief Perform a soft reset, re-read calibration, and re-apply configuration. */
    void reset();
};
