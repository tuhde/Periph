#include "DRV2605.h"
#include <math.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

// Out-of-class definitions for ODR-used static constexpr members (pre-C++17).
constexpr uint8_t DRV2605Minimal::I2C_ADDRESS;
constexpr uint8_t DRV2605Minimal::DEVICE_ID;

static const float SAMPLE_TIME_S[4] = {150e-6f, 200e-6f, 250e-6f, 300e-6f};
static const uint8_t PEAK_TIMES_MS[4] = {10, 20, 30, 40};
static const uint8_t FILTERS_HZ[4] = {100, 125, 150, 200};

// DRV2605Minimal

DRV2605Minimal::DRV2605Minimal(RegisterConnection& connection, bool lra) : _connection(connection) {
    if (!_resetChip()) return;
    if ((_readReg(REG_STATUS) >> 5) != DEVICE_ID) return;
    _writeMode(MODE_INTERNAL_TRIGGER);
    if (lra) {
        _writeReg(REG_FEEDBACK, 0xB6);
        _writeReg(REG_LIBRARY, 0x06);
    } else {
        _writeReg(REG_FEEDBACK, 0x36);
        _writeReg(REG_LIBRARY, 0x01);
    }
    _ready = true;
}

void DRV2605Minimal::_writeReg(uint8_t reg, uint8_t value) { _connection.write(reg, &value, 1); }

uint8_t DRV2605Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1];
    _connection.read(reg, buf, 1);
    return buf[0];
}

void DRV2605Minimal::_updateReg(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t old = _readReg(reg);
    _writeReg(reg, (uint8_t)((old & ~mask) | (value & mask)));
}

void DRV2605Minimal::_writeMode(uint8_t mode, bool standby) {
    _writeReg(REG_MODE, (uint8_t)((standby ? MODE_STANDBY : 0) | mode));
    _mode = mode;
    _standby = standby;
}

bool DRV2605Minimal::_resetChip() {
    _writeReg(REG_MODE, MODE_DEV_RESET);
    _mode = MODE_INTERNAL_TRIGGER;
    _standby = true;
    periph_delay_ms(2);
    for (int i = 0; i < 100; i++) {
        if (!(_readReg(REG_MODE) & MODE_DEV_RESET)) return true;
        periph_delay_ms(1);
    }
    return false;
}

void DRV2605Minimal::_ensureInternal() {
    if (_mode != MODE_INTERNAL_TRIGGER || _standby) _writeMode(MODE_INTERNAL_TRIGGER);
}

uint8_t DRV2605Minimal::_clampCode(float value) {
    int code = (int)(value + 0.5f);
    if (code < 0) return 0;
    if (code > 255) return 255;
    return (uint8_t)code;
}

bool DRV2605Minimal::playEffect(uint8_t effectId) {
    if (effectId < 1 || effectId > 123) return false;
    _ensureInternal();
    uint8_t slots[2] = {effectId, 0};
    _connection.write(REG_SEQ1, slots, 2);
    _writeReg(REG_GO, 1);
    return true;
}

void DRV2605Minimal::stop() { _writeReg(REG_GO, 0); }

bool DRV2605Minimal::isPlaying() { return (_readReg(REG_GO) & 0x01) != 0; }

// DRV2605Full

DRV2605Full::DRV2605Full(RegisterConnection& connection, bool lra) : DRV2605Minimal(connection, lra) {}

bool DRV2605Full::playSequence(const SequenceStep* items, size_t count) {
    if (count < 1 || count > 8) return false;
    uint8_t data[8];
    size_t n = 0;
    for (size_t i = 0; i < count; i++) {
        if (items[i].isWait) {
            if (items[i].value > 1270) return false;
            data[n++] = (uint8_t)(0x80 | (uint8_t)((items[i].value + 5) / 10));
        } else {
            if (items[i].value < 1 || items[i].value > 123) return false;
            data[n++] = (uint8_t)items[i].value;
        }
    }
    if (n < 8) data[n++] = 0;
    _ensureInternal();
    _connection.write(REG_SEQ1, data, n);
    _writeReg(REG_GO, 1);
    return true;
}

