#include "LSM303.h"
#include <stdlib.h>
#include <math.h>

static const double kPi = 3.14159265358979323846;
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
// Accelerometer sensitivity in mg/LSB, indexed by mode (low-power, normal,
// high-resolution) and full-scale range (2, 4, 8, 16 g).
const float ACCEL_SENSITIVITY[3][4] = {
    { 16.0f, 32.0f, 64.0f, 192.0f },
    {  4.0f,  8.0f, 16.0f,  48.0f },
    {  1.0f,  2.0f,  4.0f,  12.0f },
};

// Magnetometer gains in LSB per gauss: X/Y gain and the (different) Z gain.
struct MagGain {
    uint16_t rangeUt;
    float    xy;
    float    z;
};
const MagGain MAG_GAINS[] = {
    { 130, 1100.0f, 980.0f },
    { 190,  855.0f, 760.0f },
    { 250,  670.0f, 600.0f },
    { 400,  450.0f, 400.0f },
    { 470,  400.0f, 355.0f },
    { 560,  330.0f, 295.0f },
    { 810,  230.0f, 205.0f },
};
const size_t MAG_GAIN_COUNT = sizeof(MAG_GAINS) / sizeof(MAG_GAINS[0]);

// Magnetometer output data rates indexed by the CRA_REG_M.DO code.
const float MAG_RATES[8] = { 0.75f, 1.5f, 3.0f, 7.5f, 15.0f, 30.0f, 75.0f, 220.0f };
}  // namespace

// LSM303Minimal

LSM303Minimal::LSM303Minimal(RegisterConnection& accelConnection, RegisterConnection& magConnection)
    : _accel(accelConnection), _mag(magConnection) {
    init();
}

void LSM303Minimal::_accelRead(uint8_t reg, uint8_t* buf, size_t len) {
    // Multi-byte transfers set bit 7 of the sub-address so the accelerometer
    // auto-increments the register pointer.
    uint32_t address = len > 1 ? (reg | ACCEL_AUTO_INCREMENT) : reg;
    _accel.read(address, buf, len);
}

void LSM303Minimal::_accelWrite(uint8_t reg, uint8_t value) {
    _accel.write(reg, &value, 1);
}

void LSM303Minimal::_magRead(uint8_t reg, uint8_t* buf, size_t len) {
    // The magnetometer address pointer always auto-increments.
    _mag.read(reg, buf, len);
}

void LSM303Minimal::_magWrite(uint8_t reg, uint8_t value) {
    _mag.write(reg, &value, 1);
}

uint8_t LSM303Minimal::_accelReadReg(uint8_t reg) {
    uint8_t value = 0;
    _accelRead(reg, &value, 1);
    return value;
}

uint8_t LSM303Minimal::_magReadReg(uint8_t reg) {
    uint8_t value = 0;
    _magRead(reg, &value, 1);
    return value;
}

void LSM303Minimal::init() {
    // Boot download of the factory trim values.
    delay(5);

    uint8_t identity[3];
    _magRead(REG_IRA_M, identity, 3);
    if (identity[0] != MAG_ID_A || identity[1] != MAG_ID_B || identity[2] != MAG_ID_C) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        abort();
    }

    // Accelerometer: known state for the high-pass filter, the interrupt
    // routing and the FIFO.
    _accelWrite(REG_CTRL2_A, 0x00);
    _accelWrite(REG_CTRL3_A, 0x00);
    _accelWrite(REG_CTRL5_A, 0x00);
    _accelWrite(REG_CTRL6_A, 0x00);
    _accelWrite(REG_FIFO_CTRL_A, 0x00);

    // Accelerometer: BDU = 1, ±2 g, high-resolution mode.
    _accelWrite(REG_CTRL4_A, 0x88);
    // Accelerometer: 100 Hz, X/Y/Z enabled.
    _accelWrite(REG_CTRL1_A, 0x57);

    // Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
    // resets into sleep mode).
    _magWrite(REG_CRA_M, 0x10);
    _magWrite(REG_CRB_M, 0x20);
    _magWrite(REG_MR_M, 0x00);

    // Accelerometer turn-on: 7/ODR = 70 ms at 100 Hz.
    delay(70);
    // Magnetometer first sample: one output period = 67 ms at 15 Hz.
    delay(67);

    _mode = MODE_HIGH_RESOLUTION;
    _fsG = 2;
    _odrHz = 100;
    _magRangeUt = 130;
}

