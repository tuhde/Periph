#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief ADS1014 12-bit I²C ADC with PGA and comparator — minimal interface.
 *
 *  Single-input (AIN0 − AIN1) ΔΣ ADC with internal reference and oscillator.
 *  Registers are 16-bit, big-endian, addressed through a persistent Address
 *  Pointer. Four selectable addresses (0x48–0x4B) via the ADDR strap. The
 *  chip has no ID register, so the constructor performs no I²C traffic.
 *
 *  Defaults: ±2.048 V range (1 mV/LSB), 1600 SPS, single-shot, comparator
 *  disabled. The whole Config word is written for every conversion.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ADS1014Minimal {
public:
    /** @brief Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
    static constexpr uint8_t I2C_ADDRESS = 0x48;

    explicit ADS1014Minimal(RegisterConnection& connection);

    /** @brief Run one single-shot conversion of AIN0 − AIN1.
     *
     *  Writes the whole Config word with OS = 1, polls OS until the chip is
     *  idle again, then reads CONVERSION.
     *
     *  @return 12-bit two's-complement code, −2048…2047.
     */
    int16_t readRaw();

    /** @brief Run one single-shot conversion and convert it to volts.
     *  @return Voltage AIN0 − AIN1 in volts (raw × full-scale / 2048). */
    float readVoltage();

protected:
    static constexpr uint8_t REG_CONVERSION = 0x00;
    static constexpr uint8_t REG_CONFIG     = 0x01;
    static constexpr uint8_t REG_LO_THRESH  = 0x02;
    static constexpr uint8_t REG_HI_THRESH  = 0x03;

    static constexpr uint16_t CFG_OS          = 0x8000;
    static constexpr uint16_t CFG_MODE_SINGLE = 0x0100;
    static constexpr uint8_t  QUEUE_DISABLE   = 3;

    RegisterConnection& _connection;
    uint8_t _pga = 2;
    uint8_t _dr = 4;
    bool _continuous = false;
    uint8_t _compMode = 0;
    uint8_t _compPol = 0;
    bool _compLat = false;
    uint8_t _queue = 0;
    uint8_t _source = 0;

    uint16_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint16_t value);
    uint8_t _compQue() const;
    uint16_t _configWord(bool osBit, bool continuous) const;
    int16_t _readConversion();
    float _lsb() const;
};

