#include "FXOS8700.h"
#include <math.h>
#include <string.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

namespace {
const float kPi = 3.14159265358979f;

// System output data rates selected by CTRL_REG1.dr (Hz).
const float ODR_HZ[8] = { 800.0f, 400.0f, 200.0f, 100.0f, 50.0f, 12.5f, 6.25f, 1.5625f };

// Debounce time steps in ms (also PULSE_TMLT with the pulse LPF on), indexed
// [mods][row]; row is the CTRL_REG1.dr code, shifted by one in hybrid mode.
const float STEP_MS[4][8] = {
    { 1.25f, 2.5f, 5.0f, 10.0f, 20.0f, 20.0f, 20.0f, 20.0f },
    { 1.25f, 2.5f, 5.0f, 10.0f, 20.0f, 80.0f, 80.0f, 80.0f },
    { 1.25f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f },
    { 1.25f, 2.5f, 5.0f, 10.0f, 20.0f, 80.0f, 160.0f, 160.0f },
};

// PULSE_TMLT steps with the pulse low-pass filter off (LTCY / WIND are twice).
const float PULSE_STEP_NO_LPF_MS[4][8] = {
    { 0.625f, 0.625f, 1.25f, 2.5f, 5.0f, 5.0f, 5.0f, 5.0f },
    { 0.625f, 0.625f, 1.25f, 2.5f, 5.0f, 20.0f, 20.0f, 20.0f },
    { 0.625f, 0.625f, 0.625f, 0.625f, 0.625f, 0.625f, 0.625f, 0.625f },
    { 0.625f, 1.25f, 2.5f, 5.0f, 10.0f, 40.0f, 40.0f, 40.0f },
};

struct PlThreshold { uint8_t deg; uint8_t code; };
const PlThreshold PL_THRESHOLDS[] = {
    { 15, 0x07 }, { 20, 0x09 }, { 30, 0x0C }, { 35, 0x0D }, { 40, 0x0F },
    { 45, 0x10 }, { 55, 0x13 }, { 60, 0x14 }, { 70, 0x17 }, { 75, 0x19 },
};

struct AslpRate { float hz; uint8_t code; };
const AslpRate ASLP_RATES[] = { { 50.0f, 0 }, { 12.5f, 1 }, { 6.25f, 2 }, { 1.56f, 3 } };

int32_t clampInt(int32_t v, int32_t lo, int32_t hi) { return v < lo ? lo : (v > hi ? hi : v); }
}  // namespace

// FXOS8700Minimal

FXOS8700Minimal::FXOS8700Minimal(RegisterConnection& connection) : _conn(connection) {
    init();
}

uint8_t FXOS8700Minimal::_readReg(uint8_t reg) {
    uint8_t value = 0;
    _conn.read(reg, &value, 1);
    return value;
}

void FXOS8700Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _conn.write(reg, &value, 1);
}

void FXOS8700Minimal::_modify(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t cur = _readReg(reg);
    _writeReg(reg, (uint8_t)((cur & ~mask) | (value & mask)));
}

uint8_t FXOS8700Minimal::_whoAmI() {
    // Retry on a reported NACK for up to 10 ms (the chip is busy right after a reset).
    uint8_t value = 0;
    for (int attempt = 0; attempt < 10; attempt++) {
        value = _readReg(REG_WHO_AM_I);
        if (_conn.ok()) return value;
        delay(1);
    }
    return value;
}

void FXOS8700Minimal::_softReset() {
    // The chip resets its I²C interface mid-transaction and does not ACK the
    // write; the connection layer reports no error for that.
    _writeReg(REG_CTRL2, CTRL2_RST);
    delay(2);
}

bool FXOS8700Minimal::init() {
    delay(1);
    uint8_t who = _whoAmI();
    _found = (who == 0xC7 || who == 0xC4);
    if (!_found) return false;
    _softReset();
    who = _whoAmI();
    _found = (who == 0xC7 || who == 0xC4);
    if (!_found) return false;
    _writeReg(REG_CTRL1, 0x00);
    _writeReg(REG_M_CTRL1, 0x1F);
    _writeReg(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC);
    _writeReg(REG_XYZ_DATA_CFG, 0x00);
    _writeReg(REG_CTRL2, 0x00);
    _writeReg(REG_CTRL1, 0x11);
    _mode = 2;
    _dr = 2;
    _fsG = 2;
    _mods = 0;
    _lnoise = false;
    _fRead = false;
    _fifoMode = 0;
    _hyb = true;
    delay(_turnOnMs());
    return true;
}