bool DRV2605Full::waitDone(uint32_t timeoutMs) {
    uint32_t waited = 0;
    while (isPlaying()) {
        if (waited >= timeoutMs) return false;
        periph_delay_ms(2);
        waited += 2;
    }
    return true;
}

void DRV2605Full::stop() {
    DRV2605Minimal::stop();
    if (_mode == MODE_RTP) {
        _writeReg(REG_RTP_INPUT, 0);
        _writeMode(MODE_INTERNAL_TRIGGER, _standby);
    }
}

bool DRV2605Full::setLibrary(uint8_t library) {
    if (library > 6) return false;
    _updateReg(REG_LIBRARY, 0x07, library);
    return true;
}

bool DRV2605Full::setMode(uint8_t mode) {
    if (mode > 7) return false;
    _writeMode(mode);
    return true;
}

void DRV2605Full::standby() { _writeMode(_mode, true); }
void DRV2605Full::wake() { _writeMode(_mode, false); }
bool DRV2605Full::reset() { return _resetChip(); }
void DRV2605Full::setHiZ(bool enabled) { _updateReg(REG_LIBRARY, 0x10, enabled ? 0x10 : 0); }

bool DRV2605Full::_isLra() { return (_readReg(REG_FEEDBACK) & 0x80) != 0; }

bool DRV2605Full::_isOpenLoop(bool lra) { return (_readReg(REG_CONTROL3) & (lra ? 0x01 : 0x20)) != 0; }

void DRV2605Full::setActuator(bool lra) { _updateReg(REG_FEEDBACK, 0x80, lra ? 0x80 : 0); }

void DRV2605Full::setOpenLoop(bool enabled) {
    uint8_t mask = _isLra() ? 0x01 : 0x20;
    _updateReg(REG_CONTROL3, mask, enabled ? mask : 0);
}

bool DRV2605Full::setBidirectional(bool enabled) {
    if (!enabled && _isOpenLoop(_isLra())) return false;
    _updateReg(REG_CONTROL2, 0x80, enabled ? 0x80 : 0);
    return true;
}

bool DRV2605Full::rtpLevel(float level) {
    if (!(level >= 0.0f && level <= 1.0f)) return false;
    if (_mode != MODE_RTP) _updateReg(REG_CONTROL3, 0x08, 0);
    if (_mode != MODE_RTP || _standby) _writeMode(MODE_RTP);
    _writeReg(REG_RTP_INPUT, (uint8_t)(level * 127.0f + 0.5f));
    return true;
}

void DRV2605Full::rtpWrite(uint8_t value) {
    if (_mode != MODE_RTP || _standby) _writeMode(MODE_RTP);
    _writeReg(REG_RTP_INPUT, value);
}

void DRV2605Full::setRtpFormat(bool isUnsigned) { _updateReg(REG_CONTROL3, 0x08, isUnsigned ? 0x08 : 0); }
void DRV2605Full::setInput(bool analog) { _updateReg(REG_CONTROL3, 0x02, analog ? 0x02 : 0); }

bool DRV2605Full::_voltsToRatedCode(float volts, float lraFreqHz, uint8_t& code) {
    if (!_isLra()) {
        code = _clampCode(volts / 21.33e-3f);
        return true;
    }
    if (lraFreqHz <= 0.0f) return false;
    float tSample = SAMPLE_TIME_S[(_readReg(REG_CONTROL2) >> 4) & 0x03];
    float factor = 1.0f - (4.0f * tSample + 300e-6f) * lraFreqHz;
    if (factor <= 0.0f) return false;
    code = _clampCode(volts * sqrtf(factor) / 20.71e-3f);
    return true;
}

bool DRV2605Full::setRatedVoltage(float volts, float lraFreqHz) {
    uint8_t code;
    if (!_voltsToRatedCode(volts, lraFreqHz, code)) return false;
    _writeReg(REG_RATED_VOLTAGE, code);
    return true;
}

void DRV2605Full::setRatedVoltageCode(uint8_t code) { _writeReg(REG_RATED_VOLTAGE, code); }