uint8_t LSM303Minimal::_shift() const {
    if (_mode == MODE_LOW_POWER) return 8;
    if (_mode == MODE_NORMAL) return 6;
    return 4;
}

float LSM303Minimal::_sensitivityMg() const {
    uint8_t rangeIndex = 0;
    if (_fsG == 4) rangeIndex = 1;
    else if (_fsG == 8) rangeIndex = 2;
    else if (_fsG == 16) rangeIndex = 3;
    return ACCEL_SENSITIVITY[_mode][rangeIndex];
}

void LSM303Minimal::_convertAccel(const uint8_t* raw, float& x, float& y, float& z) const {
    int16_t rx, ry, rz;
    _convertAccelRaw(raw, rx, ry, rz);
    float scale = _sensitivityMg() / 1000.0f;
    x = rx * scale;
    y = ry * scale;
    z = rz * scale;
}

void LSM303Minimal::_convertAccelRaw(const uint8_t* raw, int16_t& x, int16_t& y, int16_t& z) const {
    uint8_t shift = _shift();
    int32_t vx = toSigned((uint32_t)raw[0] | ((uint32_t)raw[1] << 8), 16);
    int32_t vy = toSigned((uint32_t)raw[2] | ((uint32_t)raw[3] << 8), 16);
    int32_t vz = toSigned((uint32_t)raw[4] | ((uint32_t)raw[5] << 8), 16);
    x = (int16_t)(vx >> shift);
    y = (int16_t)(vy >> shift);
    z = (int16_t)(vz >> shift);
}

void LSM303Minimal::_reorderMag(const uint8_t* raw, int16_t& x, int16_t& y, int16_t& z) const {
    // The magnetometer delivers X, Z, Y with the high byte first.
    int32_t vx = toSigned(((uint32_t)raw[0] << 8) | raw[1], 16);
    int32_t vz = toSigned(((uint32_t)raw[2] << 8) | raw[3], 16);
    int32_t vy = toSigned(((uint32_t)raw[4] << 8) | raw[5], 16);
    x = (int16_t)vx;
    y = (int16_t)vy;
    z = (int16_t)vz;
}

void LSM303Minimal::_magGains(float& xy, float& z) const {
    for (size_t i = 0; i < MAG_GAIN_COUNT; i++) {
        if (MAG_GAINS[i].rangeUt == _magRangeUt) {
            xy = MAG_GAINS[i].xy;
            z = MAG_GAINS[i].z;
            return;
        }
    }
    xy = MAG_GAINS[0].xy;
    z = MAG_GAINS[0].z;
}

void LSM303Minimal::readAcceleration(float& x, float& y, float& z) {
    uint8_t raw[6];
    _accelRead(REG_OUT_X_L_A, raw, 6);
    _convertAccel(raw, x, y, z);
}

void LSM303Minimal::readMagnetic(float& x, float& y, float& z) {
    uint8_t raw[6];
    _magRead(REG_OUT_X_H_M, raw, 6);
    int16_t rx, ry, rz;
    _reorderMag(raw, rx, ry, rz);
    float gainXY, gainZ;
    _magGains(gainXY, gainZ);
    x = (float)rx / gainXY * 100.0f;
    y = (float)ry / gainXY * 100.0f;
    z = (float)rz / gainZ * 100.0f;
}

// LSM303Full

LSM303Full* LSM303Full::_activeInstance = nullptr;

LSM303Full::LSM303Full(RegisterConnection& accelConnection, RegisterConnection& magConnection)
    : LSM303Minimal(accelConnection, magConnection) {}

bool LSM303Full::setRange(uint8_t rangeG) {
    uint8_t code;
    if (rangeG == 2) code = 0x00;
    else if (rangeG == 4) code = 0x10;
    else if (rangeG == 8) code = 0x20;
    else if (rangeG == 16) code = 0x30;
    else return false;
    uint8_t ctrl4 = _accelReadReg(REG_CTRL4_A);
    ctrl4 = (uint8_t)((ctrl4 & ~CTRL4_FS_MASK) | code);
    _accelWrite(REG_CTRL4_A, ctrl4);
    _fsG = rangeG;
    return true;
}