float FXOS8700Minimal::_odrHz() const { return ODR_HZ[_dr]; }

uint32_t FXOS8700Minimal::_turnOnMs() const {
    return (uint32_t)(2000.0f / _odrHz()) + 3;
}

float FXOS8700Minimal::_accelScale() const {
    float cpg = _fsG == 2 ? 4096.0f : (_fsG == 4 ? 2048.0f : 1024.0f);
    return _fRead ? cpg / 64.0f : cpg;
}

void FXOS8700Minimal::_decodeAccel(const uint8_t* data, int16_t& x, int16_t& y, int16_t& z) const {
    if (_fRead) {
        x = (int8_t)data[0];
        y = (int8_t)data[1];
        z = (int8_t)data[2];
        return;
    }
    x = (int16_t)((int16_t)(uint16_t)((data[0] << 8) | data[1]) >> 2);
    y = (int16_t)((int16_t)(uint16_t)((data[2] << 8) | data[3]) >> 2);
    z = (int16_t)((int16_t)(uint16_t)((data[4] << 8) | data[5]) >> 2);
}

void FXOS8700Minimal::_decodeMag(const uint8_t* data, int16_t& x, int16_t& y, int16_t& z) {
    x = (int16_t)(uint16_t)((data[0] << 8) | data[1]);
    y = (int16_t)(uint16_t)((data[2] << 8) | data[3]);
    z = (int16_t)(uint16_t)((data[4] << 8) | data[5]);
}

void FXOS8700Minimal::_readAccelCounts(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6] = { 0 };
    _conn.read(REG_OUT_X_MSB, buf, _fRead ? 3 : 6);
    _decodeAccel(buf, x, y, z);
}

void FXOS8700Minimal::readAccel(float& x, float& y, float& z) {
    int16_t rx, ry, rz;
    _readAccelCounts(rx, ry, rz);
    float scale = _accelScale();
    x = rx / scale;
    y = ry / scale;
    z = rz / scale;
}

void FXOS8700Minimal::readMag(float& x, float& y, float& z) {
    uint8_t buf[6];
    _conn.read(REG_M_OUT_X_MSB, buf, 6);
    int16_t rx, ry, rz;
    _decodeMag(buf, rx, ry, rz);
    x = rx * 0.1f;
    y = ry * 0.1f;
    z = rz * 0.1f;
}

// FXOS8700Full

FXOS8700Full* FXOS8700Full::_activeInstance = nullptr;

FXOS8700Full::FXOS8700Full(RegisterConnection& connection) : FXOS8700Minimal(connection) {}

bool FXOS8700Full::_waitStandby() {
    for (int i = 0; i < 350; i++) {
        if ((_readReg(REG_SYSMOD) & 0x03) == 0) return true;
        delay(1);
    }
    return false;
}

bool FXOS8700Full::standby() {
    uint8_t c1 = _readReg(REG_CTRL1);
    _writeReg(REG_CTRL1, (uint8_t)(c1 & ~CTRL1_ACTIVE));
    return _waitStandby();
}

void FXOS8700Full::activate() {
    uint8_t c1 = _readReg(REG_CTRL1);
    _writeReg(REG_CTRL1, (uint8_t)(c1 | CTRL1_ACTIVE));
    delay(_turnOnMs());
}

bool FXOS8700Full::_begin() {
    uint8_t c1 = _readReg(REG_CTRL1);
    if (c1 & CTRL1_ACTIVE) {
        _writeReg(REG_CTRL1, (uint8_t)(c1 & ~CTRL1_ACTIVE));
        _waitStandby();
        return true;
    }
    return false;
}

void FXOS8700Full::_end(bool wasActive) {
    if (wasActive) activate();
}

