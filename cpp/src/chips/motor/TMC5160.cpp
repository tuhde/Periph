#include "TMC5160.h"
#include <math.h>
#include <string.h>

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

constexpr uint8_t TMC5160Minimal::FAULT_OVERTEMP_WARNING;
constexpr uint8_t TMC5160Minimal::FAULT_OVERTEMP;
constexpr uint8_t TMC5160Minimal::FAULT_SHORT_GND;
constexpr uint8_t TMC5160Minimal::FAULT_SHORT_SUPPLY;
constexpr uint8_t TMC5160Minimal::FAULT_OPEN_LOAD;

static const uint32_t GCONF_EN_PWM_MODE = 1UL << 2;
static const uint32_t GCONF_SHAFT = 1UL << 4;
static const uint32_t GCONF_SMALL_HYSTERESIS = 1UL << 14;
static const uint32_t GCONF_STOP_ENABLE = 1UL << 15;
static const uint32_t GCONF_DIRECT_MODE = 1UL << 16;
static const uint32_t SW_SG_STOP = 1UL << 10;
static const uint32_t SW_SOFTSTOP = 1UL << 11;
static const uint8_t TMC5160_VERSION = 0x30;
static const double V_FS = 0.325;
static const uint32_t CHOPCONF_DEFAULT = 0x000100C3UL;
static const uint32_t PWMCONF_RESET = 0xC40C001EUL;

static int32_t signExtend(uint32_t v, int bits) {
    v &= (1UL << bits) - 1;
    if (v & (1UL << (bits - 1))) return (int32_t)v - (int32_t)(1UL << bits);
    return (int32_t)v;
}

static uint32_t bitIf(bool b, uint32_t bits) { return b ? bits : 0; }

// Round half away from zero without libm; saturates far outside every register range.
static int64_t rnd(double x) {
    if (x > 1.0e12) return (int64_t)1000000000000LL;
    if (x < -1.0e12) return -(int64_t)1000000000000LL;
    return (int64_t)(x < 0 ? x - 0.5 : x + 0.5);
}

uint8_t TMC5160Minimal::crc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        uint8_t byte = data[i];
        for (int b = 0; b < 8; b++) {
            if ((crc >> 7) ^ (byte & 1)) crc = (uint8_t)((crc << 1) ^ 0x07);
            else crc = (uint8_t)(crc << 1);
            byte >>= 1;
        }
    }
    return crc;
}

TMC5160Minimal::TMC5160Minimal(Connection& connection, float rSenseOhm, OutputPin* enPin)
    : _conn(connection), _enPin(enPin), _rSense(rSenseOhm), _uart(false), _echo(false), _multiNode(false),
      _address(0), _ok(false), _fclk(12000000UL), _usc(256), _toff(3), _rampMode(0), _speed(200.0f),
      _accel(400.0f), _speedReg(0), _irun(0), _ihold(0), _iholdDelay(6), _sendDelay(0), _lastDist(0.0f),
      _lastSpeed(200.0f), _lastAccel(400.0f), _writes(0) {
    memset(_shadow, 0, sizeof(_shadow));
    memset(_seen, 0, sizeof(_seen));
    initialise();
}

TMC5160Minimal::TMC5160Minimal(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin,
                               bool echo, bool multiNode)
    : _conn(connection), _enPin(enPin), _rSense(rSenseOhm), _uart(true), _echo(echo), _multiNode(multiNode),
      _address(address), _ok(false), _fclk(12000000UL), _usc(256), _toff(3), _rampMode(0), _speed(200.0f),
      _accel(400.0f), _speedReg(0), _irun(0), _ihold(0), _iholdDelay(6), _sendDelay(0), _lastDist(0.0f),
      _lastSpeed(200.0f), _lastAccel(400.0f), _writes(0) {
    memset(_shadow, 0, sizeof(_shadow));
    memset(_seen, 0, sizeof(_seen));
    if (address > 253) return;  // _ok stays false: node address must be 0-253
    initialise();
}

void TMC5160Minimal::delayMs(unsigned ms) { periph_delay_ms(ms); }

void TMC5160Minimal::recover() {
    // Discard stale receive bytes when the connection can report them, then
    // give the chip its >= 12 bit times of bus idle.
    size_t pending = _conn.available();
    while (pending > 0 && pending != SIZE_MAX) {
        uint8_t sink[16];
        size_t n = pending < sizeof(sink) ? pending : sizeof(sink);
        _conn.read(sink, n);
        pending = _conn.available();
    }
    periph_delay_ms(2);
}

bool TMC5160Minimal::writeUart(uint8_t reg, uint32_t value) {
    uint8_t dg[8] = {0x05, _address, (uint8_t)(reg | 0x80), (uint8_t)(value >> 24),
                     (uint8_t)(value >> 16), (uint8_t)(value >> 8), (uint8_t)value, 0};
    dg[7] = crc8(dg, 7);
    _conn.write(dg, 8);
    if (_echo) {
        uint8_t echoed[8] = {0};
        _conn.read(echoed, 8);
        if (memcmp(echoed, dg, 8) != 0) return false;
    }
    return true;
}

