#pragma once
#include "ADS111xBase.h"

/** @brief ADS1114 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), PGA and comparator) — minimal interface.
 *
 *  Texas Instruments I²C ADC, four addresses (0x48–0x4B) via the ADDR strap,
 *  no ID register. The constructor performs no I²C traffic; every conversion
 *  writes the full Config word (single-shot, ±2.048 V, 128 SPS defaults) and
 *  leaves the chip in power-down.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ADS1114Minimal : public ADS111xBase {
public:
    explicit ADS1114Minimal(RegisterConnection& connection) : ADS111xBase(connection) {}

    /** @brief Run a single-shot conversion of AIN0 − AIN1.
     *  @return Signed 16-bit code (−32768…32767). */
    int16_t readRaw();

    /** @brief Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
     *  @return Voltage AIN0 − AIN1 in V. */
    float readVoltage();
};

/** @brief ADS1114 full interface — extends Minimal with gain, data rate, continuous conversion, status and reset, the comparator, thresholds and the ALERT/RDY interrupt API.
 *
 *  @param connection RegisterConnection pointing at the device.
 */
class ADS1114Full : public ADS1114Minimal {
public:
    explicit ADS1114Full(RegisterConnection& connection) : ADS1114Minimal(connection) {}

    /** @brief Data-rate codes (DR[2:0]). */
    static constexpr uint8_t DR_8 = 0, DR_16 = 1, DR_32 = 2, DR_64 = 3,
                             DR_128 = 4, DR_250 = 5, DR_475 = 6, DR_860 = 7;
    /** @brief Full-scale range codes (PGA[2:0]). */
    static constexpr uint8_t PGA_6_144 = 0, PGA_4_096 = 1, PGA_2_048 = 2,
                             PGA_1_024 = 3, PGA_0_512 = 4, PGA_0_256 = 5;
    /** @brief Comparator mode, polarity and queue values. */
    static constexpr uint8_t COMP_TRADITIONAL = 0, COMP_WINDOW = 1;
    static constexpr uint8_t ACTIVE_LOW = 0, ACTIVE_HIGH = 1;
    static constexpr uint8_t QUEUE_1 = 0, QUEUE_2 = 1, QUEUE_4 = 2;

    /** @brief Single-shot conversion in volts at the current gain. */
    float readVoltage();
    /** @brief Start continuous conversion at the current data rate (MODE ← 0). */
    void startContinuous() { _startContinuous(0); }
    /** @brief Set the full-scale range for all following reads; values above 5 are ignored.
     *  Thresholds are not rescaled — rewrite them after a gain change.
     *  @param pga PGA_6_144 … PGA_0_256. */
    void setGain(uint8_t pga) { _setGain(pga); }
    /** @brief Return the configured PGA code. */
    uint8_t getGain() const { return _pga; }
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
    /** @brief Write the reset values back to CONFIG, LO_THRESH and HI_THRESH and clear cached settings. Not an I²C general-call reset. */
    void reset() { _resetRegisters(true); }

    /** @brief Configure the comparator and route it to ALERT/RDY. Ignored if an argument is out of range or the last thresholds have high <= low.
     *  @param mode COMP_TRADITIONAL or COMP_WINDOW.
     *  @param polarity ACTIVE_LOW or ACTIVE_HIGH.
     *  @param latch true to latch until clearAlert().
     *  @param queue QUEUE_1, QUEUE_2 or QUEUE_4. */
    void configureComparator(uint8_t mode = COMP_TRADITIONAL, uint8_t polarity = ACTIVE_LOW,
                             bool latch = false, uint8_t queue = QUEUE_1) {
        _configureComparator(mode, polarity, latch, queue);
    }
    /** @brief Set COMP_QUE to 11; ALERT/RDY becomes high-impedance. */
    void disableComparator() { _disableComparator(); }
    /** @brief Write LO_THRESH (signed code, same scale as CONVERSION). */
    void setLowThresholdRaw(int16_t raw) { _setLowThresholdRaw(raw); }
    /** @brief Write HI_THRESH (signed code, same scale as CONVERSION). */
    void setHighThresholdRaw(int16_t raw) { _setHighThresholdRaw(raw); }
    /** @brief Write both thresholds from volts at the current gain; ignored if high <= low. */
    void setThresholds(float low, float high) { _setThresholds(low, high); }
    /** @brief Read both thresholds back, sign-extended. */
    void getThresholdsRaw(int16_t* low, int16_t* high) { _getThresholdsRaw(low, high); }
    /** @brief Read CONVERSION to release a latched comparator output. */
    void clearAlert() { _clearAlert(); }

    /** @brief Route one source to ALERT/RDY (the other is disabled).
     *  @param source SOURCE_CONVERSION_READY or SOURCE_COMPARATOR. */
    void enableInterrupt(uint8_t source) { _enableInterrupt(source); }
    /** @brief Release ALERT/RDY (COMP_QUE ← 11). */
    void disableInterrupt(uint8_t source) { (void)source; _disableInterrupt(); }
    /** @brief Return the enabled source constant if ALERT/RDY is asserted, else 0.
     *  Uses the connection's interrupt pin (or the one given to onInterrupt()); without one SOURCE_CONVERSION_READY
     *  falls back to CONFIG.OS (single-shot only). A latched alert is cleared. */
    uint8_t pollInterrupt() { return _pollInterrupt(); }
    /** @brief Subscribe to ALERT/RDY edges (matching COMP_POL). Needs an InputPin.
     *  @param callback Called with the pollInterrupt() value on every edge.
     *  @param intPin Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr) { _onInterrupt(callback, intPin); }
    /** @brief Unsubscribe. */
    void offInterrupt() { _offInterrupt(); }
};
