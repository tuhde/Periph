#include "LSM303.h"
#include <math.h>

// Platform delay for turn-on and reboot waits. The connection layer has no
// delay primitive, so this block follows the ADXL345 sibling.
#ifdef ARDUINO
#include <Arduino.h>
static inline void lsm303DelayMs(unsigned long ms) { delay(ms); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void lsm303DelayMs(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void lsm303DelayMs(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void lsm303DelayMs(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void lsm303DelayMs(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void lsm303DelayMs(unsigned long ms) { usleep(ms * 1000UL); }
#endif

// Out-of-class definitions for the static interrupt dispatch state.
void (*LSM303Full::_cb[2])(uint8_t) = {nullptr, nullptr};
LSM303Full* LSM303Full::_owner[2] = {nullptr, nullptr};

namespace {

const float PI_F = 3.14159265f;

// mg/LSB, indexed [mode][FS code]; mode 0 low-power, 1 normal, 2 high-resolution.
const uint8_t kMgPerLsb[3][4] = {
    {16, 32, 64, 192},
    {4, 8, 16, 48},
    {1, 2, 4, 12},
};

// Right shift applied to the left-justified 16-bit sample, indexed by mode.
const uint8_t kAccelShift[3] = {8, 6, 4};

// ODR code 0..8 to Hz. Code 9 is 5376 Hz in low-power mode and 1344 Hz otherwise.
const uint16_t kOdrHz[9] = {0, 1, 10, 25, 50, 100, 200, 400, 1620};

struct MagRangeEntry { uint16_t ut; uint8_t gn; uint16_t xy; uint16_t z; };
const MagRangeEntry kMagRanges[7] = {
    {130, 1, 1100, 980},
    {190, 2, 855, 760},
    {250, 3, 670, 600},
    {400, 4, 450, 400},
    {470, 5, 400, 355},
    {560, 6, 330, 295},
    {810, 7, 230, 205},
};

struct MagRateEntry { float hz; uint8_t code; };
const MagRateEntry kMagRates[8] = {
    {0.75f, 0}, {1.5f, 1}, {3.0f, 2}, {7.5f, 3},
    {15.0f, 4}, {30.0f, 5}, {75.0f, 6}, {220.0f, 7},
};

// Big-endian pair to signed 16-bit (hi byte first).
int16_t toInt16(uint8_t hi, uint8_t lo) {
    return static_cast<int16_t>(static_cast<uint16_t>((static_cast<uint16_t>(hi) << 8) | lo));
}

// Output data rate in Hz for an ODR code in the given mode.
float odrHzFor(uint8_t code, uint8_t mode) {
    if (code == 9) return mode == 0 ? 5376.0f : 1344.0f;
    return static_cast<float>(kOdrHz[code]);
}

// Full-scale range in g for an FS code.
int fsGFor(uint8_t code) { return 2 << code; }

// Round value * lsbPerUnit to the nearest code, clamped to [0, maxCode].
uint8_t toCode(float value, float lsbPerUnit, uint8_t maxCode) {
    if (lsbPerUnit <= 0.0f || value <= 0.0f) return 0;
    float c = value * lsbPerUnit + 0.5f;
    if (c >= static_cast<float>(maxCode)) return maxCode;
    return static_cast<uint8_t>(c);
}

// Left-justified accelerometer sample to g.
float countsToG(int16_t raw, uint8_t shift, uint8_t mgPerLsb) {
    return static_cast<float>(raw >> shift) * static_cast<float>(mgPerLsb) / 1000.0f;
}

}  // namespace

// ---------------------------------------------------------------------------
// LSM303Minimal
// ---------------------------------------------------------------------------

LSM303Minimal::LSM303Minimal(RegisterConnection& accelConnection, RegisterConnection& magConnection)
    : _accelConn(accelConnection), _magConn(magConnection) {
    _init();
}

void LSM303Minimal::_init() {
    _tempEnabled  = false;
    _intActiveLow = false;
    _mode = 2;
    _fsCode = 0;
    _odrCode = 5;
    _magGainXY = 1100;
    _magGainZ = 980;
    _recomputeAccelScale();

    // Magnetometer identity first. On mismatch, write nothing.
    uint8_t id[3] = {0, 0, 0};
    _magConn.read(REG_IRA_REG_M, id, 3);
    _valid = (id[0] == ID_A && id[1] == ID_B && id[2] == ID_C);
    if (!_valid) return;

    // Accelerometer: known state for the control registers.
    _writeAccel(REG_CTRL_REG2_A, 0x00);
    _writeAccel(REG_CTRL_REG3_A, 0x00);
    _writeAccel(REG_CTRL_REG5_A, 0x00);
    _writeAccel(REG_CTRL_REG6_A, 0x00);
    _writeAccel(REG_FIFO_CTRL_REG_A, 0x00);
    _writeAccel(REG_CTRL_REG4_A, 0x88);  // BDU=1, ±2 g, HR=1
    _writeAccel(REG_CTRL_REG1_A, 0x57);  // 100 Hz, LPen=0, X/Y/Z enabled

    // Magnetometer: leave sleep and select 15 Hz, ±1.3 gauss.
    _writeMag(REG_CRA_REG_M, 0x10);
    _writeMag(REG_CRB_REG_M, 0x20);
    _writeMag(REG_MR_REG_M, 0x00);

    // 7/ODR = 70 ms accelerometer turn-on at 100 Hz. This also covers the first
    // magnetometer sample (1/15 Hz ≈ 67 ms).
    lsm303DelayMs(70);
}

void LSM303Minimal::_recomputeAccelScale() {
    _accelShift = kAccelShift[_mode];
    _mgPerLsb = kMgPerLsb[_mode][_fsCode];
}

void LSM303Minimal::_writeAccel(uint8_t reg, uint8_t value) {
    uint8_t v = value;
    _accelConn.write(reg, &v, 1);
}

uint8_t LSM303Minimal::_readAccel(uint8_t reg) {
    uint8_t v = 0;
    _accelConn.read(reg, &v, 1);
    return v;
}

void LSM303Minimal::_readAccelBurst(uint8_t reg, uint8_t* buf, size_t len) {
    _accelConn.read(static_cast<uint8_t>(reg | AUTO_INCREMENT), buf, len);
}

void LSM303Minimal::_updateAccel(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t v = _readAccel(reg);
    v = static_cast<uint8_t>((v & ~mask) | (value & mask));
    _writeAccel(reg, v);
}

void LSM303Minimal::_writeMag(uint8_t reg, uint8_t value) {
    uint8_t v = value;
    _magConn.write(reg, &v, 1);
}

uint8_t LSM303Minimal::_readMag(uint8_t reg) {
    uint8_t v = 0;
    _magConn.read(reg, &v, 1);
    return v;
}

void LSM303Minimal::_updateMag(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t v = _readMag(reg);
    v = static_cast<uint8_t>((v & ~mask) | (value & mask));
    _writeMag(reg, v);
}

void LSM303Minimal::readAcceleration(float& x, float& y, float& z) {
    uint8_t buf[6];
    _readAccelBurst(REG_OUT_X_L_A, buf, 6);
    x = countsToG(toInt16(buf[1], buf[0]), _accelShift, _mgPerLsb);
    y = countsToG(toInt16(buf[3], buf[2]), _accelShift, _mgPerLsb);
    z = countsToG(toInt16(buf[5], buf[4]), _accelShift, _mgPerLsb);
}

void LSM303Minimal::readMagnetic(float& x, float& y, float& z) {
    // Wire order is X, Z, Y, high byte first.
    uint8_t buf[6];
    _magConn.read(REG_OUT_X_H_M, buf, 6);
    int16_t rx = toInt16(buf[0], buf[1]);
    int16_t rz = toInt16(buf[2], buf[3]);
    int16_t ry = toInt16(buf[4], buf[5]);
    x = static_cast<float>(rx) / static_cast<float>(_magGainXY) * 100.0f;
    y = static_cast<float>(ry) / static_cast<float>(_magGainXY) * 100.0f;
    z = static_cast<float>(rz) / static_cast<float>(_magGainZ) * 100.0f;
}

// ---------------------------------------------------------------------------
// LSM303Full — configuration
// ---------------------------------------------------------------------------

LSM303Full::LSM303Full(RegisterConnection& accelConnection, RegisterConnection& magConnection)
    : LSM303Minimal(accelConnection, magConnection) {}

bool LSM303Full::setRange(int rangeG) {
    uint8_t code = 0;
    switch (rangeG) {
        case 2:  code = 0; break;
        case 4:  code = 1; break;
        case 8:  code = 2; break;
        case 16: code = 3; break;
        default: return false;
    }
    _updateAccel(REG_CTRL_REG4_A, 0x30, static_cast<uint8_t>(code << 4));
    _fsCode = code;
    _recomputeAccelScale();
    return true;
}

bool LSM303Full::setDataRate(int rateHz) {
    uint8_t code = 0;
    switch (rateHz) {
        case 0:    code = 0; break;
        case 1:    code = 1; break;
        case 10:   code = 2; break;
        case 25:   code = 3; break;
        case 50:   code = 4; break;
        case 100:  code = 5; break;
        case 200:  code = 6; break;
        case 400:  code = 7; break;
        case 1620: if (_mode != 0) return false; code = 8; break;  // low-power only
        case 1344: if (_mode == 0) return false; code = 9; break;  // normal / high-resolution only
        case 5376: if (_mode != 0) return false; code = 9; break;  // low-power only
        default:   return false;
    }
    _updateAccel(REG_CTRL_REG1_A, 0xF0, static_cast<uint8_t>(code << 4));
    _odrCode = code;
    return true;
}

bool LSM303Full::setMode(int mode) {
    if (mode < 0 || mode > 2) return false;
    bool lowPower = (mode == 0);
    // The current rate must remain legal in the new mode.
    if (_odrCode == 8 && !lowPower) return false;
    if (_odrCode == 9 && ((_mode == 0) != lowPower)) return false;
    _updateAccel(REG_CTRL_REG1_A, 0x08, lowPower ? 0x08 : 0x00);  // LPen
    _updateAccel(REG_CTRL_REG4_A, 0x08, (mode == 2) ? 0x08 : 0x00);  // HR
    _mode = static_cast<uint8_t>(mode);
    _recomputeAccelScale();
    return true;
}

void LSM303Full::setAxes(bool x, bool y, bool z) {
    _updateAccel(REG_CTRL_REG1_A, 0x07,
                 static_cast<uint8_t>((x ? 0x01 : 0x00) | (y ? 0x02 : 0x00) | (z ? 0x04 : 0x00)));
}

void LSM303Full::powerDown() {
    _updateAccel(REG_CTRL_REG1_A, 0xF0, 0x00);
    _odrCode = 0;
}

void LSM303Full::readAccelerationRaw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _readAccelBurst(REG_OUT_X_L_A, buf, 6);
    x = static_cast<int16_t>(toInt16(buf[1], buf[0]) >> _accelShift);
    y = static_cast<int16_t>(toInt16(buf[3], buf[2]) >> _accelShift);
    z = static_cast<int16_t>(toInt16(buf[5], buf[4]) >> _accelShift);
}

bool LSM303Full::newDataAvailable() {
    return (_readAccel(REG_STATUS_REG_A) & 0x08) != 0;
}

uint8_t LSM303Full::readStatus() {
    return _readAccel(REG_STATUS_REG_A);
}

void LSM303Full::setHighPass(uint8_t mode, uint8_t cutoff, bool filterOutput,
                             bool onIa1, bool onIa2, bool onClick) {
    uint8_t v = static_cast<uint8_t>(((mode & 0x03) << 6) | ((cutoff & 0x03) << 4) |
                                     (filterOutput ? 0x08 : 0x00) | (onClick ? 0x04 : 0x00) |
                                     (onIa2 ? 0x02 : 0x00) | (onIa1 ? 0x01 : 0x00));
    _writeAccel(REG_CTRL_REG2_A, v);
}

uint8_t LSM303Full::resetHighPass() {
    return _readAccel(REG_REFERENCE_A);
}

void LSM303Full::setReference(uint8_t value) {
    _writeAccel(REG_REFERENCE_A, value);
}

bool LSM303Full::setFifo(uint8_t mode, uint8_t watermark, bool triggerInt2) {
    if (mode > 3 || watermark > 31) return false;
    if (mode != 0) _updateAccel(REG_CTRL_REG5_A, 0x40, 0x40);  // FIFO_EN before enabling the mode
    _writeAccel(REG_FIFO_CTRL_REG_A,
                static_cast<uint8_t>((mode << 6) | (triggerInt2 ? 0x20 : 0x00) | watermark));
    if (mode == 0) _updateAccel(REG_CTRL_REG5_A, 0x40, 0x00);  // bypass: FIFO_EN cleared
    return true;
}

uint8_t LSM303Full::fifoLevel() {
    return static_cast<uint8_t>(_readAccel(REG_FIFO_SRC_REG_A) & 0x1F);
}

uint8_t LSM303Full::readFifo(float* x, float* y, float* z, uint8_t count) {
    uint8_t n = fifoLevel();
    if (count < n) n = count;
    for (uint8_t i = 0; i < n; i++) {
        uint8_t buf[6];
        _readAccelBurst(REG_OUT_X_L_A, buf, 6);
        x[i] = countsToG(toInt16(buf[1], buf[0]), _accelShift, _mgPerLsb);
        y[i] = countsToG(toInt16(buf[3], buf[2]), _accelShift, _mgPerLsb);
        z[i] = countsToG(toInt16(buf[5], buf[4]), _accelShift, _mgPerLsb);
    }
    return n;
}

void LSM303Full::readFifoStatus(bool& watermark, bool& overrun, bool& empty, uint8_t& level) {
    uint8_t v = _readAccel(REG_FIFO_SRC_REG_A);
    watermark = (v & 0x80) != 0;
    overrun   = (v & 0x40) != 0;
    empty     = (v & 0x20) != 0;
    level     = static_cast<uint8_t>(v & 0x1F);
}

bool LSM303Full::setMotionInterrupt(uint8_t generator, uint8_t events, float thresholdG,
                                    float durationMs, bool andMode) {
    uint8_t cfgReg, thsReg, durReg;
    if (generator == 1) {
        cfgReg = REG_INT1_CFG_A; thsReg = REG_INT1_THS_A; durReg = REG_INT1_DURATION_A;
    } else if (generator == 2) {
        cfgReg = REG_INT2_CFG_A; thsReg = REG_INT2_THS_A; durReg = REG_INT2_DURATION_A;
    } else {
        return false;
    }
    _writeAccel(cfgReg, static_cast<uint8_t>((andMode ? 0x80 : 0x00) | (events & 0x3F)));
    _writeAccel(thsReg, toCode(thresholdG, 128.0f / static_cast<float>(fsGFor(_fsCode)), 127));
    _writeAccel(durReg, toCode(durationMs, odrHzFor(_odrCode, _mode) / 1000.0f, 127));
    return true;
}

bool LSM303Full::setFreeFall(uint8_t generator, float thresholdG, float durationMs) {
    // Events ZLIE|YLIE|XLIE with AOI = 1 gives INTx_CFG_A = 0x95.
    return setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true);
}

bool LSM303Full::setOrientation(uint8_t generator, bool position, bool fourD) {
    uint8_t cfgReg, d4dBit;
    if (generator == 1) {
        cfgReg = REG_INT1_CFG_A; d4dBit = 0x04;  // D4D_INT1
    } else if (generator == 2) {
        cfgReg = REG_INT2_CFG_A; d4dBit = 0x01;  // D4D_INT2
    } else {
        return false;
    }
    // 6D is bit 6. AOI (bit 7) selects position (1) or movement (0). All six zone enables set.
    _writeAccel(cfgReg, static_cast<uint8_t>((position ? 0x80 : 0x00) | 0x40 | 0x3F));
    _updateAccel(REG_CTRL_REG5_A, d4dBit, fourD ? d4dBit : static_cast<uint8_t>(0x00));
    return true;
}

bool LSM303Full::setLatch(uint8_t generator, bool enabled) {
    uint8_t bit;
    if (generator == 1) {
        bit = 0x08;  // LIR_INT1
    } else if (generator == 2) {
        bit = 0x02;  // LIR_INT2
    } else {
        return false;
    }
    _updateAccel(REG_CTRL_REG5_A, bit, enabled ? bit : static_cast<uint8_t>(0x00));
    return true;
}

uint8_t LSM303Full::readInterruptSource(uint8_t generator) {
    if (generator == 1) return _readAccel(REG_INT1_SOURCE_A);
    if (generator == 2) return _readAccel(REG_INT2_SOURCE_A);
    return 0;
}

void LSM303Full::setClick(uint8_t singleAxes, uint8_t doubleAxes, float thresholdG,
                          float timeLimitMs, float latencyMs, float windowMs) {
    // CLICK_CFG: bit 2i = single on axis i, bit 2i+1 = double on axis i (X=0, Y=1, Z=2).
    uint8_t cfg = 0;
    for (uint8_t i = 0; i < 3; i++) {
        if (singleAxes & (1 << i)) cfg = static_cast<uint8_t>(cfg | (1 << (2 * i)));
        if (doubleAxes & (1 << i)) cfg = static_cast<uint8_t>(cfg | (1 << (2 * i + 1)));
    }
    float odrPerMs = odrHzFor(_odrCode, _mode) / 1000.0f;
    _writeAccel(REG_CLICK_CFG_A, cfg);
    _writeAccel(REG_CLICK_THS_A, toCode(thresholdG, 128.0f / static_cast<float>(fsGFor(_fsCode)), 127));
    _writeAccel(REG_TIME_LIMIT_A, toCode(timeLimitMs, odrPerMs, 127));
    _writeAccel(REG_TIME_LATENCY_A, toCode(latencyMs, odrPerMs, 255));
    _writeAccel(REG_TIME_WINDOW_A, toCode(windowMs, odrPerMs, 255));
}

uint8_t LSM303Full::readClickSource() {
    return _readAccel(REG_CLICK_SRC_A);
}

void LSM303Full::setInterruptPolarity(bool activeLow) {
    _updateAccel(REG_CTRL_REG6_A, 0x02, activeLow ? 0x02 : 0x00);
    _intActiveLow = activeLow;
}

bool LSM303Full::setMagRange(int rangeUt) {
    for (uint8_t i = 0; i < 7; i++) {
        if (kMagRanges[i].ut == rangeUt) {
            _writeMag(REG_CRB_REG_M, static_cast<uint8_t>(kMagRanges[i].gn << 5));
            _magGainXY = kMagRanges[i].xy;
            _magGainZ = kMagRanges[i].z;
            return true;
        }
    }
    return false;
}

bool LSM303Full::setMagDataRate(float rateHz) {
    for (uint8_t i = 0; i < 8; i++) {
        float d = kMagRates[i].hz - rateHz;
        if (d < 0.0f) d = -d;
        if (d < 0.001f) {
            // DO<2:0> is bits 4:2. TEMP_EN (bit 7) is kept.
            _updateMag(REG_CRA_REG_M, 0x1C, static_cast<uint8_t>(kMagRates[i].code << 2));
            return true;
        }
    }
    return false;
}

bool LSM303Full::setMagMode(uint8_t mode) {
    if (mode > 2) return false;
    _writeMag(REG_MR_REG_M, mode);
    return true;
}

void LSM303Full::readMagneticRaw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _magConn.read(REG_OUT_X_H_M, buf, 6);
    x = toInt16(buf[0], buf[1]);
    z = toInt16(buf[2], buf[3]);
    y = toInt16(buf[4], buf[5]);
}

bool LSM303Full::magDataReady() {
    return (_readMag(REG_SR_REG_M) & 0x01) != 0;
}

void LSM303Full::readMagStatus(bool& drdy, bool& lock) {
    uint8_t v = _readMag(REG_SR_REG_M);
    drdy = (v & 0x01) != 0;
    lock = (v & 0x02) != 0;
}

void LSM303Full::readMagIdentity(uint8_t* buf) {
    _magConn.read(REG_IRA_REG_M, buf, 3);
}

void LSM303Full::enableTemperature(bool enabled) {
    _updateMag(REG_CRA_REG_M, 0x80, enabled ? 0x80 : 0x00);
    _tempEnabled = enabled;
}

float LSM303Full::readTemperatureDelta() {
    if (!_tempEnabled) enableTemperature(true);
    uint8_t buf[2];
    _magConn.read(REG_TEMP_OUT_H_M, buf, 2);
    int16_t raw = static_cast<int16_t>(toInt16(buf[0], buf[1]) >> 4);  // 12-bit, left-justified
    return static_cast<float>(raw) / 8.0f;                              // 8 LSB/°C
}

float LSM303Full::heading(bool tiltCompensate) {
    float mx, my, mz;
    readMagnetic(mx, my, mz);
    float h;
    if (!tiltCompensate) {
        h = static_cast<float>(atan2(my, mx));
    } else {
        float ax, ay, az;
        readAcceleration(ax, ay, az);
        float n = static_cast<float>(sqrt(ax * ax + ay * ay + az * az));
        if (n > 0.0f) { ax /= n; ay /= n; az /= n; }
        float roll  = static_cast<float>(atan2(ay, az));
        float pitch = static_cast<float>(atan2(-ax, ay * sin(roll) + az * cos(roll)));
        float sr = sin(roll), cr = cos(roll);
        float sp = sin(pitch), cp = cos(pitch);
        float xh = mx * cp + mz * sp;
        float yh = mx * sr * sp + my * cr - mz * sr * cp;
        h = static_cast<float>(atan2(yh, xh));
    }
    float deg = h * (180.0f / PI_F);
    while (deg < 0.0f) deg += 360.0f;
    while (deg >= 360.0f) deg -= 360.0f;
    return deg;
}

// ---------------------------------------------------------------------------
// LSM303Full — interrupts
// ---------------------------------------------------------------------------

bool LSM303Full::_routeTarget(uint16_t source, uint8_t& pin, uint8_t& reg, uint8_t& bit) const {
    if (pin != 1 && pin != 2) return false;
    switch (source) {
        case LSM303Source::DATA_READY:  pin = 1; reg = REG_CTRL_REG3_A; bit = 0x10; return true;
        case LSM303Source::WATERMARK:   pin = 1; reg = REG_CTRL_REG3_A; bit = 0x04; return true;
        case LSM303Source::OVERRUN:     pin = 1; reg = REG_CTRL_REG3_A; bit = 0x02; return true;
        case LSM303Source::IA1:
            reg = (pin == 1) ? REG_CTRL_REG3_A : REG_CTRL_REG6_A; bit = 0x40; return true;
        case LSM303Source::IA2:
            reg = (pin == 1) ? REG_CTRL_REG3_A : REG_CTRL_REG6_A; bit = 0x20; return true;
        case LSM303Source::CLICK:
            reg = (pin == 1) ? REG_CTRL_REG3_A : REG_CTRL_REG6_A; bit = 0x80; return true;
        case LSM303Source::BOOT:        pin = 2; reg = REG_CTRL_REG6_A; bit = 0x10; return true;
        default:                        return false;  // MAG_DATA_READY and unknown bits
    }
}

bool LSM303Full::enableInterrupt(uint16_t source, uint8_t pin) {
    uint8_t reg = 0, bit = 0;
    if (!_routeTarget(source, pin, reg, bit)) return false;
    _updateAccel(reg, bit, bit);
    _routed[pin - 1] = static_cast<uint16_t>(_routed[pin - 1] | source);
    return true;
}

bool LSM303Full::disableInterrupt(uint16_t source, uint8_t pin) {
    uint8_t reg = 0, bit = 0;
    if (!_routeTarget(source, pin, reg, bit)) return false;
    _updateAccel(reg, bit, 0x00);
    _routed[pin - 1] = static_cast<uint16_t>(_routed[pin - 1] & ~source);
    return true;
}

void LSM303Full::_trampoline1() { _dispatch(0); }
void LSM303Full::_trampoline2() { _dispatch(1); }

void LSM303Full::_dispatch(uint8_t idx) {
    LSM303Full* owner = _owner[idx];
    if (owner && _cb[idx]) _cb[idx](owner->pollInterrupt());
}

bool LSM303Full::onInterrupt(void (*callback)(uint8_t status), uint8_t pin, InputPin* intPin) {
    if (!callback || (pin != 1 && pin != 2)) return false;
    InputPin* p = intPin;
    if (!p && pin == 1) p = _accelConn.intPin();
    if (!p) return false;
    uint8_t idx = pin - 1;
    offInterrupt(pin);  // replace any earlier subscription on this pin
    InputPin::Handler h = (idx == 0) ? &LSM303Full::_trampoline1 : &LSM303Full::_trampoline2;
    if (!p->onEdge(h, _intActiveLow ? InputPin::kFalling : InputPin::kRising)) return false;
    _cb[idx] = callback;
    _owner[idx] = this;
    _intPinUsed[idx] = p;
    return true;
}

void LSM303Full::offInterrupt(uint8_t pin) {
    if (pin != 1 && pin != 2) return;
    uint8_t idx = pin - 1;
    if (_intPinUsed[idx]) {
        InputPin::Handler h = (idx == 0) ? &LSM303Full::_trampoline1 : &LSM303Full::_trampoline2;
        _intPinUsed[idx]->offEdge(h);
    }
    _intPinUsed[idx] = nullptr;
    _cb[idx] = nullptr;
    _owner[idx] = nullptr;
}

uint8_t LSM303Full::pollInterrupt() {
    uint16_t mask = static_cast<uint16_t>(_routed[0] | _routed[1]);
    uint8_t status = 0;

    if (mask & LSM303Source::DATA_READY) {
        if (_readAccel(REG_STATUS_REG_A) & 0x08) status = static_cast<uint8_t>(status | LSM303Source::DATA_READY);
    }
    if (mask & (LSM303Source::WATERMARK | LSM303Source::OVERRUN)) {
        uint8_t f = _readAccel(REG_FIFO_SRC_REG_A);
        if ((mask & LSM303Source::WATERMARK) && (f & 0x80)) status = static_cast<uint8_t>(status | LSM303Source::WATERMARK);
        if ((mask & LSM303Source::OVERRUN) && (f & 0x40))   status = static_cast<uint8_t>(status | LSM303Source::OVERRUN);
    }
    if (mask & LSM303Source::IA1) {
        if (_readAccel(REG_INT1_SOURCE_A) & 0x40) status = static_cast<uint8_t>(status | LSM303Source::IA1);
    }
    if (mask & LSM303Source::IA2) {
        if (_readAccel(REG_INT2_SOURCE_A) & 0x40) status = static_cast<uint8_t>(status | LSM303Source::IA2);
    }
    if (mask & LSM303Source::CLICK) {
        if (_readAccel(REG_CLICK_SRC_A) & 0x40) status = static_cast<uint8_t>(status | LSM303Source::CLICK);
    }
    // The magnetometer DRDY pin cannot be routed, so its flag is always checked.
    if (_readMag(REG_SR_REG_M) & 0x01) status = static_cast<uint8_t>(status | LSM303Source::MAG_DATA_READY);
    return status;
}

void LSM303Full::reboot() {
    _updateAccel(REG_CTRL_REG5_A, 0x80, 0x80);  // BOOT
    lsm303DelayMs(5);
    _routed[0] = 0;
    _routed[1] = 0;
    _init();
}