bool TMC5160Minimal::readUart(uint8_t reg, uint32_t& value) {
    uint8_t req[4] = {0x05, _address, (uint8_t)(reg & 0x7F), 0};
    req[3] = crc8(req, 3);
    uint8_t raw[12] = {0};
    const uint8_t* reply;
    if (_echo) {
        _conn.write_read(req, 4, raw, 12);
        if (memcmp(raw, req, 4) != 0) return false;
        reply = raw + 4;
    } else {
        _conn.write_read(req, 4, raw, 8);
        reply = raw;
    }
    if (reply[0] != 0x05 || reply[1] != 0xFF || reply[2] != (reg & 0x7F)) return false;
    if (crc8(reply, 7) != reply[7]) return false;
    value = ((uint32_t)reply[3] << 24) | ((uint32_t)reply[4] << 16) | ((uint32_t)reply[5] << 8) | reply[6];
    return true;
}

// A harmless GCONF read request whose reply answers the previous datagram
// (pipelined SPI protocol). Never GSTAT, which clears on read.
uint32_t TMC5160Minimal::spiSecond() {
    uint8_t req[1] = {REG_GCONF};
    uint8_t r[4] = {0};
    _conn.write_read(req, 1, r, 4);
    return ((uint32_t)r[0] << 24) | ((uint32_t)r[1] << 16) | ((uint32_t)r[2] << 8) | r[3];
}

bool TMC5160Minimal::writeReg(uint8_t reg, uint32_t value) {
    if (_uart) {
        bool done = writeUart(reg, value);
        if (!done) {
            recover();
            done = writeUart(reg, value);
        }
        if (!done) { _ok = false; return false; }
    } else {
        uint8_t frame[5] = {(uint8_t)(reg | 0x80), (uint8_t)(value >> 24), (uint8_t)(value >> 16),
                            (uint8_t)(value >> 8), (uint8_t)value};
        _conn.write(frame, 5);
    }
    _writes++;
    _shadow[reg] = value;
    _seen[reg >> 3] |= (uint8_t)(1u << (reg & 7));
    return true;
}

uint32_t TMC5160Minimal::readReg(uint8_t reg) {
    if (_uart) {
        uint32_t value = 0;
        bool done = readUart(reg, value);
        if (!done) {
            recover();
            done = readUart(reg, value);
        }
        _ok = done;
        return done ? value : 0;
    }
    uint8_t req[1] = {(uint8_t)(reg & 0x7F)};
    uint8_t ignored[4] = {0};
    _conn.write_read(req, 1, ignored, 4);
    return spiSecond();
}

bool TMC5160Minimal::writeVerified(uint8_t reg, uint32_t value) {
    if (_uart) {
        uint8_t before = (uint8_t)readReg(REG_IFCNT);
        if (!_ok || !writeReg(reg, value)) return false;
        uint8_t after = (uint8_t)readReg(REG_IFCNT);
        if (!_ok || after != (uint8_t)(before + 1)) { _ok = false; return false; }
        return true;
    }
    if (!writeReg(reg, value)) return false;
    if (spiSecond() != value) { _ok = false; return false; }
    return true;
}

bool TMC5160Minimal::modify(uint8_t reg, uint32_t mask, uint32_t value) {
    return writeReg(reg, (_shadow[reg] & ~mask) | (value & mask));
}

void TMC5160Minimal::firstUse(uint8_t reg, uint32_t reset) {
    if (!(_seen[reg >> 3] & (1u << (reg & 7)))) {
        _shadow[reg] = reset;
        _seen[reg >> 3] |= (uint8_t)(1u << (reg & 7));
    }
}

int64_t TMC5160Minimal::vReg(double v) const { return rnd(v * (double)_usc * 16777216.0 / (double)_fclk); }

int64_t TMC5160Minimal::aReg(double a) const {
    return rnd(a * (double)_usc * 2199023255552.0 / ((double)_fclk * (double)_fclk));
}

double TMC5160Minimal::iMax() const { return V_FS / ((double)_rSense * 1.41421356237); }

bool TMC5160Minimal::applyCurrent(float rmsA) {
    double r = (double)rmsA / iMax();
    if (r > 1.0 || r < 1.0 / 64.0) return false;
    uint8_t irun;
    uint32_t scaler;
    if (r >= 0.5) {
        irun = 31;
        scaler = (uint32_t)rnd(256.0 * r);
    } else {
        int64_t v = rnd(64.0 * r) - 1;
        irun = (uint8_t)(v < 0 ? 0 : (v > 31 ? 31 : v));
        scaler = 128;
    }
    if (!writeReg(REG_GLOBALSCALER, scaler >= 256 ? 0 : scaler)) return false;
    _irun = irun;
    _ihold = irun / 3;
    _iholdDelay = 6;
    return writeIholdIrun();
}

bool TMC5160Minimal::writeIholdIrun() {
    return writeReg(REG_IHOLD_IRUN, (uint32_t)(_ihold & 0x1F) | ((uint32_t)(_irun & 0x1F) << 8) |
                                    ((uint32_t)(_iholdDelay & 0xF) << 16));
}

