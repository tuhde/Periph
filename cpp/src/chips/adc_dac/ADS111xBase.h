#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief Shared implementation of the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.
 *
 *  The three chips share one register map and one Config-register layout and
 *  differ only in which Config fields the silicon honours (ADS1113: fixed
 *  ±2.048 V, no comparator, no mux; ADS1114: PGA + comparator; ADS1115: PGA +
 *  comparator + 4-input mux). This base holds the whole-word Config builder,
 *  the single-shot sequence and every feature implementation as protected
 *  members; ADS1113 / ADS1114 / ADS1115 publish exactly the subset their
 *  silicon supports.
 *
 *  Registers are 16-bit big-endian behind a write-only Address Pointer. Every
 *  Config write is a whole word — never read-modify-write.
 */
class ADS111xBase {
public:
    /** @brief Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
    static constexpr uint8_t I2C_ADDRESS = 0x48;
    /** @brief A conversion finished (ALERT/RDY in conversion-ready mode). */
    static constexpr uint8_t SOURCE_CONVERSION_READY = 0x01;
    /** @brief Result beyond the comparator thresholds. */
    static constexpr uint8_t SOURCE_COMPARATOR       = 0x02;

protected:
    static constexpr uint8_t REG_CONVERSION = 0x00;
    static constexpr uint8_t REG_CONFIG     = 0x01;
    static constexpr uint8_t REG_LO_THRESH  = 0x02;
    static constexpr uint8_t REG_HI_THRESH  = 0x03;

    static constexpr uint16_t CFG_RESET_VALUE = 0x8583;
    static constexpr uint16_t LO_RESET     = 0x8000;
    static constexpr uint16_t HI_RESET     = 0x7FFF;
    static constexpr uint16_t CFG_OS       = 0x8000;
    static constexpr uint16_t CFG_MODE     = 0x0100;
    static constexpr uint8_t  QUE_DISABLED = 3;

    explicit ADS111xBase(RegisterConnection& connection);

    RegisterConnection& _connection;
    uint8_t _mux = 0;
    uint8_t _pga = 2;
    uint8_t _dr = 4;
    uint8_t _compMode = 0;
    uint8_t _compPol = 0;
    uint8_t _compLat = 0;
    uint8_t _compQue = QUE_DISABLED;
    uint8_t _queue = 0;
    bool _continuous = false;
    int16_t _loRaw = -32768;
    int16_t _hiRaw = 32767;
    uint8_t _source = 0;

    uint16_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint16_t value);
    uint16_t _configWord(bool osBit, uint8_t mux) const;
    void _writeConfig(bool osBit = false);
    int16_t _convert(uint8_t mux);

    void _setDataRate(uint8_t dr);
    void _setGain(uint8_t pga);
    void _startContinuous(uint8_t mux);
    int16_t _readContinuousRaw();
    void _stopContinuous();
    bool _isConverting();
    float _fullScale() const;
    float _lsb() const;
    void _resetRegisters(bool withThresholds);

    void _configureComparator(uint8_t mode, uint8_t polarity, bool latch, uint8_t queue);
    void _disableComparator();
    void _setLowThresholdRaw(int16_t raw);
    void _setHighThresholdRaw(int16_t raw);
    void _setThresholds(float low, float high);
    void _getThresholdsRaw(int16_t* low, int16_t* high);
    void _clearAlert();
    void _enableInterrupt(uint8_t source);
    void _disableInterrupt();
    uint8_t _pollInterrupt();
    void _onInterrupt(void (*callback)(uint8_t status), InputPin* intPin);
    void _offInterrupt();

private:
    int16_t _voltsToCode(float volts) const;
    static void _edgeTrampoline();
    void _handleEdge();

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static ADS111xBase* _activeInstance;
};
