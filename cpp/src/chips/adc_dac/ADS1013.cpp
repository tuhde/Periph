#include "ADS1013.h"
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
const uint16_t SPS[7] = { 128, 250, 490, 920, 1600, 2400, 3300 };
const uint16_t RESET_CONFIG_WORD = 0x8583;
}  // namespace

// ADS1013Minimal

ADS1013Minimal::ADS1013Minimal(RegisterConnection& connection) : _connection(connection) {}

uint16_t ADS1013Minimal::_readReg(uint8_t reg) {
    uint8_t buf[2];
    _connection.read(reg, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void ADS1013Minimal::_writeReg(uint8_t reg, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(reg, buf, 2);
}

uint16_t ADS1013Minimal::_configWord(bool osBit, bool continuous) const {
    uint16_t word = (uint16_t)(CFG_RESERVED | (_dr << 5));
    if (osBit) word |= CFG_OS;
    if (!continuous) word |= CFG_MODE_SINGLE;
    return word;
}

int16_t ADS1013Minimal::_readConversion() {
    return (int16_t)(toSigned(_readReg(REG_CONVERSION), 16) >> 4);
}

int16_t ADS1013Minimal::readRaw() {
    _writeReg(REG_CONFIG, _configWord(true, false));
    _continuous = false;
    for (int i = 0; i < 100; i++) {
        if (_readReg(REG_CONFIG) & CFG_OS) break;
        periph_delay_ms(1);
    }
    return _readConversion();
}

float ADS1013Minimal::readVoltage() { return (float)readRaw() * 0.001f; }

// ADS1013Full

ADS1013Full::ADS1013Full(RegisterConnection& connection) : ADS1013Minimal(connection) {}

void ADS1013Full::_writeConfig() { _writeReg(REG_CONFIG, _configWord(false, _continuous)); }

float ADS1013Full::fullScale() { return 2.048f; }

float ADS1013Full::lsb() { return 0.001f; }

bool ADS1013Full::setDataRate(uint8_t dr) {
    if (dr > DR_3300) return false;
    _dr = dr;
    if (_continuous) _writeConfig();
    return true;
}

uint8_t ADS1013Full::getDataRate() { return _dr; }

uint16_t ADS1013Full::dataRateSps() { return SPS[_dr]; }

void ADS1013Full::startContinuous() {
    _continuous = true;
    _writeConfig();
}

int16_t ADS1013Full::readContinuousRaw() { return _readConversion(); }

float ADS1013Full::readContinuous() { return (float)_readConversion() * 0.001f; }

void ADS1013Full::stopContinuous() {
    _continuous = false;
    _writeConfig();
}

bool ADS1013Full::isConverting() { return (_readReg(REG_CONFIG) & CFG_OS) == 0; }

uint16_t ADS1013Full::readConfig() { return _readReg(REG_CONFIG); }

void ADS1013Full::reset() {
    _writeReg(REG_CONFIG, RESET_CONFIG_WORD);
    _dr = DR_1600;
    _continuous = false;
}