void TMC5160Minimal::initialise() {
    if (_enPin) _enPin->set(true);
    delayMs(10);
    _ok = true;
    uint8_t base = 0;
    if (_uart) {
        base = (uint8_t)readReg(REG_IFCNT);
        if (!_ok) return;
    }
    uint32_t ioin = readReg(REG_IOIN);
    if (!_ok || (uint8_t)(ioin >> 24) != TMC5160_VERSION || (ioin & (1UL << 6))) { _ok = false; return; }
    _writes = 0;
    if (_uart && _multiNode) {
        _sendDelay = 2;
        if (!writeVerified(REG_SLAVECONF, 2UL << 8)) return;
        _writes = 0;
    }
    readReg(REG_GSTAT);
    if (!_ok) return;
    double maxA = iMax();
    float rms = (float)(maxA < 0.5 ? maxA : 0.5);
    if (!writeReg(REG_GSTAT, 0x07)) return;
    if (!writeReg(REG_GLOBALSCALER, 0)) return;
    if (!applyCurrent(rms)) { _ok = false; return; }
    if (!writeReg(REG_TPOWERDOWN, 10)) return;
    if (!writeReg(REG_CHOPCONF, CHOPCONF_DEFAULT)) return;
    if (!writeReg(REG_GCONF, GCONF_EN_PWM_MODE)) return;
    if (!writeReg(REG_TPWMTHRS, 500)) return;
    if (!writeReg(REG_RAMPMODE, 0)) return;
    if (!writeReg(REG_VSTART, 0)) return;
    if (!writeReg(REG_V1, 0)) return;
    if (!writeReg(REG_VSTOP, 10)) return;
    if (!writeReg(REG_TZEROWAIT, 0)) return;
    if (!setSpeed(200.0f) || !setAcceleration(400.0f)) { _ok = false; return; }
    if (_uart) {
        uint8_t after = (uint8_t)readReg(REG_IFCNT);
        if (!_ok || after != (uint8_t)(base + _writes)) _ok = false;
    } else if (readReg(REG_GCONF) != _shadow[REG_GCONF] || readReg(REG_CHOPCONF) != _shadow[REG_CHOPCONF]) {
        _ok = false;
    }
    _rampMode = 0;
}

void TMC5160Minimal::enable() {
    if (_enPin) _enPin->set(false);
    else writeReg(REG_CHOPCONF, (_shadow[REG_CHOPCONF] & ~0xFUL) | _toff);
    delayMs(150);
}

void TMC5160Minimal::disable() {
    if (_enPin) { _enPin->set(true); return; }
    uint32_t chop = _shadow[REG_CHOPCONF];
    if (chop & 0xF) _toff = (uint8_t)(chop & 0xF);
    writeReg(REG_CHOPCONF, chop & ~0xFUL);
}

bool TMC5160Minimal::setCurrent(float rmsA) { return applyCurrent(rmsA); }

bool TMC5160Minimal::setSpeed(float fullStepsPerS) {
    if (fullStepsPerS <= 0.0f) return false;
    int64_t reg = vReg(fullStepsPerS);
    if (reg < 0 || reg > (1LL << 23) - 512) return false;
    _speed = fullStepsPerS;
    _speedReg = (uint32_t)reg;
    if (_rampMode == 0) return writeReg(REG_VMAX, _speedReg);
    return true;
}

bool TMC5160Minimal::setAcceleration(float fullStepsPerS2) {
    if (fullStepsPerS2 <= 0.0f) return false;
    int64_t reg = aReg(fullStepsPerS2);
    if (reg < 1 || reg > 0xFFFF) return false;
    _accel = fullStepsPerS2;
    static const uint8_t regs[4] = {REG_A1, REG_AMAX, REG_DMAX, REG_D1};
    for (int i = 0; i < 4; i++) {
        if (!writeReg(regs[i], (uint32_t)reg)) return false;
    }
    return true;
}

bool TMC5160Minimal::moveTo(float position) {
    int32_t target = (int32_t)rnd((double)position * (double)_usc);
    float cur = this->position();
    if (!_ok) return false;
    float d = position - cur;
    _lastDist = d < 0 ? -d : d;
    _lastSpeed = _speed;
    _lastAccel = _accel;
    if (!writeReg(REG_VMAX, _speedReg)) return false;
    if (!writeReg(REG_RAMPMODE, 0)) return false;
    _rampMode = 0;
    return writeReg(REG_XTARGET, (uint32_t)target);
}

bool TMC5160Minimal::moveSteps(float fullSteps, float fullStepsPerS) {
    float saved = _speed;
    if (fullStepsPerS > 0.0f && !setSpeed(fullStepsPerS)) return false;
    float cur = position();
    bool done = _ok && moveTo(cur + fullSteps) && waitUntilReached(0.0f);
    if (fullStepsPerS > 0.0f) setSpeed(saved);
    return done;
}

bool TMC5160Minimal::run(float fullStepsPerS) {
    float a = fullStepsPerS < 0 ? -fullStepsPerS : fullStepsPerS;
    int64_t reg = vReg(a);
    if (reg < 0 || reg > (1LL << 23) - 512) return false;
    uint8_t mode = fullStepsPerS < 0 ? 2 : 1;
    if (!writeReg(REG_RAMPMODE, mode)) return false;
    _rampMode = mode;
    return writeReg(REG_VMAX, (uint32_t)reg);
}

bool TMC5160Minimal::stop() {
    if (!writeReg(REG_RAMPMODE, 1)) return false;
    _rampMode = 1;
    return writeReg(REG_VMAX, 0);
}

float TMC5160Minimal::position() { return (float)((int32_t)readReg(REG_XACTUAL)) / (float)_usc; }

bool TMC5160Minimal::setPosition(float position) {
    uint32_t v = (uint32_t)(int32_t)rnd((double)position * (double)_usc);
    return writeReg(REG_XACTUAL, v) && writeReg(REG_XTARGET, v);
}

float TMC5160Minimal::velocity() {
    int32_t raw = signExtend(readReg(REG_VACTUAL), 24);
    return (float)((double)raw * ((double)_fclk / 2.0 / 8388608.0) / (double)_usc);
}