void FXOS8700Full::reset() {
    _softReset();
    _mode = 0;
    _dr = 0;
    _fsG = 2;
    _mods = 0;
    _lnoise = false;
    _fRead = false;
    _fifoMode = 0;
    _hyb = false;
}

bool FXOS8700Full::setMode(uint8_t mode) {
    if (mode > 2) return false;
    static const uint8_t hms[3] = { 0, 1, 3 };
    bool was = _begin();
    _modify(REG_M_CTRL1, M_CTRL1_HMS_MASK, hms[mode]);
    if (mode == 2) {
        _modify(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC, M_CTRL2_HYB_AUTOINC);
        _hyb = true;
    }
    _mode = mode;
    _end(was);
    return true;
}

bool FXOS8700Full::setRange(uint8_t rangeG) {
    if (rangeG != 2 && rangeG != 4 && rangeG != 8) return false;
    if (rangeG == 8 && _lnoise) return false;
    _modify(REG_XYZ_DATA_CFG, 0x03, rangeG == 2 ? 0 : (rangeG == 4 ? 1 : 2));
    _fsG = rangeG;
    return true;
}

bool FXOS8700Full::setDataRate(float rateHz) {
    int code = -1;
    for (int i = 0; i < 8; i++) {
        if (fabsf(ODR_HZ[i] - rateHz) <= 0.01f * ODR_HZ[i]) code = i;
    }
    if (code < 0) return false;
    bool was = _begin();
    _modify(REG_CTRL1, CTRL1_DR_MASK, (uint8_t)(code << 3));
    _dr = (uint8_t)code;
    _end(was);
    return true;
}

bool FXOS8700Full::setAccelOversampling(uint8_t mode, int8_t sleepMode) {
    if (mode > 3 || sleepMode > 3) return false;
    bool was = _begin();
    _modify(REG_CTRL2, CTRL2_MODS_MASK, mode);
    if (sleepMode >= 0) _modify(REG_CTRL2, CTRL2_SMODS_MASK, (uint8_t)(sleepMode << 3));
    _mods = mode;
    _end(was);
    return true;
}

bool FXOS8700Full::setMagOversampling(uint8_t ratio, int8_t sleepRatio) {
    if (ratio > 7 || sleepRatio > 7) return false;
    _modify(REG_M_CTRL1, M_CTRL1_OS_MASK, (uint8_t)(ratio << 2));
    if (sleepRatio >= 0) _modify(REG_M_CTRL3, 0x70, (uint8_t)(sleepRatio << 4));
    return true;
}

bool FXOS8700Full::setLowNoise(bool enabled) {
    if (enabled && _fsG == 8) return false;
    bool was = _begin();
    _modify(REG_CTRL1, CTRL1_LNOISE, enabled ? CTRL1_LNOISE : 0);
    _lnoise = enabled;
    _end(was);
    return true;
}

bool FXOS8700Full::setResolution(uint8_t bits) {
    if (bits != 14 && bits != 8) return false;
    if (_fifoMode != 0) return false;
    bool was = _begin();
    _modify(REG_CTRL1, CTRL1_F_READ, bits == 8 ? CTRL1_F_READ : 0);
    _fRead = (bits == 8);
    _end(was);
    return true;
}

void FXOS8700Full::readRawAccel(int16_t& x, int16_t& y, int16_t& z) {
    _readAccelCounts(x, y, z);
}

void FXOS8700Full::readRawMag(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _conn.read(REG_M_OUT_X_MSB, buf, 6);
    _decodeMag(buf, x, y, z);
}

bool FXOS8700Full::readHybrid(float& ax, float& ay, float& az, float& mx, float& my, float& mz) {
    if (_mode != 2 || !_hyb) return false;
    uint8_t buf[12] = { 0 };
    size_t nAccel = _fRead ? 3 : 6;
    _conn.read(REG_OUT_X_MSB, buf, nAccel + 6);
    int16_t rx, ry, rz, qx, qy, qz;
    _decodeAccel(buf, rx, ry, rz);
    _decodeMag(buf + nAccel, qx, qy, qz);
    float scale = _accelScale();
    ax = rx / scale;
    ay = ry / scale;
    az = rz / scale;
    mx = qx * 0.1f;
    my = qy * 0.1f;
    mz = qz * 0.1f;
    return true;
}