bool LSM303Full::setDataRate(uint16_t rateHz) {
    uint8_t code;
    switch (rateHz) {
        case 0: code = 0x0; break;
        case 1: code = 0x1; break;
        case 10: code = 0x2; break;
        case 25: code = 0x3; break;
        case 50: code = 0x4; break;
        case 100: code = 0x5; break;
        case 200: code = 0x6; break;
        case 400: code = 0x7; break;
        case 1620: code = 0x8; break;
        case 1344:
        case 5376: code = 0x9; break;
        default: return false;
    }
    bool lowPower = (_mode == MODE_LOW_POWER);
    if ((rateHz == 1620 || rateHz == 5376) && !lowPower) return false;
    if (rateHz == 1344 && lowPower) return false;
    uint8_t ctrl1 = _accelReadReg(REG_CTRL1_A);
    ctrl1 = (uint8_t)((ctrl1 & ~CTRL1_ODR_MASK) | (code << 4));
    _accelWrite(REG_CTRL1_A, ctrl1);
    _odrHz = rateHz;
    return true;
}

bool LSM303Full::setMode(uint8_t mode) {
    bool lpen, hr;
    if (mode == MODE_LOW_POWER) {
        lpen = true;
        hr = false;
    } else if (mode == MODE_NORMAL) {
        lpen = false;
        hr = false;
    } else if (mode == MODE_HIGH_RESOLUTION) {
        lpen = false;
        hr = true;
    } else {
        return false;
    }
    uint8_t ctrl1 = _accelReadReg(REG_CTRL1_A);
    uint8_t ctrl4 = _accelReadReg(REG_CTRL4_A);
    if (lpen) ctrl1 |= CTRL1_LPEN;
    else ctrl1 = (uint8_t)(ctrl1 & ~CTRL1_LPEN);
    if (hr) ctrl4 |= CTRL4_HR;
    else ctrl4 = (uint8_t)(ctrl4 & ~CTRL4_HR);
    _accelWrite(REG_CTRL1_A, ctrl1);
    _accelWrite(REG_CTRL4_A, ctrl4);
    _mode = mode;
    return true;
}

void LSM303Full::setAxes(bool x, bool y, bool z) {
    uint8_t ctrl1 = _accelReadReg(REG_CTRL1_A);
    ctrl1 = (uint8_t)(ctrl1 & ~CTRL1_AXES_MASK);
    if (x) ctrl1 |= 0x01;
    if (y) ctrl1 |= 0x02;
    if (z) ctrl1 |= 0x04;
    _accelWrite(REG_CTRL1_A, ctrl1);
}

void LSM303Full::powerDown() {
    uint8_t ctrl1 = _accelReadReg(REG_CTRL1_A);
    _accelWrite(REG_CTRL1_A, (uint8_t)(ctrl1 & ~CTRL1_ODR_MASK));
    _odrHz = 0;
}

void LSM303Full::readAccelerationRaw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _accelRead(REG_OUT_X_L_A, raw, 6);
    _convertAccelRaw(raw, x, y, z);
}

bool LSM303Full::newDataAvailable() {
    return (_accelReadReg(REG_STATUS_A) & STATUS_ZYXDA) != 0;
}

uint8_t LSM303Full::readStatus() {
    return _accelReadReg(REG_STATUS_A);
}

bool LSM303Full::setHighPass(uint8_t mode, uint8_t cutoff, bool filterOutput,
                              bool onIa1, bool onIa2, bool onClick) {
    if (mode > 3 || cutoff > 3) return false;
    uint8_t ctrl2 = (uint8_t)((mode << 6) | (cutoff << 4));
    if (filterOutput) ctrl2 |= CTRL2_FDS;
    if (onClick) ctrl2 |= CTRL2_HPCLICK;
    if (onIa2) ctrl2 |= CTRL2_HPIS2;
    if (onIa1) ctrl2 |= CTRL2_HPIS1;
    _accelWrite(REG_CTRL2_A, ctrl2);
    return true;
}

uint8_t LSM303Full::resetHighPass() {
    return _accelReadReg(REG_REFERENCE_A);
}