bool TMC5160Minimal::isPositionReached() { return (readReg(REG_RAMP_STAT) & (1UL << 9)) != 0; }

bool TMC5160Minimal::waitUntilReached(float timeoutS) {
    float timeout = timeoutS;
    if (timeout <= 0.0f) timeout = 2.0f * _lastDist / _lastSpeed + 2.0f * _lastSpeed / _lastAccel + 1.0f;
    uint32_t bit = _rampMode == 0 ? (1UL << 9) : (1UL << 10);
    delayMs(1);
    float waited = 0.0f;
    while (true) {
        uint32_t s = readReg(REG_RAMP_STAT);
        if (!_ok) return false;
        if (s & bit) return true;
        if (waited >= timeout) return false;
        delayMs(5);
        waited += 0.005f;
    }
}

bool TMC5160Minimal::isStandstill() { return (readReg(REG_DRV_STATUS) & (1UL << 31)) != 0; }

uint8_t TMC5160Minimal::readFault() {
    uint32_t s = readReg(REG_DRV_STATUS);
    uint8_t f = 0;
    if (s & (1UL << 26)) f |= FAULT_OVERTEMP_WARNING;
    if (s & (1UL << 25)) f |= FAULT_OVERTEMP;
    if (s & (3UL << 27)) f |= FAULT_SHORT_GND;
    if (s & (3UL << 12)) f |= FAULT_SHORT_SUPPLY;
    if (s & (3UL << 29)) f |= FAULT_OPEN_LOAD;
    return f;
}

// ---- TMC5160Full ----

TMC5160Full::TMC5160Full(Connection& connection, float rSenseOhm, OutputPin* enPin, InputPin* diag0Pin,
                         InputPin* diag1Pin)
    : TMC5160Minimal(connection, rSenseOhm, enPin), _diag0(diag0Pin), _diag1(diag1Pin) {}

TMC5160Full::TMC5160Full(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo,
                         bool multiNode)
    : TMC5160Minimal(connection, rSenseOhm, address, enPin, echo, multiNode), _diag0(nullptr), _diag1(nullptr) {}

static bool isReadable(uint8_t r) {
    switch (r) {
        case 0x00: case 0x01: case 0x02: case 0x04: case 0x07: case 0x0C: case 0x12: case 0x20: case 0x21:
        case 0x22: case 0x2D: case 0x34: case 0x35: case 0x36: case 0x38: case 0x39: case 0x3B: case 0x3C:
        case 0x6A: case 0x6B: case 0x6C: case 0x6F: case 0x71: case 0x72: case 0x73:
            return true;
        default: return false;
    }
}

static bool isWritable(uint8_t r) {
    switch (r) {
        case 0x00: case 0x01: case 0x03: case 0x04: case 0x05: case 0x09: case 0x0A: case 0x0B: case 0x10:
        case 0x11: case 0x13: case 0x14: case 0x15: case 0x20: case 0x21: case 0x23: case 0x24: case 0x25:
        case 0x26: case 0x27: case 0x28: case 0x2A: case 0x2B: case 0x2C: case 0x2D: case 0x33: case 0x34:
        case 0x35: case 0x38: case 0x39: case 0x3A: case 0x3B: case 0x3D: case 0x60: case 0x61: case 0x62:
        case 0x63: case 0x64: case 0x65: case 0x66: case 0x67: case 0x68: case 0x69: case 0x6C: case 0x6D:
        case 0x6E: case 0x70:
            return true;
        default: return false;
    }
}

uint32_t TMC5160Full::readRegister(uint8_t reg) {
    if (!isReadable(reg)) { _ok = false; return 0; }
    _ok = true;
    return readReg(reg);
}

bool TMC5160Full::writeRegister(uint8_t reg, uint32_t value, bool verify) {
    if (!isWritable(reg)) return false;
    return verify ? writeVerified(reg, value) : writeReg(reg, value);
}

bool TMC5160Full::setHoldCurrent(float rmsA) {
    double gs = _shadow[REG_GLOBALSCALER] == 0 ? 256.0 : (double)_shadow[REG_GLOBALSCALER];
    double full = gs / 256.0 * iMax();
    int64_t h = rnd((double)rmsA / full * 32.0) - 1;
    if (rmsA <= 0.0f || h > _irun) return false;
    _ihold = (uint8_t)(h < 0 ? 0 : h);
    return writeIholdIrun();
}

bool TMC5160Full::setGlobalScaler(uint16_t value) {
    if (!(value == 0 || (value >= 32 && value <= 256))) return false;
    return writeReg(REG_GLOBALSCALER, value == 256 ? 0 : value);
}

bool TMC5160Full::setCurrentScale(uint8_t irun, uint8_t ihold, uint8_t iholdDelay) {
    if (irun > 31 || ihold > 31 || iholdDelay > 15) return false;
    _irun = irun;
    _ihold = ihold;
    _iholdDelay = iholdDelay;
    return writeIholdIrun();
}

bool TMC5160Full::setPowerDownDelay(uint8_t tpowerdown) { return writeReg(REG_TPOWERDOWN, tpowerdown); }