bool FXOS8700Full::readTemperature(float& celsius) {
    if (_mode == 0) return false;
    celsius = (int8_t)_readReg(REG_TEMP) * 0.96f;
    return true;
}

bool FXOS8700Full::newAccelData() { return (_readReg(REG_STATUS) & STATUS_ZYXDR) != 0; }
bool FXOS8700Full::newMagData() { return (_readReg(REG_M_DR_STATUS) & STATUS_ZYXDR) != 0; }
uint8_t FXOS8700Full::readStatus() { return _readReg(REG_STATUS); }
uint8_t FXOS8700Full::readMagStatus() { return _readReg(REG_M_DR_STATUS); }
uint8_t FXOS8700Full::readSystemMode() { return _readReg(REG_SYSMOD) & 0x03; }

bool FXOS8700Full::setHighPassOutput(bool enabled, uint8_t cutoff) {
    if (cutoff > 3) return false;
    bool was = _begin();
    _modify(REG_XYZ_DATA_CFG, 0x10, enabled ? 0x10 : 0);
    _modify(REG_HP_FILTER_CUTOFF, 0x03, cutoff);
    _end(was);
    return true;
}

void FXOS8700Full::setAccelOffset(float xG, float yG, float zG) {
    uint8_t data[3];
    const float v[3] = { xG, yG, zG };
    for (int i = 0; i < 3; i++) data[i] = (uint8_t)(clampInt(lroundf(v[i] / 0.002f), -128, 127) & 0xFF);
    bool was = _begin();
    _conn.write(REG_OFF_X, data, 3);
    _end(was);
}

void FXOS8700Full::getAccelOffset(float& xG, float& yG, float& zG) {
    uint8_t d[3];
    _conn.read(REG_OFF_X, d, 3);
    xG = (int8_t)d[0] * 0.002f;
    yG = (int8_t)d[1] * 0.002f;
    zG = (int8_t)d[2] * 0.002f;
}

void FXOS8700Full::setMagOffset(float xUt, float yUt, float zUt) {
    uint8_t data[6];
    const float v[3] = { xUt, yUt, zUt };
    for (int i = 0; i < 3; i++) {
        int32_t value = clampInt(lroundf(v[i] / 0.1f), -16384, 16383);
        uint16_t word = (uint16_t)((value << 1) & 0xFFFF);
        data[2 * i] = (uint8_t)(word >> 8);
        data[2 * i + 1] = (uint8_t)(word & 0xFF);
    }
    _conn.write(REG_M_OFF_X_MSB, data, 6);
}

void FXOS8700Full::getMagOffset(float& xUt, float& yUt, float& zUt) {
    uint8_t d[6];
    _conn.read(REG_M_OFF_X_MSB, d, 6);
    float out[3];
    for (int i = 0; i < 3; i++) {
        int16_t word = (int16_t)(uint16_t)((d[2 * i] << 8) | d[2 * i + 1]);
        out[i] = (word >> 1) * 0.1f;
    }
    xUt = out[0];
    yUt = out[1];
    zUt = out[2];
}

void FXOS8700Full::setMagRaw(bool raw) {
    _modify(REG_M_CTRL3, 0x80, raw ? 0x80 : 0);
}

void FXOS8700Full::setMagAutoCalibration(bool enabled) {
    if (enabled) {
        _modify(REG_M_CTRL3, 0x80, 0);
        bool was = _begin();
        _modify(REG_M_CTRL2, 0x10, 0);
        _end(was);
    }
    _modify(REG_M_CTRL1, M_CTRL1_ACAL, enabled ? M_CTRL1_ACAL : 0);
}

void FXOS8700Full::readMagMinMax(float* minUt, float* maxUt) {
    uint8_t d[6];
    int16_t x, y, z;
    _conn.read(REG_MIN_X_MSB, d, 6);
    _decodeMag(d, x, y, z);
    minUt[0] = x * 0.1f;
    minUt[1] = y * 0.1f;
    minUt[2] = z * 0.1f;
    _conn.read(REG_MAX_X_MSB, d, 6);
    _decodeMag(d, x, y, z);
    maxUt[0] = x * 0.1f;
    maxUt[1] = y * 0.1f;
    maxUt[2] = z * 0.1f;
}

