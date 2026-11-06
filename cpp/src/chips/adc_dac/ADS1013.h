#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief ADS1013 12-bit I²C ADC, fixed ±2.048 V range — minimal interface.
 *
 *  Smallest member of the ADS101x family: one input (AIN0 − AIN1), no PGA,
 *  no multiplexer, no comparator, no ALERT/RDY pin. Registers are 16-bit,
 *  big-endian, addressed through a persistent Address Pointer. Four
 *  selectable addresses (0x48–0x4B) via the ADDR strap. The chip has no ID
 *  register, so the constructor performs no I²C traffic.
 *
 *  Defaults: 1600 SPS, single-shot. The whole Config word is written for
 *  every conversion.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ADS1013Minimal {
public:
    /** @brief Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
    static constexpr uint8_t I2C_ADDRESS = 0x48;

    explicit ADS1013Minimal(RegisterConnection& connection);

    /** @brief Run one single-shot conversion of AIN0 − AIN1.
     *
     *  Writes the whole Config word with OS = 1, polls OS until the chip is
     *  idle again, then reads CONVERSION.
     *
     *  @return 12-bit two's-complement code, −2048…2047.
     */
    int16_t readRaw();

    /** @brief Run one single-shot conversion and convert it to volts.
     *  @return Voltage AIN0 − AIN1 in volts (raw × 0.001). */
    float readVoltage();

protected:
    static constexpr uint8_t REG_CONVERSION = 0x00;
    static constexpr uint8_t REG_CONFIG     = 0x01;

    static constexpr uint16_t CFG_OS          = 0x8000;
    static constexpr uint16_t CFG_MODE_SINGLE = 0x0100;
    // Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
    static constexpr uint16_t CFG_RESERVED    = 0x0403;

    RegisterConnection& _connection;
    uint8_t _dr = 4;
    bool _continuous = false;

    uint16_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint16_t value);
    uint16_t _configWord(bool osBit, bool continuous) const;
    int16_t _readConversion();
};

/** @brief ADS1013 full interface — extends Minimal with data-rate selection
 *  and continuous conversion.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ADS1013Full : public ADS1013Minimal {
public:
    /** @brief Data-rate codes. */
    static constexpr uint8_t DR_128  = 0;
    static constexpr uint8_t DR_250  = 1;
    static constexpr uint8_t DR_490  = 2;
    static constexpr uint8_t DR_920  = 3;
    static constexpr uint8_t DR_1600 = 4;
    static constexpr uint8_t DR_2400 = 5;
    static constexpr uint8_t DR_3300 = 6;

    explicit ADS1013Full(RegisterConnection& connection);

    /** @brief Fixed full-scale range.
     *  @return Always 2.048 (volts). */
    float fullScale();

    /** @brief Size of one conversion code.
     *  @return Always 0.001 (volts). */
    float lsb();

    /** @brief Select the conversion data rate.
     *  @param dr DR_128 (0) … DR_3300 (6).
     *  @return true if applied, false for a value outside 0–6 (no change). */
    bool setDataRate(uint8_t dr);

    /** @brief Read the configured data-rate code.
     *  @return DR code 0–6. */
    uint8_t getDataRate();

    /** @brief Current data rate.
     *  @return 128, 250, 490, 920, 1600, 2400 or 3300 samples per second. */
    uint16_t dataRateSps();

    /** @brief Start continuous conversion (MODE = 0) at the current data rate. */
    void startContinuous();

    /** @brief Read the latest result without touching Config (valid ≥ 1/DR after startContinuous()).
     *  @return 12-bit two's-complement code, −2048…2047. */
    int16_t readContinuousRaw();

    /** @brief Read the latest result in volts.
     *  @return readContinuousRaw() × lsb(). */
    float readContinuous();

    /** @brief Stop continuous conversion (MODE = 1, single-shot / power-down). */
    void stopContinuous();

    /** @brief Report whether a single-shot conversion is running (OS = 0; meaningless in continuous mode).
     *  @return true while converting. */
    bool isConverting();

    /** @brief Read the raw CONFIG register.
     *  @return 16-bit CONFIG value. */
    uint16_t readConfig();

    /** @brief Write CONFIG = 0x8583 back and clear the cached settings.
     *  Not an I²C general-call reset. */
    void reset();

protected:
    void _writeConfig();
};