bool TMC5160Full::configureRamp(float vstart, float a1, float v1, float amax, float vmax, float dmax, float d1,
                                float vstop) {
    int64_t raw[8] = {vReg(vstart), aReg(a1), vReg(v1), aReg(amax), vReg(vmax), aReg(dmax), aReg(d1), vReg(vstop)};
    for (int i = 0; i < 8; i++) {
        if (raw[i] < 0 || raw[i] > 0xFFFFFFFFLL) return false;
    }
    if (!setRampRaw((uint32_t)raw[0], (uint32_t)raw[1], (uint32_t)raw[2], (uint32_t)raw[3], (uint32_t)raw[4],
                    (uint32_t)raw[5], (uint32_t)raw[6], (uint32_t)raw[7])) return false;
    _speed = vmax;
    _accel = amax;
    return true;
}

bool TMC5160Full::setRampRaw(uint32_t vstart, uint32_t a1, uint32_t v1, uint32_t amax, uint32_t vmax,
                             uint32_t dmax, uint32_t d1, uint32_t vstop) {
    if (vstart >= (1UL << 18) || a1 > 0xFFFF || v1 >= (1UL << 20) || amax > 0xFFFF || vmax > (1UL << 23) - 512 ||
        dmax > 0xFFFF || d1 > 0xFFFF || vstop >= (1UL << 18) || d1 < 1 || vstop < 1 || vstop < vstart) return false;
    if (!writeReg(REG_VSTART, vstart) || !writeReg(REG_A1, a1) || !writeReg(REG_V1, v1) ||
        !writeReg(REG_AMAX, amax) || !writeReg(REG_VMAX, vmax) || !writeReg(REG_DMAX, dmax) ||
        !writeReg(REG_D1, d1) || !writeReg(REG_VSTOP, vstop)) return false;
    _speedReg = vmax;
    return true;
}

bool TMC5160Full::setZeroWait(float seconds) {
    int64_t v = rnd((double)seconds * (double)_fclk / 512.0);
    if (v < 0 || v > 0xFFFF) return false;
    return writeReg(REG_TZEROWAIT, (uint32_t)v);
}

bool TMC5160Full::setRampMode(uint8_t mode) {
    if (mode > 3) return false;
    if (!writeReg(REG_RAMPMODE, mode)) return false;
    _rampMode = mode;
    return true;
}

bool TMC5160Full::hold() { return setRampMode(3); }

bool TMC5160Full::setTargetRaw(int32_t xtarget) { return writeReg(REG_XTARGET, (uint32_t)xtarget); }

TMC5160Full::RampStatus TMC5160Full::readRampStatus() {
    uint32_t s = readReg(REG_RAMP_STAT);
    RampStatus r;
    r.statusStopL = s & (1UL << 0); r.statusStopR = s & (1UL << 1); r.statusLatchL = s & (1UL << 2);
    r.statusLatchR = s & (1UL << 3); r.eventStopL = s & (1UL << 4); r.eventStopR = s & (1UL << 5);
    r.eventStopSg = s & (1UL << 6); r.eventPosReached = s & (1UL << 7); r.velocityReached = s & (1UL << 8);
    r.positionReached = s & (1UL << 9); r.vzero = s & (1UL << 10); r.tZerowaitActive = s & (1UL << 11);
    r.secondMove = s & (1UL << 12); r.statusSg = s & (1UL << 13);
    return r;
}

bool TMC5160Full::clearRampEvents() {
    return writeReg(REG_RAMP_STAT, (1UL << 2) | (1UL << 3) | (1UL << 6) | (1UL << 7) | (1UL << 12));
}

bool TMC5160Full::setPositionCompare(float position) {
    return writeReg(REG_X_COMPARE, (uint32_t)(int32_t)rnd((double)position * (double)_usc));
}

bool TMC5160Full::configureSwitches(bool stopLeft, bool stopRight, bool leftActiveLow, bool rightActiveLow,
                                    bool swap, bool softStop) {
    if (softStop && (_shadow[REG_SW_MODE] & SW_SG_STOP)) return false;
    uint32_t v = bitIf(stopLeft, 1) | bitIf(stopRight, 2) | bitIf(leftActiveLow, 4) | bitIf(rightActiveLow, 8) |
                 bitIf(swap, 16) | bitIf(softStop, SW_SOFTSTOP);
    return modify(REG_SW_MODE, 0x1FUL | SW_SOFTSTOP, v);
}

bool TMC5160Full::configureLatch(bool leftActive, bool leftInactive, bool rightActive, bool rightInactive,
                                 bool latchEncoder) {
    uint32_t v = bitIf(leftActive, 1UL << 5) | bitIf(leftInactive, 1UL << 6) | bitIf(rightActive, 1UL << 7) |
                 bitIf(rightInactive, 1UL << 8) | bitIf(latchEncoder, 1UL << 9);
    return modify(REG_SW_MODE, 0x1FUL << 5, v);
}

float TMC5160Full::readLatchedPosition() { return (float)((int32_t)readReg(REG_XLATCH)) / (float)_usc; }

void TMC5160Full::readSwitches(bool& left, bool& right) {
    uint32_t s = readReg(REG_RAMP_STAT);
    left = (s & 1) != 0;
    right = (s & 2) != 0;
}

bool TMC5160Full::setMicrosteps(uint16_t n) {
    if (n == 0 || n > 256 || (n & (n - 1)) != 0) return false;
    uint8_t mres = 8;
    for (uint16_t v = n; v > 1; v >>= 1) mres--;
    if (!modify(REG_CHOPCONF, 0xFUL << 24, (uint32_t)mres << 24)) return false;
    _usc = n;
    return true;
}

bool TMC5160Full::setInterpolation(bool enabled) { return modify(REG_CHOPCONF, 1UL << 28, bitIf(enabled, 1UL << 28)); }