void FXOS8700Full::resetMagMinMax() {
    _modify(REG_M_CTRL2, 0x04, 0x04);
}

bool FXOS8700Full::setMagReset(uint8_t mode) {
    if (mode > 3) return false;
    bool was = _begin();
    _modify(REG_M_CTRL2, 0x03, mode);
    _end(was);
    return true;
}

void FXOS8700Full::triggerMagReset() {
    _modify(REG_M_CTRL1, M_CTRL1_RST, M_CTRL1_RST);
}

bool FXOS8700Full::measureMagOnce(float& x, float& y, float& z) {
    if (_mode == 0) return false;
    bool was = _begin();
    _modify(REG_M_CTRL1, M_CTRL1_OST, M_CTRL1_OST);
    bool done = false;
    for (int i = 0; i < 1000; i++) {
        if ((_readReg(REG_M_CTRL1) & M_CTRL1_OST) == 0) { done = true; break; }
        delay(1);
    }
    if (!done) {
        _end(was);
        return false;
    }
    readMag(x, y, z);
    _end(was);
    return true;
}

float FXOS8700Full::heading(float declinationDeg) {
    float ax, ay, az, mx, my, mz;
    if (!(_mode == 2 && _hyb && readHybrid(ax, ay, az, mx, my, mz))) {
        readAccel(ax, ay, az);
        readMag(mx, my, mz);
    }
    float roll = atan2f(ay, az);
    float pitch = atan2f(-ax, ay * sinf(roll) + az * cosf(roll));
    float mx2 = mx * cosf(pitch) + mz * sinf(pitch);
    float my2 = mx * sinf(roll) * sinf(pitch) + my * cosf(roll) - mz * sinf(roll) * cosf(pitch);
    float deg = atan2f(-my2, mx2) * 180.0f / kPi + declinationDeg;
    deg = fmodf(deg, 360.0f);
    if (deg < 0.0f) deg += 360.0f;
    return deg;
}

bool FXOS8700Full::setFifo(uint8_t mode, uint8_t watermark) {
    if (mode > 3 || watermark > 63) return false;
    bool was = _begin();
    if (_fifoMode != 0 && mode != 0 && mode != _fifoMode) _writeReg(REG_F_SETUP, 0x00);
    _writeReg(REG_F_SETUP, (uint8_t)((mode << 6) | watermark));
    _fifoMode = mode;
    _end(was);
    return true;
}

void FXOS8700Full::setFifoTrigger(bool transient, bool orientation, bool pulse, bool motion) {
    uint8_t value = (uint8_t)((transient ? 0x20 : 0) | (orientation ? 0x10 : 0) | (pulse ? 0x08 : 0) | (motion ? 0x04 : 0));
    bool was = _begin();
    _writeReg(REG_TRIG_CFG, value);
    _end(was);
}

uint8_t FXOS8700Full::fifoLevel() { return _readReg(REG_STATUS) & 0x3F; }

void FXOS8700Full::readFifoStatus(bool& overflow, bool& watermark, uint8_t& count) {
    uint8_t v = _readReg(REG_STATUS);
    overflow = (v & 0x80) != 0;
    watermark = (v & 0x40) != 0;
    count = v & 0x3F;
}

size_t FXOS8700Full::readFifo(float* samples, size_t maxSamples) {
    size_t n = fifoLevel();
    if (n > maxSamples) n = maxSamples;
    float scale = _accelScale();
    for (size_t i = 0; i < n; i++) {
        int16_t x, y, z;
        _readAccelCounts(x, y, z);
        samples[3 * i] = x / scale;
        samples[3 * i + 1] = y / scale;
        samples[3 * i + 2] = z / scale;
    }
    return n;
}

void FXOS8700Full::setFifoGate(bool enabled) {
    bool was = _begin();
    _modify(REG_CTRL3, 0x80, enabled ? 0x80 : 0);
    _end(was);
}

float FXOS8700Full::_stepMs() const {
    uint8_t row = (uint8_t)(_dr + (_mode == 2 ? 1 : 0));
    if (row > 7) row = 7;
    return STEP_MS[_mods][row];
}