bool DRV2605Full::setOverdriveClamp(float volts, float lraFreqHz) {
    bool lra = _isLra();
    bool openLoop = _isOpenLoop(lra);
    uint8_t code;
    if (lra && openLoop) {
        if (lraFreqHz <= 0.0f) return false;
        float factor = 1.0f - lraFreqHz * 800e-6f;
        if (factor <= 0.0f) return false;
        code = _clampCode(volts / (21.33e-3f * sqrtf(factor)));
    } else if (!lra && !openLoop) {
        return false;
    } else {
        code = _clampCode(volts / 21.96e-3f);
    }
    _writeReg(REG_OD_CLAMP, code);
    return true;
}

void DRV2605Full::setOverdriveClampCode(uint8_t code) { _writeReg(REG_OD_CLAMP, code); }

uint8_t DRV2605Full::_driveTimeCode(float ms, bool lra) {
    int code = lra ? (int)((ms - 0.5f) / 0.1f + 0.5f) : (int)((ms - 1.0f) / 0.2f + 0.5f);
    if (code < 0) code = 0;
    if (code > 31) code = 31;
    return (uint8_t)code;
}

void DRV2605Full::setDriveTime(float ms) { _updateReg(REG_CONTROL1, 0x1F, _driveTimeCode(ms, _isLra())); }

bool DRV2605Full::setFeedback(uint8_t brakeFactor, uint8_t loopGain, uint8_t bemfGain) {
    if (brakeFactor > 7 || loopGain > 3 || bemfGain > 3) return false;
    _updateReg(REG_FEEDBACK, 0x7F, (uint8_t)((brakeFactor << 4) | (loopGain << 2) | bemfGain));
    return true;
}

bool DRV2605Full::setAdvanced(bool startupBoost, bool brakeStabilizer, uint8_t sampleTime,
                              uint8_t blankingTime, uint8_t idissTime, bool lraDriveTwice,
                              bool supplyComp, uint8_t noiseGate) {
    if (sampleTime > 3 || blankingTime > 3 || idissTime > 3 || noiseGate > 3) return false;
    _updateReg(REG_CONTROL1, 0x80, startupBoost ? 0x80 : 0);
    _updateReg(REG_CONTROL2, 0x7F, (uint8_t)((brakeStabilizer ? 0x40 : 0) | (sampleTime << 4) |
                                             (blankingTime << 2) | idissTime));
    _updateReg(REG_CONTROL3, 0xD4, (uint8_t)((noiseGate << 6) | (supplyComp ? 0 : 0x10) |
                                             (lraDriveTwice ? 0x04 : 0)));
    return true;
}

bool DRV2605Full::setTimeOffsets(int16_t overdriveMs, int16_t sustainPosMs, int16_t sustainNegMs,
                                 int16_t brakeMs) {
    int16_t values[4] = {overdriveMs, sustainPosMs, sustainNegMs, brakeMs};
    uint8_t data[4];
    for (int i = 0; i < 4; i++) {
        int steps = values[i] >= 0 ? (values[i] + 2) / 5 : -((-values[i] + 2) / 5);
        if (steps < -128 || steps > 127) return false;
        data[i] = (uint8_t)(int8_t)steps;
    }
    _connection.write(REG_ODT, data, 4);
    return true;
}

DRV2605Full::Calibration DRV2605Full::calibrate(bool lra, float ratedVoltage, float overdriveVoltage,
                                                float lraFreqHz) {
    Calibration result = {false, 0, 0, 0};
    if (lra && lraFreqHz <= 0.0f) return result;
    bool wasStandby = _standby;
    _writeMode(MODE_AUTO_CALIBRATION);
    uint8_t bemfGain = _readReg(REG_FEEDBACK) & 0x03;
    _writeReg(REG_FEEDBACK, (uint8_t)((lra ? 0x80 : 0) | (2 << 4) | (2 << 2) | bemfGain));
    _updateReg(REG_CONTROL2, 0x3F, (3 << 4) | (1 << 2) | 1);  // SAMPLE_TIME 3, BLANKING 1, IDISS 1
    if (lra) _updateReg(REG_CONTROL1, 0x1F, _driveTimeCode(500.0f / lraFreqHz, true));
    uint8_t ratedCode;
    if (!_voltsToRatedCode(ratedVoltage, lraFreqHz, ratedCode)) {
        _writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
        return result;
    }
    _writeReg(REG_RATED_VOLTAGE, ratedCode);
    _writeReg(REG_OD_CLAMP, _clampCode(overdriveVoltage / 21.96e-3f));
    _updateReg(REG_CONTROL4, 0x31, 3 << 4);  // AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
    _writeReg(REG_GO, 1);
    bool finished = waitDone(3000);
    bool diagFailed = (_readReg(REG_STATUS) & 0x08) != 0;
    result = getCalibration();
    result.ok = finished && !diagFailed;
    _writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
    return result;
}

