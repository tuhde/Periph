#include "TMC2130.h"
#include <math.h>
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
static void periph_busy_us(unsigned us) { usleep(us); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
static void periph_busy_us(unsigned us) { k_busy_wait(us); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include "esp_rom_sys.h"
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
static void periph_busy_us(unsigned us) { esp_rom_delay_us(us); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
static void periph_busy_us(unsigned) { HAL_Delay(1); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
static void periph_busy_us(unsigned us) { sleep_us(us); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
static void periph_busy_us(unsigned us) { delayMicroseconds(us); }
#endif

constexpr uint8_t TMC2130Minimal::FAULT_OVERTEMP_WARNING;
constexpr uint8_t TMC2130Minimal::FAULT_OVERTEMP;
constexpr uint8_t TMC2130Minimal::FAULT_SHORT_GND;
constexpr uint8_t TMC2130Minimal::FAULT_OPEN_LOAD;

static const uint32_t GCONF_I_SCALE_ANALOG = 1UL << 0;
static const uint32_t GCONF_INTERNAL_RSENSE = 1UL << 1;
static const uint32_t GCONF_EN_PWM_MODE = 1UL << 2;
static const uint32_t GCONF_SHAFT = 1UL << 4;
static const uint32_t GCONF_SMALL_HYSTERESIS = 1UL << 14;
static const uint32_t GCONF_STOP_ENABLE = 1UL << 15;
static const uint32_t GCONF_DIRECT_MODE = 1UL << 16;
static const uint8_t TMC2130_VERSION = 0x11;
static const uint32_t CHOPCONF_DEFAULT = 0x140100C3UL;
static const uint32_t PWMCONF_DEFAULT = 0x000401C8UL;

static int32_t signExtend(uint32_t v, int bits) {
    v &= (1UL << bits) - 1;
    if (v & (1UL << (bits - 1))) return (int32_t)v - (int32_t)(1UL << bits);
    return (int32_t)v;
}

static uint32_t bitIf(bool b, uint32_t bits) { return b ? bits : 0; }

TMC2130Minimal::TMC2130Minimal(Connection& connection, OutputPin* stepPin, OutputPin* dirPin,
                               float rSenseOhm, OutputPin* enPin)
    : _conn(connection), _stepPin(stepPin), _dirPin(dirPin), _enPin(enPin), _rSense(rSenseOhm),
      _ok(false), _internalRsense(false), _vsense(false), _autoVsense(true), _microsteps(16),
      _toff(3), _runCs(0), _holdCs(0), _iholdDelay(6),
      _gconf(0), _chopconf(0), _pwmconf(0), _coolconf(0) {
    initialise();
}

void TMC2130Minimal::delayMs(unsigned ms) { periph_delay_ms(ms); }
void TMC2130Minimal::delayUs(unsigned us) {
    if (us >= 1000) periph_delay_ms(us / 1000);
    else if (us) periph_busy_us(us);
}

bool TMC2130Minimal::writeReg(uint8_t reg, uint32_t value, bool verify) {
    uint8_t frame[5] = {(uint8_t)(reg | 0x80), (uint8_t)(value >> 24), (uint8_t)(value >> 16),
                        (uint8_t)(value >> 8), (uint8_t)value};
    _conn.write(frame, 5);
    switch (reg) {
        case REG_GCONF: _gconf = value; break;
        case REG_CHOPCONF: _chopconf = value; break;
        case REG_PWMCONF: _pwmconf = value; break;
        case REG_COOLCONF: _coolconf = value; break;
        default: break;
    }
    if (verify) {
        uint32_t mirror = secondDatagram();
        if (mirror != value) { _ok = false; return false; }
    }
    return true;
}

// A harmless GCONF read request whose reply answers the previous datagram
// (pipelined protocol). Never GSTAT, which clears on read.
uint32_t TMC2130Minimal::secondDatagram() {
    uint8_t req[1] = {REG_GCONF};
    uint8_t r[4] = {0};
    _conn.write_read(req, 1, r, 4);
    return ((uint32_t)r[0] << 24) | ((uint32_t)r[1] << 16) | ((uint32_t)r[2] << 8) | r[3];
}

uint32_t TMC2130Minimal::readReg(uint8_t reg) {
    uint8_t req[1] = {(uint8_t)(reg & 0x7F)};
    uint8_t ignored[4] = {0};
    _conn.write_read(req, 1, ignored, 4);
    return secondDatagram();
}

uint32_t TMC2130Minimal::shadow(uint8_t reg) const {
    switch (reg) {
        case REG_GCONF: return _gconf;
        case REG_CHOPCONF: return _chopconf;
        case REG_COOLCONF: return _coolconf;
        default: return _pwmconf;
    }
}

bool TMC2130Minimal::modify(uint8_t reg, uint32_t mask, uint32_t value) {
    return writeReg(reg, (shadow(reg) & ~mask) | (value & mask));
}

static uint8_t csFor(float rmsA, float rSense, bool vsense) {
    float vfs = vsense ? 0.180f : 0.325f;
    long v = lroundf(rmsA * sqrtf(2.0f) * (rSense + 0.020f) * 32.0f / vfs) - 1;
    if (v < 0) v = 0;
    if (v > 31) v = 31;
    return (uint8_t)v;
}

// forcedVsense: -1 = automatic (unless disabled by setVsense), 0 / 1 = use that range.
bool TMC2130Minimal::currentToCs(float rmsA, int8_t forcedVsense, uint8_t& cs, bool& vsense) const {
    if (_internalRsense) return false;
    if (forcedVsense >= 0) {
        vsense = forcedVsense != 0;
        cs = csFor(rmsA, _rSense, vsense);
        return true;
    }
    if (!_autoVsense) {
        vsense = _vsense;
        cs = csFor(rmsA, _rSense, vsense);
        return true;
    }
    cs = csFor(rmsA, _rSense, false);
    vsense = false;
    if (cs < 16) {
        uint8_t high = csFor(rmsA, _rSense, true);
        if (high <= 31) { cs = high; vsense = true; }
    }
    return true;
}

bool TMC2130Minimal::writeIholdIrun(bool verify) {
    return writeReg(REG_IHOLD_IRUN, (uint32_t)(_holdCs & 0x1F) | ((uint32_t)(_runCs & 0x1F) << 8) |
                                    ((uint32_t)(_iholdDelay & 0xF) << 16), verify);
}

void TMC2130Minimal::initialise() {
    if (_enPin) _enPin->set(true);
    delayMs(10);
    _ok = true;
    uint32_t ioin = readReg(REG_IOIN);
    if ((uint8_t)(ioin >> 24) != TMC2130_VERSION || !(ioin & (1UL << 6))) { _ok = false; return; }
    readReg(REG_GSTAT);
    uint8_t cs = 0;
    bool vs = false;
    currentToCs(0.5f, -1, cs, vs);
    _runCs = cs;
    _holdCs = cs / 3;
    _vsense = vs;
    if (!writeReg(REG_CHOPCONF, CHOPCONF_DEFAULT | bitIf(vs, 1UL << 17), true)) return;
    if (!writeIholdIrun(true)) return;
    if (!writeReg(REG_TPOWERDOWN, 10, true)) return;
    if (!writeReg(REG_GCONF, GCONF_EN_PWM_MODE, true)) return;
    if (!writeReg(REG_TPWMTHRS, 500, true)) return;
    if (!writeReg(REG_PWMCONF, PWMCONF_DEFAULT, true)) return;
    if (readReg(REG_GCONF) != _gconf || readReg(REG_CHOPCONF) != _chopconf) _ok = false;
}

void TMC2130Minimal::enable() {
    if (_enPin) _enPin->set(false);
    else writeReg(REG_CHOPCONF, (_chopconf & ~0xFUL) | _toff);
    delayMs(100);
}

void TMC2130Minimal::disable() {
    if (_enPin) { _enPin->set(true); return; }
    if (_chopconf & 0xF) _toff = (uint8_t)(_chopconf & 0xF);
    writeReg(REG_CHOPCONF, _chopconf & ~0xFUL);
}

bool TMC2130Minimal::setCurrent(float rmsA) {
    if (rmsA > 1.4f) return false;
    uint8_t cs;
    bool vs;
    if (!currentToCs(rmsA, -1, cs, vs)) return false;
    _runCs = cs;
    _holdCs = cs / 3;
    if (vs != _vsense) {
        _vsense = vs;
        modify(REG_CHOPCONF, 1UL << 17, bitIf(vs, 1UL << 17));
    }
    return writeIholdIrun();
}

bool TMC2130Minimal::move(int32_t fullSteps, float fullStepsPerS) {
    if (fullStepsPerS <= 0.0f || !_stepPin || !_dirPin) return false;
    _dirPin->set(fullSteps >= 0);
    int32_t n = fullSteps < 0 ? -fullSteps : fullSteps;
    uint32_t pulses = (uint32_t)n * _microsteps;
    unsigned halfUs = (unsigned)(1.0e6f / (2.0f * fullStepsPerS * _microsteps));
    for (uint32_t i = 0; i < pulses; i++) {
        _stepPin->set(true);
        delayUs(halfUs);
        _stepPin->set(false);
        delayUs(halfUs);
    }
    return true;
}

bool TMC2130Minimal::isStandstill() { return (readReg(REG_DRV_STATUS) & (1UL << 31)) != 0; }

uint8_t TMC2130Minimal::readFault() {
    uint32_t s = readReg(REG_DRV_STATUS);
    uint8_t f = 0;
    if (s & (1UL << 26)) f |= FAULT_OVERTEMP_WARNING;
    if (s & (1UL << 25)) f |= FAULT_OVERTEMP;
    if (s & (3UL << 27)) f |= FAULT_SHORT_GND;
    if (s & (3UL << 29)) f |= FAULT_OPEN_LOAD;
    return f;
}

// ---- TMC2130Full ----

TMC2130Full::TMC2130Full(Connection& connection, OutputPin* stepPin, OutputPin* dirPin, float rSenseOhm,
                         OutputPin* enPin, InputPin* diag0Pin, InputPin* diag1Pin)
    : TMC2130Minimal(connection, stepPin, dirPin, rSenseOhm, enPin), _diag0(diag0Pin), _diag1(diag1Pin) {}

static bool isReadable(uint8_t r) {
    switch (r) {
        case 0x00: case 0x01: case 0x04: case 0x12: case 0x2D: case 0x6A: case 0x6B: case 0x6C:
        case 0x6F: case 0x71: case 0x73:
            return true;
        default: return false;
    }
}

static bool isWritable(uint8_t r) {
    switch (r) {
        case 0x00: case 0x10: case 0x11: case 0x13: case 0x14: case 0x15: case 0x2D: case 0x33:
        case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
        case 0x68: case 0x69: case 0x6C: case 0x6D: case 0x6E: case 0x70:
            return true;
        default: return false;
    }
}

uint32_t TMC2130Full::readRegister(uint8_t reg) {
    if (!isReadable(reg)) { _ok = false; return 0; }
    _ok = true;
    return readReg(reg);
}

bool TMC2130Full::writeRegister(uint8_t reg, uint32_t value, bool verify) {
    if (!isWritable(reg)) return false;
    return writeReg(reg, value, verify);
}

bool TMC2130Full::setHoldCurrent(float rmsA) {
    uint8_t hold;
    bool vs;
    if (!currentToCs(rmsA, _vsense ? 1 : 0, hold, vs)) return false;
    _holdCs = hold;
    return writeIholdIrun();
}

bool TMC2130Full::setCurrentDelay(uint8_t iholdDelay, uint8_t tpowerdown) {
    if (iholdDelay > 15) return false;
    _iholdDelay = iholdDelay;
    writeIholdIrun();
    return writeReg(REG_TPOWERDOWN, tpowerdown);
}

bool TMC2130Full::setMicrosteps(uint16_t n) {
    if (n == 0 || n > 256 || (n & (n - 1)) != 0) return false;
    uint8_t mres = 8;
    for (uint16_t v = n; v > 1; v >>= 1) mres--;
    modify(REG_CHOPCONF, 0xFUL << 24, (uint32_t)mres << 24);
    _microsteps = n;
    return true;
}

void TMC2130Full::setInterpolation(bool enabled) { modify(REG_CHOPCONF, 1UL << 28, bitIf(enabled, 1UL << 28)); }

void TMC2130Full::setChopperMode(bool spreadcycle) {
    modify(REG_GCONF, GCONF_EN_PWM_MODE, spreadcycle ? 0 : GCONF_EN_PWM_MODE);
}

bool TMC2130Full::setStealthchopThreshold(uint32_t tpwmthrs) {
    if (tpwmthrs > 0xFFFFFUL) return false;
    return writeReg(REG_TPWMTHRS, tpwmthrs);
}

bool TMC2130Full::setHighVelocityThreshold(uint32_t thigh) {
    if (thigh > 0xFFFFFUL) return false;
    return writeReg(REG_THIGH, thigh);
}

bool TMC2130Full::configureChopper(uint8_t toff, uint8_t tbl, uint8_t hstrt, uint8_t hend) {
    if (toff > 15 || tbl > 3 || hstrt > 7 || hend > 15) return false;
    if (toff) _toff = toff;
    uint32_t mask = 0xFUL | (3UL << 15) | (7UL << 4) | (0xFUL << 7) | (1UL << 14) | (1UL << 11);
    return modify(REG_CHOPCONF, mask, toff | ((uint32_t)tbl << 15) | ((uint32_t)hstrt << 4) | ((uint32_t)hend << 7));
}

bool TMC2130Full::configureConstantOffTime(uint8_t toff, uint8_t tbl, uint8_t tfd, int8_t offset, bool disfdcc, bool rndtf) {
    if (toff > 15 || tbl > 3 || tfd > 15 || offset < -3 || offset > 12) return false;
    if (toff) _toff = toff;
    uint32_t mask = 0xFUL | (3UL << 15) | (7UL << 4) | (0xFUL << 7) | (1UL << 14) | (1UL << 13) | (1UL << 12) | (1UL << 11);
    uint32_t v = toff | ((uint32_t)tbl << 15) | ((uint32_t)(tfd & 7) << 4) | ((uint32_t)(offset + 3) << 7) |
                 (1UL << 14) | bitIf(rndtf, 1UL << 13) | bitIf(disfdcc, 1UL << 12) | ((uint32_t)((tfd >> 3) & 1) << 11);
    return modify(REG_CHOPCONF, mask, v);
}

void TMC2130Full::configureHighVelocity(bool vhighchm, bool vhighfs) {
    modify(REG_CHOPCONF, (1UL << 19) | (1UL << 18), bitIf(vhighchm, 1UL << 19) | bitIf(vhighfs, 1UL << 18));
}

bool TMC2130Full::setChopperSync(uint8_t sync) {
    if (sync > 15) return false;
    return modify(REG_CHOPCONF, 0xFUL << 20, (uint32_t)sync << 20);
}

bool TMC2130Full::configureStealthchop(bool autoscale, bool symmetric, uint8_t pwmAmpl, uint8_t pwmGrad, uint8_t pwmFreq) {
    if (pwmFreq > 3) return false;
    uint32_t mask = (1UL << 19) | (1UL << 18) | (3UL << 16) | (0xFFUL << 8) | 0xFFUL;
    uint32_t v = bitIf(symmetric, 1UL << 19) | bitIf(autoscale, 1UL << 18) | ((uint32_t)pwmFreq << 16) |
                 ((uint32_t)pwmGrad << 8) | pwmAmpl;
    return modify(REG_PWMCONF, mask, v);
}

bool TMC2130Full::setFreewheel(uint8_t mode) {
    if (mode > 3) return false;
    return modify(REG_PWMCONF, 3UL << 20, (uint32_t)mode << 20);
}

void TMC2130Full::setVsense(bool highSensitivity) {
    modify(REG_CHOPCONF, 1UL << 17, bitIf(highSensitivity, 1UL << 17));
    _vsense = highSensitivity;
    _autoVsense = false;
}

void TMC2130Full::setInternalRsense(bool enabled) {
    modify(REG_GCONF, GCONF_INTERNAL_RSENSE, bitIf(enabled, GCONF_INTERNAL_RSENSE));
    _internalRsense = enabled;
}

void TMC2130Full::setAnalogScaling(bool enabled) { modify(REG_GCONF, GCONF_I_SCALE_ANALOG, bitIf(enabled, GCONF_I_SCALE_ANALOG)); }
void TMC2130Full::setDirectionInverted(bool inverted) { modify(REG_GCONF, GCONF_SHAFT, bitIf(inverted, GCONF_SHAFT)); }

void TMC2130Full::configureDiag(bool diag0Error, bool diag0Otpw, bool diag0Stall, bool diag1Stall,
                                bool diag1Index, bool diag1Onstate, bool diag0Pushpull, bool diag1Pushpull) {
    uint32_t v = bitIf(diag0Error, 1UL << 5) | bitIf(diag0Otpw, 1UL << 6) | bitIf(diag0Stall, 1UL << 7) |
                 bitIf(diag1Stall, 1UL << 8) | bitIf(diag1Index, 1UL << 9) | bitIf(diag1Onstate, 1UL << 10) |
                 bitIf(diag0Pushpull, 1UL << 12) | bitIf(diag1Pushpull, 1UL << 13);
    modify(REG_GCONF, 0x3FE0UL & ~(1UL << 11), v);
}

void TMC2130Full::setDoubleEdge(bool enabled) { modify(REG_CHOPCONF, 1UL << 29, bitIf(enabled, 1UL << 29)); }
void TMC2130Full::setShortProtection(bool shortGnd) { modify(REG_CHOPCONF, 1UL << 30, bitIf(!shortGnd, 1UL << 30)); }
void TMC2130Full::setSmallHysteresis(bool enabled) { modify(REG_GCONF, GCONF_SMALL_HYSTERESIS, bitIf(enabled, GCONF_SMALL_HYSTERESIS)); }
void TMC2130Full::setEmergencyStop(bool enabled) { modify(REG_GCONF, GCONF_STOP_ENABLE, bitIf(enabled, GCONF_STOP_ENABLE)); }

bool TMC2130Full::setStallThreshold(int8_t sgt) {
    if (sgt < -64 || sgt > 63) return false;
    return modify(REG_COOLCONF, 0x7FUL << 16, ((uint32_t)sgt & 0x7F) << 16);
}

void TMC2130Full::setStallguardFilter(bool enabled) { modify(REG_COOLCONF, 1UL << 24, bitIf(enabled, 1UL << 24)); }

bool TMC2130Full::setCoolstepThreshold(uint32_t tcoolthrs) {
    if (tcoolthrs > 0xFFFFFUL) return false;
    return writeReg(REG_TCOOLTHRS, tcoolthrs);
}

bool TMC2130Full::configureCoolstep(uint8_t semin, uint8_t semax, uint8_t seup, uint8_t sedn, uint8_t seimin) {
    if (semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1) return false;
    uint32_t mask = 0xFUL | (3UL << 5) | (0xFUL << 8) | (3UL << 13) | (1UL << 15);
    return modify(REG_COOLCONF, mask, semin | ((uint32_t)seup << 5) | ((uint32_t)semax << 8) |
                                      ((uint32_t)sedn << 13) | ((uint32_t)seimin << 15));
}

uint16_t TMC2130Full::readStallguard() { return (uint16_t)(readReg(REG_DRV_STATUS) & 0x3FF); }
bool TMC2130Full::isStalled() { return (readReg(REG_DRV_STATUS) & (1UL << 24)) != 0; }

bool TMC2130Full::readDiag(uint8_t n) {
    InputPin* pin = n == 0 ? _diag0 : (n == 1 ? _diag1 : nullptr);
    if (!pin) { _ok = false; return false; }
    return pin->isHigh();
}

bool TMC2130Full::step(int32_t count, float delayS) {
    if (!_stepPin || !_dirPin) return false;
    _dirPin->set(count >= 0);
    unsigned us = (unsigned)(delayS * 1e6f);
    int32_t n = count < 0 ? -count : count;
    for (int32_t i = 0; i < n; i++) {
        _stepPin->set(true);
        delayUs(us);
        _stepPin->set(false);
        delayUs(us);
    }
    return true;
}

void TMC2130Full::setDirectMode(bool enabled) { modify(REG_GCONF, GCONF_DIRECT_MODE, bitIf(enabled, GCONF_DIRECT_MODE)); }

bool TMC2130Full::setCoilCurrents(int16_t coilA, int16_t coilB) {
    if (!(_gconf & GCONF_DIRECT_MODE)) return false;
    if (coilA < -255 || coilA > 255 || coilB < -255 || coilB > 255) return false;
    return writeReg(REG_XDIRECT, ((uint32_t)coilA & 0x1FF) | (((uint32_t)coilB & 0x1FF) << 16));
}

bool TMC2130Full::setDcstep(uint32_t vdcmin, uint16_t dcTime, uint8_t dcSg) {
    if (vdcmin > 0x7FFFFFUL || dcTime > 1023) return false;
    writeReg(REG_VDCMIN, vdcmin);
    return writeReg(REG_DCCTRL, dcTime | ((uint32_t)dcSg << 16));
}

void TMC2130Full::writeMicrostepTable(const uint32_t mslut[8], uint32_t mslutsel, uint32_t mslutstart) {
    for (uint8_t i = 0; i < 8; i++) writeReg(REG_MSLUT0 + i, mslut[i]);
    writeReg(REG_MSLUTSEL, mslutsel);
    writeReg(REG_MSLUTSTART, mslutstart);
}

uint16_t TMC2130Full::microstepPosition() { return (uint16_t)(readReg(REG_MSCNT) & 0x3FF); }
uint8_t TMC2130Full::readCurrentScale() { return (uint8_t)((readReg(REG_DRV_STATUS) >> 16) & 0x1F); }

TMC2130Full::CoilCurrents TMC2130Full::readCoilCurrents() {
    uint32_t raw = readReg(REG_MSCURACT);
    return { (int16_t)signExtend(raw >> 16, 9), (int16_t)signExtend(raw, 9) };
}

uint32_t TMC2130Full::readTstep() { return readReg(REG_TSTEP) & 0xFFFFFUL; }
uint8_t TMC2130Full::readPwmScale() { return (uint8_t)(readReg(REG_PWM_SCALE) & 0xFF); }
uint32_t TMC2130Full::readLostSteps() { return readReg(REG_LOST_STEPS) & 0xFFFFFUL; }

TMC2130Full::Status TMC2130Full::readStatus() {
    uint32_t s = readReg(REG_DRV_STATUS);
    return { (s & (1UL << 31)) != 0, (s & (1UL << 15)) != 0, (uint8_t)((s >> 16) & 0x1F), (uint16_t)(s & 0x3FF),
             (s & (1UL << 24)) != 0, (s & (1UL << 26)) != 0, (s & (1UL << 25)) != 0,
             (s & (3UL << 27)) != 0, (s & (3UL << 29)) != 0 };
}

TMC2130Full::GlobalStatus TMC2130Full::readGlobalStatus() {
    uint32_t g = readReg(REG_GSTAT);
    return { (g & 1) != 0, (g & 2) != 0, (g & 4) != 0 };
}

TMC2130Full::Ioin TMC2130Full::readIoin() {
    uint32_t raw = readReg(REG_IOIN);
    return { raw, (raw & 1) != 0, (raw & 2) != 0, (raw & 4) != 0, (raw & 8) != 0, (raw & 16) != 0,
             (raw & 32) != 0, (uint8_t)(raw >> 24) };
}
