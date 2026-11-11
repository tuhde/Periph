#include "ADS7828.h"

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
const float INTERNAL_VREF = 2.5f;
const unsigned REF_SETTLE_MS = 1;

// C2:C0 for single-ended channel n — the encoding is interleaved.
uint8_t singleEndedCode(int channel) { return (uint8_t)(((channel & 1) << 2) | (channel >> 1)); }
int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}  // namespace

// ADS7828Minimal

ADS7828Minimal::ADS7828Minimal(RegisterConnection& connection, float vref)
    : _connection(connection), _internal(true), _refOn(false), _vref(INTERNAL_VREF), _pd(PD_REF_ON_ADC_ON) {
    _selectReference(vref);
}

void ADS7828Minimal::begin() {
    if (_internal) _enableReference();
}

void ADS7828Minimal::_selectReference(float vref) {
    if (vref <= 0.0f) {
        _internal = true;
        _vref = INTERNAL_VREF;
        _pd = PD_REF_ON_ADC_ON;
    } else {
        _internal = false;
        _vref = vref;
        _pd = PD_ALL_DOWN;
    }
    _refOn = false;
}

void ADS7828Minimal::_enableReference() {
    uint8_t cmd = CMD_REF_ON;
    _connection.write(&cmd, 1);
    periph_delay_ms(REF_SETTLE_MS);
    _refOn = true;
}

uint16_t ADS7828Minimal::_convert(bool singleEnded, uint8_t code) {
    if (_internal && !_refOn) _enableReference();
    uint8_t command = (uint8_t)(((singleEnded ? 1 : 0) << 7) | (code << 4) | (_pd << 2));
    uint8_t buf[2] = { 0, 0 };
    _connection.read(command, buf, 2);
    return (uint16_t)(((buf[0] & 0x0F) << 8) | buf[1]);
}

uint16_t ADS7828Minimal::readRaw(int channel) {
    return _convert(true, singleEndedCode(clampInt(channel, 0, 7)));
}

float ADS7828Minimal::readVoltage(int channel) {
    return readRaw(channel) / 4096.0f * _vref;
}

void ADS7828Minimal::readAll(float* out) {
    for (int ch = 0; ch < 8; ch++) out[ch] = readVoltage(ch);
}

// ADS7828Full

ADS7828Full::ADS7828Full(RegisterConnection& connection, float vref) : ADS7828Minimal(connection, vref) {}

uint16_t ADS7828Full::readDifferentialRaw(int pair, bool inverted) {
    uint8_t p = (uint8_t)clampInt(pair, 0, 3);
    return _convert(false, inverted ? (uint8_t)(4 | p) : p);
}

float ADS7828Full::readDifferential(int pair, bool inverted) {
    return readDifferentialRaw(pair, inverted) / 4096.0f * _vref;
}

float ADS7828Full::readSignedDifferential(int pair) {
    int normal = readDifferentialRaw(pair, false);
    int inverted = readDifferentialRaw(pair, true);
    return (normal - inverted) / 4096.0f * _vref;
}

void ADS7828Full::setReference(float vref) {
    _selectReference(vref);
    if (_internal) _enableReference();
}

float ADS7828Full::getReference() const { return _vref; }

bool ADS7828Full::usesInternalReference() const { return _internal; }

bool ADS7828Full::setPowerMode(uint8_t pd) {
    if (pd > 3) return false;
    if (_internal && !(pd & 2)) return false;
    _pd = pd;
    return true;
}

void ADS7828Full::powerDown() {
    uint8_t cmd = CMD_POWER_DOWN;
    _connection.write(&cmd, 1);
    _refOn = false;
}

void ADS7828Full::readScan(const uint8_t* channels, size_t count, uint16_t* out) {
    for (size_t i = 0; i < count; i++) out[i] = readRaw(channels[i]);
}

float ADS7828Full::lsb() const { return _vref / 4096.0f; }