void LSM303Full::setReference(uint8_t value) {
    _accelWrite(REG_REFERENCE_A, value);
}

bool LSM303Full::setFifo(uint8_t mode, uint8_t watermark, bool triggerInt2) {
    if (mode > 3 || watermark > 31) return false;
    uint8_t fifo = (uint8_t)((mode << 6) | (watermark & 0x1F));
    if (triggerInt2) fifo |= 0x20;
    _accelWrite(REG_FIFO_CTRL_A, fifo);
    uint8_t ctrl5 = _accelReadReg(REG_CTRL5_A);
    if (mode == FIFO_BYPASS) ctrl5 = (uint8_t)(ctrl5 & ~CTRL5_FIFO_EN);
    else ctrl5 |= CTRL5_FIFO_EN;
    _accelWrite(REG_CTRL5_A, ctrl5);
    return true;
}

uint8_t LSM303Full::fifoLevel() {
    return (uint8_t)(_accelReadReg(REG_FIFO_SRC_A) & FIFO_SRC_LEVEL_MASK);
}

uint8_t LSM303Full::readFifo(float* out, uint8_t maxCount) {
    uint8_t level = fifoLevel();
    if (level > maxCount) level = maxCount;
    for (uint8_t i = 0; i < level; i++) {
        uint8_t raw[6];
        _accelRead(REG_OUT_X_L_A, raw, 6);
        _convertAccel(raw, out[i * 3], out[i * 3 + 1], out[i * 3 + 2]);
    }
    return level;
}

void LSM303Full::readFifoStatus(bool& watermark, bool& overrun, bool& empty, uint8_t& level) {
    uint8_t src = _accelReadReg(REG_FIFO_SRC_A);
    watermark = (src & FIFO_SRC_WTM) != 0;
    overrun = (src & FIFO_SRC_OVERRUN) != 0;
    empty = (src & FIFO_SRC_EMPTY) != 0;
    level = (uint8_t)(src & FIFO_SRC_LEVEL_MASK);
}

bool LSM303Full::_intRegs(uint8_t generator, uint8_t& cfg, uint8_t& ths, uint8_t& dur) const {
    if (generator == 1) {
        cfg = REG_INT1_CFG_A;
        ths = REG_INT1_THS_A;
        dur = REG_INT1_DURATION_A;
        return true;
    }
    if (generator == 2) {
        cfg = REG_INT2_CFG_A;
        ths = REG_INT2_THS_A;
        dur = REG_INT2_DURATION_A;
        return true;
    }
    return false;
}

uint8_t LSM303Full::_encodeThreshold(float thresholdG) const {
    int32_t code = (int32_t)(thresholdG * 128.0f / (float)_fsG + 0.5f);
    if (code < 0) code = 0;
    if (code > 127) code = 127;
    return (uint8_t)code;
}

uint8_t LSM303Full::_encodeDuration(float durationMs, uint8_t bits) const {
    int32_t code = 0;
    if (_odrHz > 0) code = (int32_t)(durationMs * (float)_odrHz / 1000.0f + 0.5f);
    int32_t maxValue = (1 << bits) - 1;
    if (code < 0) code = 0;
    if (code > maxValue) code = maxValue;
    return (uint8_t)code;
}

bool LSM303Full::setMotionInterrupt(uint8_t generator, uint8_t events, float thresholdG,
                                    float durationMs, bool andMode) {
    uint8_t cfg, ths, dur;
    if (!_intRegs(generator, cfg, ths, dur)) return false;
    if (events > 0x3F) return false;
    uint8_t value = (uint8_t)(events & 0x3F);
    if (andMode) value |= 0x80;
    _accelWrite(cfg, value);
    _accelWrite(ths, _encodeThreshold(thresholdG));
    _accelWrite(dur, _encodeDuration(durationMs, 7));
    return true;
}

bool LSM303Full::setFreeFall(uint8_t generator, float thresholdG, float durationMs) {
    // All three axes low, combined with AND.
    return setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true);
}

