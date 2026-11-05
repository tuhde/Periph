#pragma once
#include "ADS111xBase.h"

/** @brief ADS1113 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V) — minimal interface.
 *
 *  Texas Instruments I²C ADC, four addresses (0x48–0x4B) via the ADDR strap,
 *  no ID register. The constructor performs no I²C traffic; every conversion
 *  writes the full Config word (single-shot, ±2.048 V, 128 SPS defaults) and
 *  leaves the chip in power-down.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ADS1113Minimal : public ADS111xBase {
public:
    explicit ADS1113Minimal(RegisterConnection& connection) : ADS111xBase(connection) {}

    /** @brief Run a single-shot conversion of AIN0 − AIN1.
     *  @return Signed 16-bit code (−32768…32767). */
    int16_t readRaw();

    /** @brief Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
     *  @return Voltage AIN0 − AIN1 in V. */
    float readVoltage();
};

/** @brief ADS1113 full interface — extends Minimal with data rate, continuous conversion, status and reset.
 *
 *  @param connection RegisterConnection pointing at the device.
 */
class ADS1113Full : public ADS1113Minimal {
public:
    explicit ADS1113Full(RegisterConnection& connection) : ADS1113Minimal(connection) {}

    /** @brief Data-rate codes (DR[2:0]). */
    static constexpr uint8_t DR_8 = 0, DR_16 = 1, DR_32 = 2, DR_64 = 3,
                             DR_128 = 4, DR_250 = 5, DR_475 = 6, DR_860 = 7;

    /** @brief Single-shot conversion in volts at the fixed ±2.048 V range. */
    float readVoltage();
    /** @brief Start continuous conversion at the current data rate (MODE ← 0). */
    void startContinuous() { _startContinuous(0); }
    /** @brief Return ±FSR of the current gain in volts. */
    float fullScale() const { return _fullScale(); }
    /** @brief Return the size of one code in volts (fullScale() / 32768). */
    float lsb() const { return _lsb(); }
    /** @brief Set the data rate used by all following conversions (DR_8 … DR_860). */
    void setDataRate(uint8_t dr) { _setDataRate(dr); }
    /** @brief Return the configured DR code. */
    uint8_t getDataRate() const { return _dr; }
    /** @brief Return the configured data rate in samples per second. */
    uint16_t dataRateSps() const;
    /** @brief Read the most recent completed result without touching Config. */
    int16_t readContinuousRaw() { return _readContinuousRaw(); }
    /** @brief Read the most recent continuous result in volts. */
    float readContinuous() { return _readContinuousRaw() * _lsb(); }
    /** @brief Return to single-shot / power-down mode (MODE ← 1). */
    void stopContinuous() { _stopContinuous(); }
    /** @brief Return true while CONFIG.OS reads 0 (single-shot mode only). */
    bool isConverting() { return _isConverting(); }
    /** @brief Read the raw 16-bit CONFIG register. */
    uint16_t readConfig() { return _readReg(REG_CONFIG); }
    /** @brief Write the reset values back to CONFIG and clear cached settings. Not an I²C general-call reset. */
    void reset() { _resetRegisters(false); }
};