DRV2605Full::Calibration DRV2605Full::getCalibration() {
    uint8_t buf[2];
    _connection.read(REG_A_CAL_COMP, buf, 2);
    Calibration c = {true, buf[0], buf[1], (uint8_t)(_readReg(REG_FEEDBACK) & 0x03)};
    return c;
}

bool DRV2605Full::setCalibration(uint8_t comp, uint8_t bemf, uint8_t bemfGain) {
    if (bemfGain > 3) return false;
    uint8_t data[2] = {comp, bemf};
    _connection.write(REG_A_CAL_COMP, data, 2);
    _updateReg(REG_FEEDBACK, 0x03, bemfGain);
    return true;
}

bool DRV2605Full::diagnose() {
    bool wasStandby = _standby;
    _writeMode(MODE_DIAGNOSTICS);
    _writeReg(REG_GO, 1);
    bool finished = waitDone(3000);
    bool diagFailed = (_readReg(REG_STATUS) & 0x08) != 0;
    _writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
    return finished && !diagFailed;
}

bool DRV2605Full::setAudioToVibe(bool acCouple, uint8_t peakTimeMs, uint8_t filterHz, float minInputV,
                                 float maxInputV, float minDrive, float maxDrive) {
    int peak = -1, filter = -1;
    for (int i = 0; i < 4; i++) {
        if (PEAK_TIMES_MS[i] == peakTimeMs) peak = i;
        if (FILTERS_HZ[i] == filterHz) filter = i;
    }
    if (peak < 0 || filter < 0) return false;
    if (!(minInputV >= 0.0f && minInputV <= 1.8f && maxInputV >= 0.0f && maxInputV <= 1.8f &&
          minDrive >= 0.0f && minDrive <= 1.0f && maxDrive >= 0.0f && maxDrive <= 1.0f))
        return false;
    uint8_t data[5] = {(uint8_t)((peak << 2) | filter), _clampCode(minInputV * 255.0f / 1.8f),
                       _clampCode(maxInputV * 255.0f / 1.8f), _clampCode(minDrive * 255.0f),
                       _clampCode(maxDrive * 255.0f)};
    _connection.write(REG_ATH_CTRL, data, 5);
    _updateReg(REG_CONTROL1, 0x20, acCouple ? 0x20 : 0);
    _updateReg(REG_CONTROL3, 0x02, 0x02);
    _writeMode(MODE_AUDIO_TO_VIBE);
    return true;
}

DRV2605Full::Status DRV2605Full::readStatus() {
    uint8_t s = _readReg(REG_STATUS);
    Status st = {(s & 0x08) != 0, (s & 0x04) != 0, (s & 0x02) != 0, (s & 0x01) != 0};
    return st;
}

uint8_t DRV2605Full::readDeviceId() { return (uint8_t)(_readReg(REG_STATUS) >> 5); }

float DRV2605Full::readSupplyVoltage() { return _readReg(REG_VBAT) * 5.6f / 255.0f; }

float DRV2605Full::readLraFrequency() {
    uint8_t raw = _readReg(REG_LRA_PERIOD);
    if (raw == 0) return 0.0f;
    return 1e6f / (raw * 98.46f);
}

bool DRV2605Full::programOtp(bool confirm) {
    if (!confirm || otpProgrammed()) return false;
    _updateReg(REG_CONTROL4, 0x01, 0x01);
    for (int i = 0; i < 100; i++) {
        periph_delay_ms(10);
        if (otpProgrammed()) return true;
    }
    return false;
}

bool DRV2605Full::otpProgrammed() { return (_readReg(REG_CONTROL4) & 0x04) != 0; }