float FXOS8700Full::_pulseStepMs(bool lpf) const {
    uint8_t row = (uint8_t)(_dr + (_mode == 2 ? 1 : 0));
    if (row > 7) row = 7;
    return lpf ? STEP_MS[_mods][row] : PULSE_STEP_NO_LPF_MS[_mods][row];
}

uint8_t FXOS8700Full::_threshold(float thresholdG) {
    return (uint8_t)clampInt(lroundf(thresholdG / 0.063f), 0, 127);
}

void FXOS8700Full::setFreefallMotion(bool motion, uint8_t axes, float thresholdG, float debounceMs,
                                     bool latch, bool debounceClear) {
    uint8_t cfg = (uint8_t)((latch ? 0x80 : 0) | (motion ? 0x40 : 0) | ((axes & 7) << 3));
    uint8_t ths = (uint8_t)((debounceClear ? 0x80 : 0) | _threshold(thresholdG));
    uint8_t count = (uint8_t)clampInt(lroundf(debounceMs / _stepMs()), 0, 255);
    bool was = _begin();
    _writeReg(REG_A_FFMT_CFG, cfg);
    _writeReg(REG_A_FFMT_THS, ths);
    _writeReg(REG_A_FFMT_COUNT, count);
    _end(was);
}

uint8_t FXOS8700Full::readMotionSource() { return _readReg(REG_A_FFMT_SRC); }

void FXOS8700Full::setTransient(uint8_t axes, float thresholdG, float debounceMs, bool bypassHpf, bool latch) {
    uint8_t cfg = (uint8_t)((latch ? 0x10 : 0) | ((axes & 7) << 1) | (bypassHpf ? 1 : 0));
    uint8_t count = (uint8_t)clampInt(lroundf(debounceMs / _stepMs()), 0, 255);
    bool was = _begin();
    _writeReg(REG_TRANSIENT_CFG, cfg);
    _writeReg(REG_TRANSIENT_THS, _threshold(thresholdG));
    _writeReg(REG_TRANSIENT_COUNT, count);
    _end(was);
}

uint8_t FXOS8700Full::readTransientSource() { return _readReg(REG_TRANSIENT_SRC); }

void FXOS8700Full::setPulse(uint8_t single, uint8_t doubleTap, const float* thresholdG, float timeLimitMs,
                            float latencyMs, float windowMs, bool latch, bool doubleAbort, bool lpf, bool hpf) {
    static const float defaults[3] = { 0.5f, 0.5f, 0.5f };
    const float* t = thresholdG ? thresholdG : defaults;
    uint8_t cfg = (uint8_t)((doubleAbort ? 0x80 : 0) | (latch ? 0x40 : 0));
    for (int i = 0; i < 3; i++) {
        if ((single >> i) & 1) cfg |= (uint8_t)(1 << (2 * i));
        if ((doubleTap >> i) & 1) cfg |= (uint8_t)(2 << (2 * i));
    }
    float step = _pulseStepMs(lpf);
    uint8_t tmlt = (uint8_t)clampInt(lroundf(timeLimitMs / step), 0, 255);
    uint8_t ltcy = (uint8_t)clampInt(lroundf(latencyMs / (2 * step)), 0, 255);
    uint8_t wind = (uint8_t)clampInt(lroundf(windowMs / (2 * step)), 0, 255);
    uint8_t ths[3] = { _threshold(t[0]), _threshold(t[1]), _threshold(t[2]) };
    bool was = _begin();
    _writeReg(REG_PULSE_CFG, cfg);
    _conn.write(REG_PULSE_THSX, ths, 3);
    _writeReg(REG_PULSE_TMLT, tmlt);
    _writeReg(REG_PULSE_LTCY, ltcy);
    _writeReg(REG_PULSE_WIND, wind);
    _modify(REG_HP_FILTER_CUTOFF, 0x30, (uint8_t)((hpf ? 0 : 0x20) | (lpf ? 0x10 : 0)));
    _end(was);
}

uint8_t FXOS8700Full::readPulseSource() { return _readReg(REG_PULSE_SRC); }

