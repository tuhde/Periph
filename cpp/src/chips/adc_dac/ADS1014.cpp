#include "ADS1014.h"
#include "../../connection/Register.h"

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
const float FULL_SCALE[6] = { 6.144f, 4.096f, 2.048f, 1.024f, 0.512f, 0.256f };
const uint16_t SPS[7] = { 128, 250, 490, 920, 1600, 2400, 3300 };
const uint16_t RESET_CONFIG_WORD = 0x8583;
const uint16_t RESET_LO_WORD = 0x8000;
const uint16_t RESET_HI_WORD = 0x7FFF;
}  // namespace

// ADS1014Minimal

ADS1014Minimal::ADS1014Minimal(RegisterConnection& connection) : _connection(connection) {}

uint16_t ADS1014Minimal::_readReg(uint8_t reg) {
    uint8_t buf[2];
    _connection.read(reg, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void ADS1014Minimal::_writeReg(uint8_t reg, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(reg, buf, 2);
}

uint8_t ADS1014Minimal::_compQue() const {
    if (_source == 0x01) return 0;
    if (_source == 0x02) return _queue;
    return QUEUE_DISABLE;
}

uint16_t ADS1014Minimal::_configWord(bool osBit, bool continuous) const {
    uint16_t word = osBit ? CFG_OS : 0;
    word |= (uint16_t)(_pga << 9);
    if (!continuous) word |= CFG_MODE_SINGLE;
    word |= (uint16_t)(_dr << 5);
    word |= (uint16_t)(_compMode << 4);
    word |= (uint16_t)(_compPol << 3);
    word |= (uint16_t)((_compLat ? 1 : 0) << 2);
    word |= _compQue();
    return word;
}

int16_t ADS1014Minimal::_readConversion() {
    return (int16_t)(toSigned(_readReg(REG_CONVERSION), 16) >> 4);
}

float ADS1014Minimal::_lsb() const { return FULL_SCALE[_pga] / 2048.0f; }

int16_t ADS1014Minimal::readRaw() {
    _writeReg(REG_CONFIG, _configWord(true, false));
    _continuous = false;
    for (int i = 0; i < 100; i++) {
        if (_readReg(REG_CONFIG) & CFG_OS) break;
        periph_delay_ms(1);
    }
    return _readConversion();
}

float ADS1014Minimal::readVoltage() { return (float)readRaw() * _lsb(); }

// ADS1014Full

ADS1014Full* ADS1014Full::_activeInstance = nullptr;

ADS1014Full::ADS1014Full(RegisterConnection& connection) : ADS1014Minimal(connection) {}

void ADS1014Full::_writeConfig() { _writeReg(REG_CONFIG, _configWord(false, _continuous)); }

bool ADS1014Full::setGain(uint8_t pga) {
    if (pga > PGA_0_256) return false;
    _pga = pga;
    if (_continuous) _writeConfig();
    return true;
}

uint8_t ADS1014Full::getGain() { return _pga; }

float ADS1014Full::fullScale() { return FULL_SCALE[_pga]; }

float ADS1014Full::lsb() { return _lsb(); }

bool ADS1014Full::setDataRate(uint8_t dr) {
    if (dr > DR_3300) return false;
    _dr = dr;
    if (_continuous) _writeConfig();
    return true;
}

uint8_t ADS1014Full::getDataRate() { return _dr; }

uint16_t ADS1014Full::dataRateSps() { return SPS[_dr]; }

void ADS1014Full::startContinuous() {
    _continuous = true;
    _writeConfig();
}

int16_t ADS1014Full::readContinuousRaw() { return _readConversion(); }

float ADS1014Full::readContinuous() { return (float)_readConversion() * _lsb(); }

void ADS1014Full::stopContinuous() {
    _continuous = false;
    _writeConfig();
}

bool ADS1014Full::isConverting() { return (_readReg(REG_CONFIG) & CFG_OS) == 0; }

uint16_t ADS1014Full::readConfig() { return _readReg(REG_CONFIG); }

void ADS1014Full::reset() {
    _writeReg(REG_CONFIG, RESET_CONFIG_WORD);
    _writeReg(REG_LO_THRESH, RESET_LO_WORD);
    _writeReg(REG_HI_THRESH, RESET_HI_WORD);
    _pga = PGA_2_048;
    _dr = DR_1600;
    _continuous = false;
    _compMode = COMP_TRADITIONAL;
    _compPol = ACTIVE_LOW;
    _compLat = false;
    _queue = QUEUE_1;
    _source = 0;
    _loRaw = -2048;
    _hiRaw = 2047;
}

bool ADS1014Full::configureComparator(uint8_t mode, uint8_t polarity, bool latch, uint8_t queue) {
    if (mode > COMP_WINDOW || polarity > ACTIVE_HIGH || queue > QUEUE_4) return false;
    _compMode = mode;
    _compPol = polarity;
    _compLat = latch;
    _queue = queue;
    _source = SOURCE_COMPARATOR;
    _writeConfig();
    return true;
}

void ADS1014Full::disableComparator() {
    _source = 0;
    _writeConfig();
}

bool ADS1014Full::_writeThreshold(uint8_t reg, int16_t raw) {
    if (raw < -2048 || raw > 2047) return false;
    _writeReg(reg, (uint16_t)(((uint16_t)raw & 0x0FFF) << 4));
    return true;
}

bool ADS1014Full::setLowThresholdRaw(int16_t raw) {
    if (!_writeThreshold(REG_LO_THRESH, raw)) return false;
    _loRaw = raw;
    return true;
}

bool ADS1014Full::setHighThresholdRaw(int16_t raw) {
    if (!_writeThreshold(REG_HI_THRESH, raw)) return false;
    _hiRaw = raw;
    return true;
}

bool ADS1014Full::setThresholds(float low, float high) {
    if (high <= low) return false;
    float step = _lsb();
    auto toCode = [step](float volts) -> int16_t {
        float c = volts / step;
        int32_t code = (int32_t)(c >= 0.0f ? c + 0.5f : c - 0.5f);
        if (code < -2048) return -2048;
        if (code > 2047) return 2047;
        return (int16_t)code;
    };
    setLowThresholdRaw(toCode(low));
    setHighThresholdRaw(toCode(high));
    return true;
}

void ADS1014Full::getThresholdsRaw(int16_t* low, int16_t* high) {
    if (low) *low = (int16_t)(toSigned(_readReg(REG_LO_THRESH), 16) >> 4);
    if (high) *high = (int16_t)(toSigned(_readReg(REG_HI_THRESH), 16) >> 4);
}

void ADS1014Full::clearAlert() { _readReg(REG_CONVERSION); }

bool ADS1014Full::enableInterrupt(uint8_t source) {
    if (source == SOURCE_CONVERSION_READY) {
        _writeReg(REG_HI_THRESH, 0x8000);
        _writeReg(REG_LO_THRESH, 0x0000);
    } else if (source == SOURCE_COMPARATOR) {
        if (_hiRaw <= _loRaw) return false;
        _writeReg(REG_LO_THRESH, (uint16_t)(((uint16_t)_loRaw & 0x0FFF) << 4));
        _writeReg(REG_HI_THRESH, (uint16_t)(((uint16_t)_hiRaw & 0x0FFF) << 4));
    } else {
        return false;
    }
    _source = source;
    _writeConfig();
    return true;
}

void ADS1014Full::disableInterrupt(uint8_t source) {
    (void)source;
    _source = 0;
    _writeConfig();
}

uint8_t ADS1014Full::pollInterrupt() {
    if (_source == 0) return 0;
    InputPin* pin = _intPinUsed ? _intPinUsed : _connection.intPin();
    if (pin) {
        bool asserted = pin->isHigh() == (_compPol == ACTIVE_HIGH);
        if (!asserted) return 0;
        if (_source == SOURCE_COMPARATOR && _compLat) clearAlert();
        return _source;
    }
    if (_source == SOURCE_CONVERSION_READY && !_continuous && (_readReg(REG_CONFIG) & CFG_OS))
        return SOURCE_CONVERSION_READY;
    return 0;
}

void ADS1014Full::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&ADS1014Full::_edgeTrampoline,
                _compPol == ACTIVE_HIGH ? InputPin::kRising : InputPin::kFalling);
}

void ADS1014Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&ADS1014Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void ADS1014Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void ADS1014Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (_callback && status) _callback(status);
}