/** @brief ADS1014 full interface — extends Minimal with PGA gain, data rate,
 *  continuous conversion, the programmable comparator and the Level-2
 *  ALERT/RDY interrupt API.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class ADS1014Full : public ADS1014Minimal {
public:
    /** @brief PGA settings (full-scale range). */
    static constexpr uint8_t PGA_6_144 = 0;
    static constexpr uint8_t PGA_4_096 = 1;
    static constexpr uint8_t PGA_2_048 = 2;
    static constexpr uint8_t PGA_1_024 = 3;
    static constexpr uint8_t PGA_0_512 = 4;
    static constexpr uint8_t PGA_0_256 = 5;

    /** @brief Data-rate codes. */
    static constexpr uint8_t DR_128  = 0;
    static constexpr uint8_t DR_250  = 1;
    static constexpr uint8_t DR_490  = 2;
    static constexpr uint8_t DR_920  = 3;
    static constexpr uint8_t DR_1600 = 4;
    static constexpr uint8_t DR_2400 = 5;
    static constexpr uint8_t DR_3300 = 6;

    /** @brief Comparator mode, polarity and queue settings. */
    static constexpr uint8_t COMP_TRADITIONAL = 0;
    static constexpr uint8_t COMP_WINDOW      = 1;
    static constexpr uint8_t ACTIVE_LOW       = 0;
    static constexpr uint8_t ACTIVE_HIGH      = 1;
    static constexpr uint8_t QUEUE_1          = 0;
    static constexpr uint8_t QUEUE_2          = 1;
    static constexpr uint8_t QUEUE_4          = 2;

    /** @brief Interrupt sources — only one at a time can drive ALERT/RDY. */
    static constexpr uint8_t SOURCE_CONVERSION_READY = 0x01;
    static constexpr uint8_t SOURCE_COMPARATOR       = 0x02;

    explicit ADS1014Full(RegisterConnection& connection);

    /** @brief Select the full-scale range used by all following reads.
     *  Thresholds must be rewritten after a gain change.
     *  @param pga PGA_6_144 (0) … PGA_0_256 (5).
     *  @return true if applied, false for a value outside 0–5 (no change). */
    bool setGain(uint8_t pga);

    /** @brief Read the configured PGA code.
     *  @return PGA code 0–5. */
    uint8_t getGain();

    /** @brief Full-scale range of the current gain.
     *  @return ±FSR in volts (6.144 … 0.256). */
    float fullScale();

    /** @brief Size of one conversion code.
     *  @return fullScale() / 2048 in volts. */
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

    /** @brief Write the reset values back (CONFIG 0x8583, LO 0x8000, HI 0x7FFF) and clear
     *  the cached settings. Not an I²C general-call reset. */
    void reset();

    /** @brief Configure the comparator and enable it as the ALERT/RDY source.
     *  @param mode     COMP_TRADITIONAL or COMP_WINDOW.
     *  @param polarity ACTIVE_LOW or ACTIVE_HIGH.
     *  @param latch    Latch the alert until CONVERSION is read.
     *  @param queue    QUEUE_1, QUEUE_2 or QUEUE_4 consecutive out-of-range conversions.
     *  @return true if applied, false for an out-of-range argument (no write). */
    bool configureComparator(uint8_t mode = COMP_TRADITIONAL, uint8_t polarity = ACTIVE_LOW,
                             bool latch = false, uint8_t queue = QUEUE_1);

    /** @brief Disable the comparator (COMP_QUE = 11); ALERT/RDY goes high-impedance. */
    void disableComparator();

    /** @brief Write the comparator low threshold (LO_THRESH = raw << 4).
     *  @param raw 12-bit two's-complement code, −2048…2047.
     *  @return true if written, false if out of range (no write). */
    bool setLowThresholdRaw(int16_t raw);

    /** @brief Write the comparator high threshold (HI_THRESH = raw << 4).
     *  @param raw 12-bit two's-complement code, −2048…2047.
     *  @return true if written, false if out of range (no write). */
    bool setHighThresholdRaw(int16_t raw);

    /** @brief Write both thresholds from volts (converted with lsb(), clamped to the code range).
     *  @param low  Low threshold in volts.
     *  @param high High threshold in volts.
     *  @return true if written, false if high ≤ low (no write). */
    bool setThresholds(float low, float high);

    /** @brief Read both thresholds back as sign-extended 12-bit codes.
     *  @param low  Receives the low threshold code.
     *  @param high Receives the high threshold code. */
    void getThresholdsRaw(int16_t* low, int16_t* high);

    /** @brief Release a latched comparator output by reading CONVERSION. */
    void clearAlert();

    /** @brief Route one source to ALERT/RDY; the other is disabled.
     *
     *  SOURCE_CONVERSION_READY writes HI_THRESH = 0x8000, LO_THRESH = 0x0000 and
     *  COMP_QUE = 00. SOURCE_COMPARATOR restores the thresholds last set through
     *  the driver and the configured queue.
     *
     *  @param source SOURCE_CONVERSION_READY or SOURCE_COMPARATOR.
     *  @return true if applied, false for an unknown source or, for
     *          SOURCE_COMPARATOR, thresholds with high ≤ low (no write). */
    bool enableInterrupt(uint8_t source);

    /** @brief Release ALERT/RDY (COMP_QUE = 11). The pin is shared, so the call
     *  releases it whichever source is given.
     *  @param source SOURCE_CONVERSION_READY or SOURCE_COMPARATOR. */
    void disableInterrupt(uint8_t source);

    /** @brief Report whether the enabled source is asserted.
     *
     *  The chip has no interrupt-status register. With an InputPin on the
     *  connection (or passed to onInterrupt()), samples the ALERT/RDY level
     *  against COMP_POL and, for a latched comparator alert, reads CONVERSION
     *  to clear it. Without a pin, SOURCE_CONVERSION_READY falls back to
     *  CONFIG.OS (single-shot only) and SOURCE_COMPARATOR returns 0.
     *
     *  @return The enabled SOURCE_* constant if asserted, else 0. */
    uint8_t pollInterrupt();

    /** @brief Subscribe to ALERT/RDY edges (falling for ACTIVE_LOW, rising for ACTIVE_HIGH).
     *  Requires an InputPin — either @p intPin or connection.intPin(). Call
     *  enableInterrupt() first.
     *  @param callback Called with the pollInterrupt() value on every asserting edge.
     *  @param intPin   Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

protected:
    int16_t _loRaw = -2048;
    int16_t _hiRaw = 2047;

    void _writeConfig();
    bool _writeThreshold(uint8_t reg, int16_t raw);

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static ADS1014Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
