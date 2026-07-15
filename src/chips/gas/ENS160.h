#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief ENS160 digital multi-gas sensor — minimal interface.
 *
 *  Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
 *  configuration required beyond the transport. The sensor performs automatic
 *  baseline correction and on-chip signal processing.
 *
 *  Default: STANDARD mode (gas sensing active), polling only, no external
 *  T/RH compensation.
 *
 *  @param transport Configured I²C or SPI transport pointing at the device.
 */
class ENS160Minimal {
public:
    explicit ENS160Minimal(Transport& transport);

    /** @brief Read the VALIDITY_FLAG from DEVICE_STATUS.
     *  @return Validity flag (0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output).
     */
    uint8_t status();

    /** @brief Read calibrated air quality values.
     *
     *  Polls until NEWDAT is set, then checks VALIDITY_FLAG. Only returns
     *  data when validity is 0 (OK). Reads AQI, TVOC, and eCO2 in a single
     *  burst to ensure consistency.
     *
     *  @param aqi      Output: AQI value 1–5.
     *  @param tvoc_ppb Output: TVOC in ppb.
     *  @param eco2_ppm Output: eCO2 in ppm.
     *  @return true if data is valid, false otherwise.
     */
    bool read_air_quality(uint8_t& aqi, float& tvoc_ppb, float& eco2_ppm);

protected:
    static constexpr uint8_t REG_PART_ID       = 0x00;
    static constexpr uint8_t REG_OPMODE        = 0x10;
    static constexpr uint8_t REG_CONFIG        = 0x11;
    static constexpr uint8_t REG_COMMAND       = 0x12;
    static constexpr uint8_t REG_TEMP_IN       = 0x13;
    static constexpr uint8_t REG_RH_IN         = 0x15;
    static constexpr uint8_t REG_DEVICE_STATUS = 0x20;
    static constexpr uint8_t REG_DATA_AQI      = 0x21;
    static constexpr uint8_t REG_DATA_TVOC     = 0x22;
    static constexpr uint8_t REG_DATA_ECO2     = 0x24;
    static constexpr uint8_t REG_DATA_T        = 0x30;
    static constexpr uint8_t REG_DATA_RH       = 0x32;
    static constexpr uint8_t REG_DATA_MISR     = 0x38;
    static constexpr uint8_t REG_GPR_WRITE     = 0x40;
    static constexpr uint8_t REG_GPR_READ      = 0x48;

    static constexpr uint8_t OPMODE_DEEP_SLEEP = 0x00;
    static constexpr uint8_t OPMODE_IDLE       = 0x01;
    static constexpr uint8_t OPMODE_STANDARD   = 0x02;
    static constexpr uint8_t OPMODE_RESET      = 0xF0;

    static constexpr uint16_t PART_ID_EXPECTED = 0x0160;

    Transport& _transport;

    void     _write_reg(uint8_t reg, uint8_t value);
    void     _write_reg_le16(uint8_t reg, uint16_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    uint16_t _read_reg_le16(uint8_t reg);
    uint8_t  _read_device_status();
    bool     _wait_for_new_data(uint32_t timeout_ms = 5000);
};

/** @brief ENS160 full interface — extends ENS160Minimal with compensation, raw readings, and power control.
 *
 *  Adds external temperature/humidity compensation, individual gas readings,
 *  raw sensor resistance, firmware version query, interrupt configuration,
 *  and sleep/wake control.
 *
 *  @param transport Configured I²C or SPI transport pointing at the device.
 */
class ENS160Full : public ENS160Minimal {
public:
    static constexpr uint8_t VALIDITY_OK              = 0;
    static constexpr uint8_t VALIDITY_WARMUP          = 1;
    static constexpr uint8_t VALIDITY_INITIAL_STARTUP = 2;
    static constexpr uint8_t VALIDITY_INVALID         = 3;

    explicit ENS160Full(Transport& transport);

    /** @brief Write external temperature and humidity for compensation.
     *  @param temp_celsius Ambient temperature in degrees Celsius.
     *  @param rh_percent   Ambient relative humidity in percent (0–100).
     */
    void set_compensation(float temp_celsius, float rh_percent);

    /** @brief Read TVOC concentration.
     *  @return TVOC in ppb.
     */
    float read_tvoc();

    /** @brief Read equivalent CO2 concentration.
     *  @return eCO2 in ppm.
     */
    float read_eco2();

    /** @brief Read Air Quality Index (UBA scale).
     *  @return AQI value 1–5 (1=Excellent, 5=Unhealthy).
     */
    uint8_t read_aqi();

    /** @brief Read ethanol concentration estimate.
     *  @return Ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
     */
    float read_ethanol();

    /** @brief Read raw sensor resistance from GPR_READ registers.
     *  @param sensor Sensor number (1 or 4).
     *  @return Resistance in Ohms.
     */
    float read_raw_resistance(uint8_t sensor);

    /** @brief Read the temperature and humidity values used by the sensor.
     *  @param temp_celsius Output: temperature in degrees Celsius.
     *  @param rh_percent   Output: relative humidity in percent.
     */
    void read_compensation_actuals(float& temp_celsius, float& rh_percent);

    /** @brief Query firmware version (requires IDLE mode).
     *  @param major   Output: major version.
     *  @param minor   Output: minor version.
     *  @param release Output: release version.
     */
    void get_firmware_version(uint8_t& major, uint8_t& minor, uint8_t& release);

    /** @brief Configure the INTn interrupt pin.
     *  @param enabled     Enable interrupt pin.
     *  @param active_high True for active-high polarity, false for active-low.
     *  @param push_pull   True for push-pull drive, false for open-drain.
     *  @param on_data     Assert on new DATA_xxx data.
     *  @param on_gpr      Assert on new GPR_READ data.
     */
    void configure_interrupt(bool enabled, bool active_high, bool push_pull, bool on_data, bool on_gpr);

    /** @brief Enter DEEP SLEEP mode for power saving. */
    void sleep();

    /** @brief Wake from DEEP SLEEP and resume STANDARD gas sensing. */
    void wake();
};