bool FXOS8700Full::setOrientation(bool enabled, float debounceMs, uint8_t thresholdDeg, uint8_t hysteresis,
                                  uint8_t zLock, uint8_t backFront) {
    int ths = -1;
    for (size_t i = 0; i < sizeof(PL_THRESHOLDS) / sizeof(PL_THRESHOLDS[0]); i++) {
        if (PL_THRESHOLDS[i].deg == thresholdDeg) ths = PL_THRESHOLDS[i].code;
    }
    if (ths < 0 || hysteresis > 7 || zLock > 7 || backFront > 3) return false;
    if (ths + hysteresis < 1 || ths + hysteresis > 31) return false;
    uint8_t count = (uint8_t)clampInt(lroundf(debounceMs / _stepMs()), 0, 255);
    bool was = _begin();
    _writeReg(REG_PL_CFG, (uint8_t)(0x80 | (enabled ? 0x40 : 0)));
    _writeReg(REG_PL_COUNT, count);
    _writeReg(REG_PL_THS, (uint8_t)((ths << 3) | hysteresis));
    _writeReg(REG_PL_BF_ZCOMP, (uint8_t)((backFront << 6) | zLock));
    _end(was);
    return true;
}

void FXOS8700Full::readOrientation(bool& changed, bool& lockout, uint8_t& portraitLandscape, bool& back) {
    uint8_t v = _readReg(REG_PL_STATUS);
    changed = (v & 0x80) != 0;
    lockout = (v & 0x40) != 0;
    portraitLandscape = (v >> 1) & 0x03;
    back = (v & 0x01) != 0;
}

bool FXOS8700Full::setAutoSleep(bool enabled, float sleepRateHz, float timeoutS, uint8_t wakeSources) {
    int code = -1;
    for (size_t i = 0; i < sizeof(ASLP_RATES) / sizeof(ASLP_RATES[0]); i++) {
        if (fabsf(ASLP_RATES[i].hz - sleepRateHz) <= 0.01f * ASLP_RATES[i].hz) code = ASLP_RATES[i].code;
    }
    if (code < 0) return false;
    if (wakeSources & ~0x3E) return false;
    float step = _dr == 7 ? 0.64f : 0.32f;
    uint8_t count = (uint8_t)clampInt(lroundf(timeoutS / step), 0, 255);
    bool was = _begin();
    _modify(REG_CTRL1, CTRL1_ASLP_MASK, (uint8_t)(code << 6));
    _writeReg(REG_ASLP_COUNT, count);
    _modify(REG_CTRL2, CTRL2_SLPE, enabled ? CTRL2_SLPE : 0);
    _modify(REG_CTRL3, 0x7C, (uint8_t)((wakeSources & 0x3E) << 1));
    _end(was);
    return true;
}

void FXOS8700Full::setInterruptPins(bool activeHigh, bool openDrain) {
    bool was = _begin();
    _modify(REG_CTRL3, 0x03, (uint8_t)((activeHigh ? 0x02 : 0) | (openDrain ? 0x01 : 0)));
    _end(was);
}

bool FXOS8700Full::enableInterrupt(uint8_t source, uint8_t pin) {
    if (pin != 1 && pin != 2) return false;
    bool was = _begin();
    _modify(REG_CTRL4, source, source);
    _modify(REG_CTRL5, source, pin == 1 ? source : 0);
    _end(was);
    return true;
}

void FXOS8700Full::disableInterrupt(uint8_t source) {
    bool was = _begin();
    _modify(REG_CTRL4, source, 0);
    _end(was);
}

uint8_t FXOS8700Full::pollInterrupt() {
    uint8_t status = _readReg(REG_INT_SOURCE);
    struct Clear { uint8_t bit; uint8_t reg; };
    const Clear clear[] = {
        { SOURCE_AUTO_SLEEP, REG_SYSMOD },
        { SOURCE_FIFO, REG_STATUS },
        { SOURCE_TRANSIENT, REG_TRANSIENT_SRC },
        { SOURCE_ORIENTATION, REG_PL_STATUS },
        { SOURCE_PULSE, REG_PULSE_SRC },
        { SOURCE_FF_MT, REG_A_FFMT_SRC },
    };
    for (size_t i = 0; i < sizeof(clear) / sizeof(clear[0]); i++) {
        if (status & clear[i].bit) _readReg(clear[i].reg);
    }
    return status;
}