bool LSM303Full::setOrientation(uint8_t generator, bool position, bool fourD) {
    uint8_t cfg, ths, dur;
    if (!_intRegs(generator, cfg, ths, dur)) return false;
    uint8_t events = 0x3F;
    if (fourD) events = (uint8_t)(events & ~0x30);
    uint8_t value = (uint8_t)(events | 0x40);
    if (position) value |= 0x80;
    _accelWrite(cfg, value);
    uint8_t ctrl5 = _accelReadReg(REG_CTRL5_A);
    uint8_t d4dBit = (generator == 1) ? CTRL5_D4D_INT1 : CTRL5_D4D_INT2;
    if (fourD) ctrl5 |= d4dBit;
    else ctrl5 = (uint8_t)(ctrl5 & ~d4dBit);
    _accelWrite(REG_CTRL5_A, ctrl5);
    return true;
}

bool LSM303Full::setLatch(uint8_t generator, bool enabled) {
    if (generator != 1 && generator != 2) return false;
    uint8_t ctrl5 = _accelReadReg(REG_CTRL5_A);
    uint8_t bit = (generator == 1) ? CTRL5_LIR_INT1 : CTRL5_LIR_INT2;
    if (enabled) ctrl5 |= bit;
    else ctrl5 = (uint8_t)(ctrl5 & ~bit);
    _accelWrite(REG_CTRL5_A, ctrl5);
    return true;
}

uint8_t LSM303Full::readInterruptSource(uint8_t generator) {
    if (generator == 1) return _accelReadReg(REG_INT1_SOURCE_A);
    if (generator == 2) return _accelReadReg(REG_INT2_SOURCE_A);
    return 0;
}

bool LSM303Full::setClick(uint8_t single, uint8_t doubleClick, float thresholdG,
                          float timeLimitMs, float latencyMs, float windowMs) {
    if (single > 0x07 || doubleClick > 0x07) return false;
    uint8_t cfg = 0;
    if (single & CLICK_AXIS_X) cfg |= 0x01;
    if (single & CLICK_AXIS_Y) cfg |= 0x04;
    if (single & CLICK_AXIS_Z) cfg |= 0x10;
    if (doubleClick & CLICK_AXIS_X) cfg |= 0x02;
    if (doubleClick & CLICK_AXIS_Y) cfg |= 0x08;
    if (doubleClick & CLICK_AXIS_Z) cfg |= 0x20;
    _accelWrite(REG_CLICK_CFG_A, cfg);
    _accelWrite(REG_CLICK_THS_A, _encodeThreshold(thresholdG));
    _accelWrite(REG_TIME_LIMIT_A, _encodeDuration(timeLimitMs, 7));
    _accelWrite(REG_TIME_LATENCY_A, _encodeDuration(latencyMs, 8));
    _accelWrite(REG_TIME_WINDOW_A, _encodeDuration(windowMs, 8));
    return true;
}

uint8_t LSM303Full::readClickSource() {
    return _accelReadReg(REG_CLICK_SRC_A);
}

void LSM303Full::setInterruptPolarity(bool activeLow) {
    uint8_t ctrl6 = _accelReadReg(REG_CTRL6_A);
    if (activeLow) ctrl6 |= CTRL6_H_LACTIVE;
    else ctrl6 = (uint8_t)(ctrl6 & ~CTRL6_H_LACTIVE);
    _accelWrite(REG_CTRL6_A, ctrl6);
}

bool LSM303Full::setMagRange(uint16_t rangeUt) {
    uint8_t code;
    switch (rangeUt) {
        case 130: code = 0x20; break;
        case 190: code = 0x40; break;
        case 250: code = 0x60; break;
        case 400: code = 0x80; break;
        case 470: code = 0xA0; break;
        case 560: code = 0xC0; break;
        case 810: code = 0xE0; break;
        default: return false;
    }
    uint8_t crb = _magReadReg(REG_CRB_M);
    crb = (uint8_t)((crb & ~CRB_GN_MASK) | code);
    _magWrite(REG_CRB_M, crb);
    _magRangeUt = rangeUt;
    return true;
}

bool LSM303Full::setMagDataRate(float rateHz) {
    for (uint8_t code = 0; code < 8; code++) {
        float diff = MAG_RATES[code] - rateHz;
        if (diff < 0) diff = -diff;
        if (diff < 1e-6f) {
            uint8_t cra = _magReadReg(REG_CRA_M);
            cra = (uint8_t)((cra & ~CRA_DO_MASK) | (code << 2));
            _magWrite(REG_CRA_M, cra);
            return true;
        }
    }
    return false;
}