bool TMC5160Full::setChopperMode(bool spreadcycle) {
    return modify(REG_GCONF, GCONF_EN_PWM_MODE, bitIf(!spreadcycle, GCONF_EN_PWM_MODE));
}

bool TMC5160Full::threshold(uint8_t reg, uint32_t v) {
    if (v > 0xFFFFFUL) return false;
    return writeReg(reg, v);
}

bool TMC5160Full::setStealthchopThreshold(uint32_t tpwmthrs) { return threshold(REG_TPWMTHRS, tpwmthrs); }
bool TMC5160Full::setHighVelocityThreshold(uint32_t thigh) { return threshold(REG_THIGH, thigh); }
bool TMC5160Full::setCoolstepThreshold(uint32_t tcoolthrs) { return threshold(REG_TCOOLTHRS, tcoolthrs); }

bool TMC5160Full::configureChopper(uint8_t toff, uint8_t tbl, uint8_t hstrt, uint8_t hend, uint8_t tpfd) {
    if (toff > 15 || tbl > 3 || hstrt > 7 || hend > 15 || tpfd > 15) return false;
    if (toff) _toff = toff;
    uint32_t mask = 0xFUL | (3UL << 15) | (7UL << 4) | (0xFUL << 7) | (0xFUL << 20) | (1UL << 14) | (1UL << 11);
    uint32_t v = (uint32_t)toff | ((uint32_t)tbl << 15) | ((uint32_t)hstrt << 4) | ((uint32_t)hend << 7) |
                 ((uint32_t)tpfd << 20);
    return modify(REG_CHOPCONF, mask, v);
}

bool TMC5160Full::configureConstantOffTime(uint8_t toff, uint8_t tbl, uint8_t tfd, int8_t offset, bool disfdcc,
                                           uint8_t tpfd) {
    if (toff > 15 || tbl > 3 || tfd > 15 || offset < -3 || offset > 12 || tpfd > 15) return false;
    if (toff) _toff = toff;
    uint32_t mask = 0xFUL | (3UL << 15) | (7UL << 4) | (0xFUL << 7) | (0xFUL << 20) | (1UL << 14) | (1UL << 12) |
                    (1UL << 11);
    uint32_t v = (uint32_t)toff | ((uint32_t)tbl << 15) | ((uint32_t)(tfd & 7) << 4) |
                 ((uint32_t)(offset + 3) << 7) | ((uint32_t)tpfd << 20) | (1UL << 14) | bitIf(disfdcc, 1UL << 12) |
                 ((uint32_t)((tfd >> 3) & 1) << 11);
    return modify(REG_CHOPCONF, mask, v);
}

bool TMC5160Full::configureHighVelocity(bool vhighchm, bool vhighfs) {
    return modify(REG_CHOPCONF, (1UL << 19) | (1UL << 18), bitIf(vhighchm, 1UL << 19) | bitIf(vhighfs, 1UL << 18));
}

bool TMC5160Full::configureStealthchop(bool autoscale, bool autograd, uint8_t pwmOfs, uint8_t pwmGrad,
                                       uint8_t pwmFreq, uint8_t pwmReg, uint8_t pwmLim) {
    if (pwmFreq > 3 || pwmReg < 1 || pwmReg > 15 || pwmLim > 15) return false;
    firstUse(REG_PWMCONF, PWMCONF_RESET);
    uint32_t mask = (0xFUL << 28) | (0xFUL << 24) | (1UL << 19) | (1UL << 18) | (3UL << 16) | (0xFFUL << 8) | 0xFFUL;
    uint32_t v = ((uint32_t)pwmLim << 28) | ((uint32_t)pwmReg << 24) | bitIf(autograd, 1UL << 19) |
                 bitIf(autoscale, 1UL << 18) | ((uint32_t)pwmFreq << 16) | ((uint32_t)pwmGrad << 8) | pwmOfs;
    return modify(REG_PWMCONF, mask, v);
}

bool TMC5160Full::setFreewheel(uint8_t mode) {
    if (mode > 3) return false;
    firstUse(REG_PWMCONF, PWMCONF_RESET);
    return modify(REG_PWMCONF, 3UL << 20, (uint32_t)mode << 20);
}

bool TMC5160Full::setShortProtection(bool shortGnd, bool shortSupply) {
    return modify(REG_CHOPCONF, (1UL << 30) | (1UL << 31), bitIf(!shortGnd, 1UL << 30) | bitIf(!shortSupply, 1UL << 31));
}

bool TMC5160Full::setDoubleEdge(bool enabled) { return modify(REG_CHOPCONF, 1UL << 29, bitIf(enabled, 1UL << 29)); }

bool TMC5160Full::gconfBit(uint32_t mask, bool on) { return modify(REG_GCONF, mask, bitIf(on, mask)); }

bool TMC5160Full::setDirectionInverted(bool inverted) { return gconfBit(GCONF_SHAFT, inverted); }
bool TMC5160Full::setFastStandstill(bool enabled) { return gconfBit(1UL << 1, enabled); }
bool TMC5160Full::setRecalibrate(bool enabled) { return gconfBit(1UL << 0, enabled); }
bool TMC5160Full::setSmallHysteresis(bool enabled) { return gconfBit(GCONF_SMALL_HYSTERESIS, enabled); }
bool TMC5160Full::setEmergencyStop(bool enabled) { return gconfBit(GCONF_STOP_ENABLE, enabled); }