void FXOS8700Full::onInterrupt(void (*callback)(uint8_t status), uint8_t pin, InputPin* intPin) {
    if (pin != 1 && pin != 2) return;
    _callback = callback;
    InputPin* usePin = intPin ? intPin : _conn.intPin();
    _intPinUsed = usePin;
    if (!usePin) return;
    _activeInstance = this;
    bool activeHigh = (_readReg(REG_CTRL3) & 0x02) != 0;
    usePin->onEdge(&FXOS8700Full::_edgeTrampoline, activeHigh ? InputPin::kRising : InputPin::kFalling);
}

void FXOS8700Full::offInterrupt(uint8_t pin) {
    if (pin != 1 && pin != 2) return;
    if (_intPinUsed) {
        _intPinUsed->offEdge(&FXOS8700Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void FXOS8700Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void FXOS8700Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (_callback) _callback(status);
}

void FXOS8700Full::readRegister(uint8_t reg, uint8_t* buf, size_t len) {
    _conn.read(reg, buf, len);
}

void FXOS8700Full::writeRegister(uint8_t reg, const uint8_t* data, size_t len) {
    _conn.write(reg, data, len);
}

void FXOS8700Full::_averageCounts(int32_t& x, int32_t& y, int32_t& z) {
    int32_t sx = 0, sy = 0, sz = 0;
    const int samples = 8;
    for (int i = 0; i < samples; i++) {
        delay(12);
        uint8_t buf[6];
        _conn.read(REG_OUT_X_MSB, buf, 6);
        int16_t cx, cy, cz;
        _decodeAccel(buf, cx, cy, cz);
        sx += cx;
        sy += cy;
        sz += cz;
    }
    // Floor division, matching the other languages.
    x = (sx >= 0) ? sx / samples : -((-sx + samples - 1) / samples);
    y = (sy >= 0) ? sy / samples : -((-sy + samples - 1) / samples);
    z = (sz >= 0) ? sz / samples : -((-sz + samples - 1) / samples);
}

bool FXOS8700Full::selfTest(int16_t& dx, int16_t& dy, int16_t& dz) {
    const uint8_t regs[4] = { REG_CTRL1, REG_CTRL2, REG_XYZ_DATA_CFG, REG_M_CTRL1 };
    uint8_t saved[4];
    for (int i = 0; i < 4; i++) saved[i] = _readReg(regs[i]);
    uint8_t cMode = _mode, cDr = _dr, cFs = _fsG, cMods = _mods;
    bool cLnoise = _lnoise, cFRead = _fRead;
    standby();
    _writeReg(REG_XYZ_DATA_CFG, 0x00);
    _writeReg(REG_M_CTRL1, (uint8_t)(saved[3] & ~M_CTRL1_HMS_MASK));
    _writeReg(REG_CTRL2, 0x00);
    _mode = 0; _dr = 3; _fsG = 2; _mods = 0; _lnoise = false; _fRead = false;
    _writeReg(REG_CTRL1, (uint8_t)((3 << 3) | CTRL1_ACTIVE));
    delay(_turnOnMs());
    int32_t bx, by, bz, ax, ay, az;
    _averageCounts(bx, by, bz);
    _writeReg(REG_CTRL2, CTRL2_ST);
    delay(25);
    _averageCounts(ax, ay, az);
    _writeReg(REG_CTRL2, 0x00);
    standby();
    _writeReg(REG_XYZ_DATA_CFG, saved[2]);
    _writeReg(REG_M_CTRL1, saved[3]);
    _writeReg(REG_CTRL2, saved[1]);
    _mode = cMode; _dr = cDr; _fsG = cFs; _mods = cMods; _lnoise = cLnoise; _fRead = cFRead;
    _writeReg(REG_CTRL1, saved[0]);
    if (saved[0] & CTRL1_ACTIVE) delay(_turnOnMs());
    dx = (int16_t)(ax - bx);
    dy = (int16_t)(ay - by);
    dz = (int16_t)(az - bz);
    return dx >= 192 && dy >= 270 && dz >= 1275;
}