bool LSM303Full::setMagMode(uint8_t mode) {
    if (mode > MAG_MODE_SLEEP) return false;
    uint8_t mr = _magReadReg(REG_MR_M);
    mr = (uint8_t)((mr & ~0x03) | mode);
    _magWrite(REG_MR_M, mr);
    return true;
}

void LSM303Full::readMagneticRaw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _magRead(REG_OUT_X_H_M, raw, 6);
    _reorderMag(raw, x, y, z);
}

bool LSM303Full::magDataReady() {
    return (_magReadReg(REG_SR_M) & SR_DRDY) != 0;
}

void LSM303Full::readMagStatus(bool& drdy, bool& lock) {
    uint8_t sr = _magReadReg(REG_SR_M);
    drdy = (sr & SR_DRDY) != 0;
    lock = (sr & SR_LOCK) != 0;
}

void LSM303Full::readMagIdentity(uint8_t* out) {
    _magRead(REG_IRA_M, out, 3);
}

void LSM303Full::enableTemperature(bool enabled) {
    uint8_t cra = _magReadReg(REG_CRA_M);
    if (enabled) cra |= CRA_TEMP_EN;
    else cra = (uint8_t)(cra & ~CRA_TEMP_EN);
    _magWrite(REG_CRA_M, cra);
    _tempEnabled = enabled;
}

float LSM303Full::readTemperatureDelta() {
    if (!_tempEnabled) enableTemperature(true);
    uint8_t raw[2];
    _magRead(REG_TEMP_OUT_H_M, raw, 2);
    int32_t value = toSigned(((uint32_t)raw[0] << 8) | raw[1], 16) >> 4;
    return (float)value / 8.0f;
}

float LSM303Full::_wrapDegrees(float degrees) const {
    float value = (float)fmod((double)degrees, 360.0);
    if (value < 0) value += 360.0f;
    return value;
}

float LSM303Full::heading(bool tiltCompensate) {
    float mx, my, mz;
    readMagnetic(mx, my, mz);
    if (!tiltCompensate) {
        return _wrapDegrees((float)(atan2((double)my, (double)mx) * 180.0 / kPi));
    }
    float ax, ay, az;
    readAcceleration(ax, ay, az);
    double roll = atan2((double)ay, (double)az);
    double pitch = atan2((double)-ax,
                         (double)ay * sin(roll) + (double)az * cos(roll));
    double xh = (double)mx * cos(pitch) + (double)mz * sin(pitch);
    double yh = (double)mx * sin(roll) * sin(pitch) + (double)my * cos(roll)
                - (double)mz * sin(roll) * cos(pitch);
    return _wrapDegrees((float)(atan2(yh, xh) * 180.0 / kPi));
}

void LSM303Full::_sourceBits(uint8_t source, uint8_t& ctrl3, uint8_t& ctrl6) const {
    ctrl3 = 0;
    ctrl6 = 0;
    switch (source) {
        case SOURCE_DATA_READY: ctrl3 = CTRL3_I1_DRDY1; break;
        case SOURCE_WATERMARK: ctrl3 = CTRL3_I1_WTM; break;
        case SOURCE_OVERRUN: ctrl3 = CTRL3_I1_OVERRUN; break;
        case SOURCE_IA1: ctrl3 = CTRL3_I1_AOI1; ctrl6 = CTRL6_I2_INT1; break;
        case SOURCE_IA2: ctrl3 = CTRL3_I1_AOI2; ctrl6 = CTRL6_I2_INT2; break;
        case SOURCE_CLICK: ctrl3 = CTRL3_I1_CLICK; ctrl6 = CTRL6_I2_CLICK; break;
        case SOURCE_BOOT: ctrl6 = CTRL6_BOOT_I1; break;
        default: break;
    }
}

