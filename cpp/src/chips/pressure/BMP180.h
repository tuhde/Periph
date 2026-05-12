#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief BMP180 piezo-resistive pressure + temperature sensor — minimal interface.
 *
 *  Provides calibrated temperature (°C) and pressure (hPa) with no configuration
 *  beyond the transport. The BMP180 has a fixed I²C address (0x77) and no
 *  programmable address pin.
 *
 *  Default OSS = 0 (Ultra Low Power, 4.5 ms conversion).
 *
 *  @param transport Configured I²C transport pointing at the device.
 */
class BMP180Minimal {
public:
    explicit BMP180Minimal(Transport& transport);

    /** @brief Read calibrated temperature.
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

    /** @brief Read calibrated pressure.
     *
     *  Reads temperature first to refresh B5, then reads pressure.
     *  Self-contained — may be called without a prior temperature() call.
     *
     *  @return Pressure in hPa.
     */
    float pressure();

protected:
    static constexpr uint8_t  REG_ID         = 0xD0;
    static constexpr uint8_t  REG_CAL_START  = 0xAA;
    static constexpr uint8_t  REG_CAL_END    = 0xBF;
    static constexpr uint8_t  REG_CTRL_MEAS  = 0xF4;
    static constexpr uint8_t  REG_OUT_MSB    = 0xF6;
    static constexpr uint8_t  REG_OUT_LSB    = 0xF7;
    static constexpr uint8_t  REG_OUT_XLSB    = 0xF8;
    static constexpr uint8_t  REG_SOFT_RESET = 0xE0;

    static constexpr uint8_t  CMD_TEMP     = 0x2E;
    static constexpr uint8_t  CMD_PRESSURE_OSS0 = 0x34;
    static constexpr uint8_t  CMD_PRESSURE_OSS1 = 0x74;
    static constexpr uint8_t  CMD_PRESSURE_OSS2 = 0xB4;
    static constexpr uint8_t  CMD_PRESSURE_OSS3 = 0xF4;

    static constexpr uint8_t  CHIP_ID        = 0x55;
    static constexpr uint8_t  SOFT_RESET_CMD  = 0xB6;

    static constexpr float    CONV_TIME_OSS0 = 0.0045f;
    static constexpr float    CONV_TIME_OSS1 = 0.0075f;
    static constexpr float    CONV_TIME_OSS2 = 0.0135f;
    static constexpr float    CONV_TIME_OSS3 = 0.0255f;
    static constexpr float    CONV_TIME_TEMP  = 0.0045f;

    Transport& _transport;
    uint8_t   _oss = 0;

    int16_t  _ac1 = 0;
    int16_t  _ac2 = 0;
    int16_t  _ac3 = 0;
    uint16_t _ac4 = 0;
    uint16_t _ac5 = 0;
    uint16_t _ac6 = 0;
    int16_t  _b1  = 0;
    int16_t  _b2  = 0;
    int16_t  _mb  = 0;
    int16_t  _mc  = 0;
    int16_t  _md  = 0;

    int32_t  _b5 = 0;

    void     _read_calibration();
    void     _write_reg(uint8_t reg, uint8_t value);
    uint16_t _read_raw_temp();
    uint32_t _read_raw_pressure();
    int32_t  _compensate_temp(uint16_t ut);
    int32_t  _compensate_pressure(uint32_t up);
    float    _compensate_temp_f(int32_t b5);
    float    _compensate_pressure_f(int32_t up);
};

/** @brief BMP180 full interface — extends BMP180Minimal with OSS control and altitude helpers.
 *
 *  Adds oversampling mode selection and altitude / sea-level pressure conversion.
 *
 *  @param transport Configured I²C transport pointing at the device.
 *  @param oss       Oversampling mode 0–3 (default 0 = ULP).
 */
class BMP180Full : public BMP180Minimal {
public:
    static constexpr uint8_t OSS_ULP            = 0;
    static constexpr uint8_t OSS_STANDARD         = 1;
    static constexpr uint8_t OSS_HIGH_RES         = 2;
    static constexpr uint8_t OSS_ULTRA_HIGH_RES   = 3;

    explicit BMP180Full(Transport& transport, uint8_t oss = 0);

    /** @brief Read the current oversampling mode.
     *  @return OSS value 0–3.
     */
    uint8_t  oversampling();

    /** @brief Change the oversampling mode for subsequent pressure() calls.
     *  @param oss New OSS value 0–3.
     */
    void     set_oversampling(uint8_t oss);

    /** @brief Compute altitude above sea level from the current pressure.
     *  @param sea_level_hpa Reference sea-level pressure in hPa (default 1013.25).
     *  @return Altitude in metres.
     */
    float    altitude(float sea_level_hpa = 1013.25f);

    /** @brief Compute sea-level pressure for a known altitude.
     *  @param altitude_m Altitude in metres.
     *  @return Sea-level pressure in hPa.
     */
    float    sea_level_pressure(float altitude_m);

    /** @brief Read the chip ID register.
     *  @return Chip ID; expect 0x55.
     */
    uint8_t  chip_id();

    /** @brief Perform a soft reset and re-read calibration coefficients. */
    void     reset();
};