bool TMC5160Full::configureDiag(bool diag0StepOutput, bool diag1DirOutput, bool diag0Pushpull, bool diag1Pushpull) {
    uint32_t v = bitIf(diag0StepOutput, 1UL << 7) | bitIf(diag1DirOutput, 1UL << 8) |
                 bitIf(diag0Pushpull, 1UL << 12) | bitIf(diag1Pushpull, 1UL << 13);
    return modify(REG_GCONF, (1UL << 7) | (1UL << 8) | (1UL << 12) | (1UL << 13), v);
}

bool TMC5160Full::setStallThreshold(int8_t sgt) {
    if (sgt < -64 || sgt > 63) return false;
    return modify(REG_COOLCONF, 0x7FUL << 16, ((uint32_t)(uint8_t)sgt & 0x7F) << 16);
}

bool TMC5160Full::setStallguardFilter(bool enabled) { return modify(REG_COOLCONF, 1UL << 24, bitIf(enabled, 1UL << 24)); }

bool TMC5160Full::configureCoolstep(uint8_t semin, uint8_t semax, uint8_t seup, uint8_t sedn, uint8_t seimin) {
    if (semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1) return false;
    uint32_t mask = 0xFUL | (3UL << 5) | (0xFUL << 8) | (3UL << 13) | (1UL << 15);
    uint32_t v = (uint32_t)semin | ((uint32_t)seup << 5) | ((uint32_t)semax << 8) | ((uint32_t)sedn << 13) |
                 ((uint32_t)seimin << 15);
    return modify(REG_COOLCONF, mask, v);
}

bool TMC5160Full::enableStallStop(bool enabled) {
    if (enabled && (_shadow[REG_SW_MODE] & SW_SOFTSTOP)) return false;
    return modify(REG_SW_MODE, SW_SG_STOP, bitIf(enabled, SW_SG_STOP));
}

uint16_t TMC5160Full::readStallguard() { return (uint16_t)(readReg(REG_DRV_STATUS) & 0x3FF); }

bool TMC5160Full::isStalled() { return ((readReg(REG_DRV_STATUS) >> 24) & 1) != 0; }

bool TMC5160Full::readDiag(uint8_t n) {
    InputPin* p = n == 0 ? _diag0 : (n == 1 ? _diag1 : nullptr);
    if (!p) { _ok = false; return false; }
    _ok = true;
    return p->isHigh();
}

bool TMC5160Full::setDcstep(uint32_t vdcmin, uint16_t dcTime, uint8_t dcSg) {
    if (vdcmin > 0x7FFFFFUL || dcTime > 1023) return false;
    return writeReg(REG_VDCMIN, vdcmin) && writeReg(REG_DCCTRL, (uint32_t)dcTime | ((uint32_t)dcSg << 16));
}

bool TMC5160Full::setDirectMode(bool enabled) {
    if (!gconfBit(GCONF_DIRECT_MODE, enabled)) return false;
    if (!enabled) return writeReg(REG_XTARGET, _shadow[REG_XTARGET]);
    return true;
}

bool TMC5160Full::setCoilCurrents(int16_t coilA, int16_t coilB) {
    if (!(_shadow[REG_GCONF] & GCONF_DIRECT_MODE)) return false;
    if (coilA < -255 || coilA > 255 || coilB < -255 || coilB > 255) return false;
    return writeReg(REG_XTARGET, ((uint32_t)(int32_t)coilA & 0x1FF) | (((uint32_t)(int32_t)coilB & 0x1FF) << 16));
}

bool TMC5160Full::writeMicrostepTable(const uint32_t mslut[8], uint32_t mslutsel, uint32_t mslutstart) {
    for (uint8_t i = 0; i < 8; i++) {
        if (!writeReg((uint8_t)(REG_MSLUT0 + i), mslut[i])) return false;
    }
    return writeReg(REG_MSLUTSEL, mslutsel) && writeReg(REG_MSLUTSTART, mslutstart);
}

bool TMC5160Full::configureGateDriver(uint8_t bbmtime, uint8_t bbmclks, uint8_t otselect, uint8_t drvstrength,
                                      uint8_t filtIsense) {
    if (bbmtime > 24 || bbmclks > 15 || otselect > 3 || drvstrength > 3 || filtIsense > 3) return false;
    return writeReg(REG_DRV_CONF, (uint32_t)bbmtime | ((uint32_t)bbmclks << 8) | ((uint32_t)otselect << 16) |
                                  ((uint32_t)drvstrength << 18) | ((uint32_t)filtIsense << 20));
}

bool TMC5160Full::configureShortDetection(uint8_t s2vsLevel, uint8_t s2gLevel, uint8_t shortfilter,
                                          uint8_t shortdelay) {
    if (s2vsLevel < 4 || s2vsLevel > 15 || s2gLevel < 2 || s2gLevel > 15 || shortfilter > 3 || shortdelay > 1)
        return false;
    return writeReg(REG_SHORT_CONF, (uint32_t)s2vsLevel | ((uint32_t)s2gLevel << 8) | ((uint32_t)shortfilter << 16) |
                                    ((uint32_t)shortdelay << 18));
}

bool TMC5160Full::configureEncoder(const bool flags[11]) {
    uint32_t v = 0;
    for (int i = 0; i < 11; i++) v |= bitIf(flags[i], 1UL << i);
    return writeReg(REG_ENCMODE, v);
}