bool LSM303Full::enableInterrupt(uint8_t source, uint8_t pin) {
    if (pin != 1 && pin != 2) return false;
    if (source == SOURCE_MAG_DATA_READY) return false;
    uint8_t ctrl3Bit, ctrl6Bit;
    _sourceBits(source, ctrl3Bit, ctrl6Bit);
    if (ctrl3Bit == 0 && ctrl6Bit == 0) return false;
    if (ctrl3Bit != 0 && (pin == 1 || ctrl6Bit == 0)) {
        uint8_t ctrl3 = _accelReadReg(REG_CTRL3_A);
        _accelWrite(REG_CTRL3_A, (uint8_t)(ctrl3 | ctrl3Bit));
    }
    if (ctrl6Bit != 0 && (pin == 2 || ctrl3Bit == 0)) {
        uint8_t ctrl6 = _accelReadReg(REG_CTRL6_A);
        _accelWrite(REG_CTRL6_A, (uint8_t)(ctrl6 | ctrl6Bit));
    }
    _enabledSources |= source;
    return true;
}

bool LSM303Full::disableInterrupt(uint8_t source, uint8_t pin) {
    if (pin != 1 && pin != 2) return false;
    if (source == SOURCE_MAG_DATA_READY) return false;
    uint8_t ctrl3Bit, ctrl6Bit;
    _sourceBits(source, ctrl3Bit, ctrl6Bit);
    if (ctrl3Bit == 0 && ctrl6Bit == 0) return false;
    if (ctrl3Bit != 0 && (pin == 1 || ctrl6Bit == 0)) {
        uint8_t ctrl3 = _accelReadReg(REG_CTRL3_A);
        _accelWrite(REG_CTRL3_A, (uint8_t)(ctrl3 & ~ctrl3Bit));
    }
    if (ctrl6Bit != 0 && (pin == 2 || ctrl3Bit == 0)) {
        uint8_t ctrl6 = _accelReadReg(REG_CTRL6_A);
        _accelWrite(REG_CTRL6_A, (uint8_t)(ctrl6 & ~ctrl6Bit));
    }
    _enabledSources = (uint8_t)(_enabledSources & ~source);
    return true;
}

uint8_t LSM303Full::pollInterrupt() {
    uint8_t status = 0;
    if (_accelReadReg(REG_STATUS_A) & STATUS_ZYXDA) status |= SOURCE_DATA_READY;
    uint8_t fifo = _accelReadReg(REG_FIFO_SRC_A);
    if (fifo & FIFO_SRC_WTM) status |= SOURCE_WATERMARK;
    if (fifo & FIFO_SRC_OVERRUN) status |= SOURCE_OVERRUN;
    if (_enabledSources & SOURCE_IA1) {
        if (_accelReadReg(REG_INT1_SOURCE_A) & SOURCE_IA) status |= SOURCE_IA1;
    }
    if (_enabledSources & SOURCE_IA2) {
        if (_accelReadReg(REG_INT2_SOURCE_A) & SOURCE_IA) status |= SOURCE_IA2;
    }
    if (_enabledSources & SOURCE_CLICK) {
        if (_accelReadReg(REG_CLICK_SRC_A) & SOURCE_IA) status |= SOURCE_CLICK;
    }
    if (_magReadReg(REG_SR_M) & SR_DRDY) status |= SOURCE_MAG_DATA_READY;
    return status;
}

void LSM303Full::onInterrupt(void (*callback)(uint8_t status), uint8_t pin, InputPin* intPin) {
    if (pin != 1 && pin != 2) return;
    _callback = callback;
    InputPin* usePin = intPin ? intPin : _accel.intPin();
    _intPinUsed = usePin;
    if (!usePin) return;
    _activeInstance = this;
    bool activeLow = (_accelReadReg(REG_CTRL6_A) & CTRL6_H_LACTIVE) != 0;
    usePin->onEdge(&LSM303Full::_edgeTrampoline,
                   activeLow ? InputPin::kFalling : InputPin::kRising);
}

void LSM303Full::offInterrupt(uint8_t pin) {
    if (pin != 1 && pin != 2) return;
    if (_intPinUsed) {
        _intPinUsed->offEdge(&LSM303Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void LSM303Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void LSM303Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (_callback) _callback(status);
}

void LSM303Full::reboot() {
    uint8_t ctrl5 = _accelReadReg(REG_CTRL5_A);
    _accelWrite(REG_CTRL5_A, (uint8_t)(ctrl5 | CTRL5_BOOT));
    delay(5);
    init();
    _tempEnabled = false;
}
