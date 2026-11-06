#include "ADS111xBase.h"

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

namespace {
// Full-scale range in volts, indexed by PGA[2:0] (codes 6 and 7 alias ±0.256 V).
const float FSR[6] = { 6.144f, 4.096f, 2.048f, 1.024f, 0.512f, 0.256f };
// Conversion time in ms (rounded up), indexed by DR[2:0].
const uint8_t WAIT_MS[8] = { 125, 63, 32, 16, 8, 4, 3, 2 };
}  // namespace

ADS111xBase* ADS111xBase::_activeInstance = nullptr;

ADS111xBase::ADS111xBase(RegisterConnection& connection) : _connection(connection) {}

uint16_t ADS111xBase::_readReg(uint8_t reg) {
    uint8_t buf[2] = {0, 0};
    _connection.read(reg, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void ADS111xBase::_writeReg(uint8_t reg, uint16_t value) {
    uint8_t data[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(reg, data, 2);
}

uint16_t ADS111xBase::_configWord(bool osBit, uint8_t mux) const {
    return (uint16_t)((osBit ? CFG_OS : 0) | ((uint16_t)mux << 12) | ((uint16_t)_pga << 9)
        | (_continuous ? 0 : CFG_MODE) | ((uint16_t)_dr << 5) | ((uint16_t)_compMode << 4)
        | ((uint16_t)_compPol << 3) | ((uint16_t)_compLat << 2) | _compQue);
}

void ADS111xBase::_writeConfig(bool osBit) {
    _writeReg(REG_CONFIG, _configWord(osBit, _mux));
}

int16_t ADS111xBase::_convert(uint8_t mux) {
    _continuous = false;
    _writeReg(REG_CONFIG, _configWord(true, mux));
    periph_delay_ms(WAIT_MS[_dr]);
    for (int i = 0; i < 200 && !(_readReg(REG_CONFIG) & CFG_OS); i++) periph_delay_ms(1);
    return (int16_t)_readReg(REG_CONVERSION);
}

void ADS111xBase::_setDataRate(uint8_t dr) {
    if (dr > 7) return;
    _dr = dr;
    if (_continuous) _writeConfig();
}

void ADS111xBase::_setGain(uint8_t pga) {
    if (pga > 5) return;
    _pga = pga;
    if (_continuous) _writeConfig();
}

void ADS111xBase::_startContinuous(uint8_t mux) {
    _mux = mux & 7;
    _continuous = true;
    _writeConfig();
}

int16_t ADS111xBase::_readContinuousRaw() { return (int16_t)_readReg(REG_CONVERSION); }

void ADS111xBase::_stopContinuous() {
    _continuous = false;
    _writeConfig();
}

bool ADS111xBase::_isConverting() { return !(_readReg(REG_CONFIG) & CFG_OS); }

float ADS111xBase::_fullScale() const { return FSR[_pga]; }

float ADS111xBase::_lsb() const { return FSR[_pga] / 32768.0f; }

void ADS111xBase::_resetRegisters(bool withThresholds) {
    _writeReg(REG_CONFIG, CFG_RESET_VALUE);
    if (withThresholds) {
        _writeReg(REG_LO_THRESH, LO_RESET);
        _writeReg(REG_HI_THRESH, HI_RESET);
    }
    _mux = 0; _pga = 2; _dr = 4;
    _compMode = 0; _compPol = 0; _compLat = 0; _compQue = QUE_DISABLED;
    _queue = 0; _continuous = false; _loRaw = -32768; _hiRaw = 32767; _source = 0;
}

void ADS111xBase::_configureComparator(uint8_t mode, uint8_t polarity, bool latch, uint8_t queue) {
    if (mode > 1 || polarity > 1 || queue > 2 || _hiRaw <= _loRaw) return;
    _compMode = mode;
    _compPol = polarity;
    _compLat = latch ? 1 : 0;
    _queue = queue;
    _compQue = queue;
    _source = SOURCE_COMPARATOR;
    _writeConfig();
}

void ADS111xBase::_disableComparator() {
    _compQue = QUE_DISABLED;
    _source = 0;
    _writeConfig();
}

void ADS111xBase::_setLowThresholdRaw(int16_t raw) {
    _loRaw = raw;
    _writeReg(REG_LO_THRESH, (uint16_t)raw);
}

void ADS111xBase::_setHighThresholdRaw(int16_t raw) {
    _hiRaw = raw;
    _writeReg(REG_HI_THRESH, (uint16_t)raw);
}

int16_t ADS111xBase::_voltsToCode(float volts) const {
    float steps = volts / _lsb();
    long code = steps >= 0 ? (long)(steps + 0.5f) : -(long)(-steps + 0.5f);
    if (code > 32767) code = 32767;
    if (code < -32768) code = -32768;
    return (int16_t)code;
}

void ADS111xBase::_setThresholds(float low, float high) {
    if (high <= low) return;
    _setLowThresholdRaw(_voltsToCode(low));
    _setHighThresholdRaw(_voltsToCode(high));
}

void ADS111xBase::_getThresholdsRaw(int16_t* low, int16_t* high) {
    *low = (int16_t)_readReg(REG_LO_THRESH);
    *high = (int16_t)_readReg(REG_HI_THRESH);
}

void ADS111xBase::_clearAlert() { _readReg(REG_CONVERSION); }

void ADS111xBase::_enableInterrupt(uint8_t source) {
    if (source == SOURCE_CONVERSION_READY) {
        _writeReg(REG_HI_THRESH, 0x8000);
        _writeReg(REG_LO_THRESH, 0x0000);
        _compQue = 0;
        _source = SOURCE_CONVERSION_READY;
        _writeConfig();
    } else if (source == SOURCE_COMPARATOR) {
        _setLowThresholdRaw(_loRaw);
        _setHighThresholdRaw(_hiRaw);
        _configureComparator(_compMode, _compPol, _compLat != 0, _queue);
    }
}

void ADS111xBase::_disableInterrupt() { _disableComparator(); }

uint8_t ADS111xBase::_pollInterrupt() {
    if (_source == 0) return 0;
    InputPin* pin = _connection.intPin();
    if (!pin) pin = _intPinUsed;
    bool asserted;
    if (pin) asserted = (pin->isHigh() == (_compPol != 0));
    else if (_source == SOURCE_CONVERSION_READY) asserted = (_readReg(REG_CONFIG) & CFG_OS) != 0;
    else asserted = false;
    if (!asserted) return 0;
    if (_source == SOURCE_COMPARATOR && _compLat) _clearAlert();
    return _source;
}

void ADS111xBase::_onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&ADS111xBase::_edgeTrampoline, _compPol ? InputPin::kRising : InputPin::kFalling);
}

void ADS111xBase::_offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&ADS111xBase::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void ADS111xBase::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void ADS111xBase::_handleEdge() {
    uint8_t status = _pollInterrupt();
    if (_callback) _callback(status);
}