bool TMC5160Full::setEncoderConstant(double value) {
    double a = value < 0 ? -value : value;
    if (a > 32767.9999) return false;
    uint32_t raw;
    if (_shadow[REG_ENCMODE] & (1UL << 10)) {
        uint32_t whole = (uint32_t)a;
        raw = (whole << 16) | (uint32_t)rnd((a - (double)whole) * 10000.0);
        if (value < 0) raw = (uint32_t)(0u - raw);
    } else {
        raw = (uint32_t)(int32_t)rnd(value * 65536.0);
    }
    return writeReg(REG_ENC_CONST, raw);
}

bool TMC5160Full::setEncoderPosition(int32_t counts) { return writeReg(REG_X_ENC, (uint32_t)counts); }
int32_t TMC5160Full::readEncoderPosition() { return (int32_t)readReg(REG_X_ENC); }
int32_t TMC5160Full::readEncoderLatch() { return (int32_t)readReg(REG_ENC_LATCH); }
bool TMC5160Full::setEncoderDeviation(uint32_t steps) { return threshold(REG_ENC_DEVIATION, steps); }

void TMC5160Full::readEncoderStatus(bool& nEvent, bool& deviationWarn) {
    uint32_t s = readReg(REG_ENC_STATUS);
    nEvent = (s & 1) != 0;
    deviationWarn = (s & 2) != 0;
}

bool TMC5160Full::clearEncoderStatus() { return writeReg(REG_ENC_STATUS, 3); }

bool TMC5160Full::setSlaveAddress(uint8_t slaveaddr) {
    if (!_uart || slaveaddr > 253) return false;
    if (!writeReg(REG_SLAVECONF, (uint32_t)slaveaddr | ((uint32_t)_sendDelay << 8))) return false;
    _address = slaveaddr;
    return true;
}

bool TMC5160Full::setSendDelay(uint8_t sendDelay) {
    if (!_uart || sendDelay > 15) return false;
    _sendDelay = sendDelay;
    return writeReg(REG_SLAVECONF, (_shadow[REG_SLAVECONF] & 0xFF) | ((uint32_t)sendDelay << 8));
}

bool TMC5160Full::setNaoPolarity(bool level) {
    if (!_uart) return false;
    return writeReg(REG_OUTPUT, level ? 1 : 0);
}

uint8_t TMC5160Full::readIfcnt() {
    if (!_uart) { _ok = false; return 0; }
    return (uint8_t)readReg(REG_IFCNT);
}

uint16_t TMC5160Full::microstepPosition() { return (uint16_t)(readReg(REG_MSCNT) & 0x3FF); }

uint8_t TMC5160Full::readCurrentScale() { return (uint8_t)((readReg(REG_DRV_STATUS) >> 16) & 0x1F); }

TMC5160Full::CoilCurrents TMC5160Full::readCoilCurrents() {
    uint32_t raw = readReg(REG_MSCURACT);
    CoilCurrents c;
    c.curA = (int16_t)signExtend(raw >> 16, 9);
    c.curB = (int16_t)signExtend(raw, 9);
    return c;
}

uint32_t TMC5160Full::readTstep() { return readReg(REG_TSTEP) & 0xFFFFFUL; }

void TMC5160Full::readPwmScale(uint8_t& sum, int16_t& autoScale) {
    uint32_t raw = readReg(REG_PWM_SCALE);
    sum = (uint8_t)(raw & 0xFF);
    autoScale = (int16_t)signExtend(raw >> 16, 9);
}

void TMC5160Full::readPwmAuto(uint8_t& ofs, uint8_t& grad) {
    uint32_t raw = readReg(REG_PWM_AUTO);
    ofs = (uint8_t)(raw & 0xFF);
    grad = (uint8_t)((raw >> 16) & 0xFF);
}

void TMC5160Full::readOffset(int8_t& phaseA, int8_t& phaseB) {
    uint32_t raw = readReg(REG_OFFSET_READ);
    phaseA = (int8_t)((raw >> 8) & 0xFF);
    phaseB = (int8_t)(raw & 0xFF);
}

TMC5160Full::Status TMC5160Full::readStatus() {
    uint32_t s = readReg(REG_DRV_STATUS);
    Status r;
    r.stst = (s >> 31) != 0;
    r.stealth = s & (1UL << 14);
    r.fsactive = s & (1UL << 15);
    r.csActual = (uint8_t)((s >> 16) & 0x1F);
    r.sgResult = (uint16_t)(s & 0x3FF);
    r.stallguard = s & (1UL << 24);
    r.otpw = s & (1UL << 26);
    r.ot = s & (1UL << 25);
    r.shortGnd = s & (3UL << 27);
    r.shortSupply = s & (3UL << 12);
    r.openLoad = s & (3UL << 29);
    return r;
}

TMC5160Full::GlobalStatus TMC5160Full::readGlobalStatus() {
    uint32_t g = readReg(REG_GSTAT);
    GlobalStatus r;
    r.reset = g & 1;
    r.drvErr = g & 2;
    r.uvCp = g & 4;
    return r;
}

bool TMC5160Full::clearGlobalStatus() { return writeReg(REG_GSTAT, 7); }

TMC5160Full::Ioin TMC5160Full::readIoin() {
    uint32_t r = readReg(REG_IOIN);
    Ioin i;
    i.raw = r;
    i.refl = r & 1; i.refr = r & 2; i.encb = r & 4; i.enca = r & 8; i.drvEnn = r & 16; i.encn = r & 32;
    i.sdMode = r & 64; i.swcompIn = r & 128;
    i.version = (uint8_t)(r >> 24);
    return i;
}
