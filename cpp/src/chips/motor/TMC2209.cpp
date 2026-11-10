#include "TMC2209.h"
#include <math.h>

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

constexpr uint8_t TMC2209Minimal::FAULT_OVERTEMP_WARNING;
constexpr uint8_t TMC2209Minimal::FAULT_OVERTEMP;
constexpr uint8_t TMC2209Minimal::FAULT_SHORT_GND;
constexpr uint8_t TMC2209Minimal::FAULT_SHORT_SUPPLY;
constexpr uint8_t TMC2209Minimal::FAULT_OPEN_LOAD;

static const uint32_t GCONF_I_SCALE_ANALOG = 1UL << 0;
static const uint32_t GCONF_INTERNAL_RSENSE = 1UL << 1;
static const uint32_t GCONF_EN_SPREADCYCLE = 1UL << 2;
static const uint32_t GCONF_SHAFT = 1UL << 3;
static const uint32_t GCONF_INDEX_OTPW = 1UL << 4;
static const uint32_t GCONF_INDEX_STEP = 1UL << 5;
static const uint32_t GCONF_PDN_DISABLE = 1UL << 6;
static const uint32_t GCONF_MSTEP_REG_SELECT = 1UL << 7;
static const uint32_t GCONF_MULTISTEP_FILT = 1UL << 8;
static const uint8_t TMC2209_VERSION = 0x21;
static const uint32_t PWMCONF_DEFAULT = 0xC10D0024UL;

static int32_t signExtend(uint32_t v, int bits) {
    v &= (1UL << bits) - 1;
    if (v & (1UL << (bits - 1))) return (int32_t)v - (int32_t)(1UL << bits);
    return (int32_t)v;
}

uint8_t TMC2209Minimal::crc8(const uint8_t* data, size_t len) {
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

TMC2209Minimal::TMC2209Minimal(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo)
    : TMC2209Minimal(connection, rSenseOhm, address, enPin, echo, 12000000UL, false) {}

TMC2209Minimal::TMC2209Minimal(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo,
                               uint32_t fclkHz, bool multiNode)
    : _conn(connection), _rSense(rSenseOhm), _address(address), _enPin(enPin), _echo(echo), _fclk(fclkHz),
      _multiNode(multiNode), _ok(false), _internalRsense(false), _vsense(false),
      _microsteps(16), _toff(3), _runCs(0), _holdCs(0), _iholdDelay(6), _txCount(0),
      _gconf(0), _chopconf(0), _pwmconf(0), _nodeconf(0) {
    if (address > 3) return;  // _ok stays false: node address must be 0-3
    initialise();
}

void TMC2209Minimal::recover() {
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

bool TMC2209Minimal::writeDatagram(uint8_t reg, uint32_t value) {
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

bool TMC2209Minimal::readDatagram(uint8_t reg, uint32_t& value) {
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
    value = ((uint32_t)reply[3] << 24) | ((uint32_t)reply[4] << 16) |
            ((uint32_t)reply[5] << 8) | reply[6];
    return true;
}

bool TMC2209Minimal::writeReg(uint8_t reg, uint32_t value) {
    bool done = writeDatagram(reg, value);
    if (!done) {
        recover();
        done = writeDatagram(reg, value);
    }
    if (!done) { _ok = false; return false; }
    _txCount++;
    switch (reg) {
        case REG_GCONF: _gconf = value; break;
        case REG_CHOPCONF: _chopconf = value; break;
        case REG_PWMCONF: _pwmconf = value; break;
        case REG_NODECONF: _nodeconf = value; break;
        default: break;
    }
    return true;
}

uint32_t TMC2209Minimal::readReg(uint8_t reg) {
    uint32_t value = 0;
    bool done = readDatagram(reg, value);
    if (!done) {
        recover();
        done = readDatagram(reg, value);
    }
    _ok = done;
    return done ? value : 0;
}

uint32_t TMC2209Minimal::shadow(uint8_t reg) const {
    switch (reg) {
        case REG_GCONF: return _gconf;
        case REG_CHOPCONF: return _chopconf;
        case REG_PWMCONF: return _pwmconf;
        default: return 0;
    }
}

bool TMC2209Minimal::modify(uint8_t reg, uint32_t mask, uint32_t value) {
    return writeReg(reg, (shadow(reg) & ~mask) | (value & mask));
}

bool TMC2209Minimal::currentToCs(float rmsA, uint8_t& cs) const {
    if (_internalRsense) return false;
    float vfs = _vsense ? 0.180f : 0.325f;
    long v = lroundf(rmsA * sqrtf(2.0f) * (_rSense + 0.020f) * 32.0f / vfs) - 1;
    if (v < 0) v = 0;
    if (v > 31) v = 31;
    cs = (uint8_t)v;
    return true;
}

void TMC2209Minimal::writeIholdIrun() {
    writeReg(REG_IHOLD_IRUN, (uint32_t)(_holdCs & 0x1F) | ((uint32_t)(_runCs & 0x1F) << 8) |
                             ((uint32_t)(_iholdDelay & 0xF) << 16));
}

void TMC2209Minimal::initialise() {
    if (_enPin) _enPin->set(true);
    periph_delay_ms(10);
    _ok = true;
    uint8_t baseline = (uint8_t)(readReg(REG_IFCNT) & 0xFF);
    if (!_ok) return;
    uint32_t ioin = readReg(REG_IOIN);
    if (!_ok) return;
    uint8_t version = (uint8_t)(ioin >> 24);
    if (version != TMC2209_VERSION) { _ok = false; return; }
    uint8_t writes = 6;
    if (_multiNode) {
        if (!writeReg(REG_NODECONF, 2UL << 8)) return;
        writes = 7;
    }
    if (!writeReg(REG_GCONF, GCONF_I_SCALE_ANALOG | GCONF_PDN_DISABLE |
                             GCONF_MSTEP_REG_SELECT | GCONF_MULTISTEP_FILT)) return;
    if (!writeReg(REG_GSTAT, 0x07)) return;
    currentToCs(0.5f, _runCs);
    currentToCs(0.5f / 3.0f, _holdCs);
    writeIholdIrun();
    writeReg(REG_TPOWERDOWN, 20);
    uint32_t chop = readReg(REG_CHOPCONF);
    if (!_ok) return;
    chop &= ~(0xFUL | (3UL << 15) | (7UL << 4) | (0xFUL << 7) | (0xFUL << 24) | (1UL << 17));
    chop |= 3UL | (2UL << 15) | (5UL << 4) | (4UL << 24) | (1UL << 28);
    writeReg(REG_CHOPCONF, chop);
    writeReg(REG_PWMCONF, PWMCONF_DEFAULT);
    uint8_t ifcnt = (uint8_t)(readReg(REG_IFCNT) & 0xFF);
    if (_ok && ifcnt != (uint8_t)(baseline + writes)) _ok = false;
}

void TMC2209Minimal::enable() {
    if (_enPin) _enPin->set(false);
    else writeReg(REG_CHOPCONF, (_chopconf & ~0xFUL) | _toff);
}

void TMC2209Minimal::disable() {
    if (_enPin) { _enPin->set(true); return; }
    if (_chopconf & 0xF) _toff = (uint8_t)(_chopconf & 0xF);
    writeReg(REG_CHOPCONF, _chopconf & ~0xFUL);
}

bool TMC2209Minimal::setCurrent(float rmsA) {
    uint8_t run, hold;
    if (!currentToCs(rmsA, run) || !currentToCs(rmsA / 3.0f, hold)) return false;
    _runCs = run;
    _holdCs = hold;
    writeIholdIrun();
    return true;
}

void TMC2209Minimal::run(float stepsPerS) {
    double v = (double)stepsPerS * _microsteps * 16777216.0 / (double)_fclk;
    long vact = lround(v);
    if (vact > 8388607L) vact = 8388607L;
    if (vact < -8388607L) vact = -8388607L;
    writeReg(REG_VACTUAL, (uint32_t)vact & 0xFFFFFFUL);
}

void TMC2209Minimal::stop() { writeReg(REG_VACTUAL, 0); }

bool TMC2209Minimal::isStandstill() { return (readReg(REG_DRV_STATUS) & (1UL << 31)) != 0; }

uint8_t TMC2209Minimal::readFault() {
    uint32_t s = readReg(REG_DRV_STATUS);
    uint8_t f = 0;
    if (s & 0x01) f |= FAULT_OVERTEMP_WARNING;
    if (s & 0x02) f |= FAULT_OVERTEMP;
    if (s & 0x0C) f |= FAULT_SHORT_GND;
    if (s & 0x30) f |= FAULT_SHORT_SUPPLY;
    if (s & 0xC0) f |= FAULT_OPEN_LOAD;
    return f;
}

// ---- TMC2209Full ----

TMC2209Full::TMC2209Full(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo,
                         OutputPin* stepPin, OutputPin* dirPin, InputPin* diagPin, bool multiNode,
                         uint32_t fclkHz)
    : TMC2209Minimal(connection, rSenseOhm, address, enPin, echo, fclkHz, multiNode),
      _stepPin(stepPin), _dirPin(dirPin), _diagPin(diagPin) {}

static bool isReadable(uint8_t r) {
    switch (r) {
        case 0x00: case 0x01: case 0x02: case 0x05: case 0x06: case 0x07: case 0x12: case 0x41:
        case 0x6A: case 0x6B: case 0x6C: case 0x6F: case 0x70: case 0x71: case 0x72:
            return true;
        default: return false;
    }
}

static bool isWritable(uint8_t r) {
    switch (r) {
        case 0x00: case 0x01: case 0x03: case 0x10: case 0x11: case 0x13: case 0x14: case 0x22:
        case 0x40: case 0x42: case 0x6C: case 0x70:
            return true;
        default: return false;
    }
}

uint32_t TMC2209Full::readRegister(uint8_t reg) {
    if (!isReadable(reg)) { _ok = false; return 0; }
    return readReg(reg);
}

bool TMC2209Full::writeRegister(uint8_t reg, uint32_t value) {
    if (!isWritable(reg)) return false;
    return writeReg(reg, value);
}

bool TMC2209Full::setHoldCurrent(float rmsA) {
    uint8_t hold;
    if (!currentToCs(rmsA, hold)) return false;
    _holdCs = hold;
    writeIholdIrun();
    return true;
}

bool TMC2209Full::setCurrentDelay(uint8_t iholdDelay, uint8_t tpowerdown) {
    if (iholdDelay > 15) return false;
    _iholdDelay = iholdDelay;
    writeIholdIrun();
    return writeReg(REG_TPOWERDOWN, tpowerdown);
}

bool TMC2209Full::setMicrosteps(uint16_t n) {
    uint8_t mres = 0;
    switch (n) {
        case 256: mres = 0; break; case 128: mres = 1; break; case 64: mres = 2; break;
        case 32: mres = 3; break; case 16: mres = 4; break; case 8: mres = 5; break;
        case 4: mres = 6; break; case 2: mres = 7; break; case 1: mres = 8; break;
        default: return false;
    }
    if (!modify(REG_CHOPCONF, 0xFUL << 24, (uint32_t)mres << 24)) return false;
    _microsteps = n;
    return true;
}

void TMC2209Full::setInterpolation(bool enabled) {
    modify(REG_CHOPCONF, 1UL << 28, enabled ? (1UL << 28) : 0);
}

void TMC2209Full::setChopperMode(bool spreadcycle) {
    modify(REG_GCONF, GCONF_EN_SPREADCYCLE, spreadcycle ? GCONF_EN_SPREADCYCLE : 0);
}

void TMC2209Full::setStealthchopThreshold(uint32_t tpwmthrs) {
    writeReg(REG_TPWMTHRS, tpwmthrs & 0xFFFFFUL);
}

void TMC2209Full::configureChopper(uint8_t toff, uint8_t tbl, uint8_t hstrt, uint8_t hend) {
    uint32_t mask = 0xFUL | (3UL << 15) | (7UL << 4) | (0xFUL << 7);
    uint32_t value = (toff & 0xFUL) | ((uint32_t)(tbl & 3) << 15) | ((uint32_t)(hstrt & 7) << 4) |
                     ((uint32_t)(hend & 0xF) << 7);
    if (toff) _toff = toff & 0xF;
    modify(REG_CHOPCONF, mask, value);
}

void TMC2209Full::configureStealthchop(bool autoscale, bool autograd, uint8_t pwmOfs,
                                       uint8_t pwmGrad, uint8_t pwmFreq, uint8_t pwmReg,
                                       uint8_t pwmLim) {
    uint32_t mask = (0xFUL << 28) | (0xFUL << 24) | (1UL << 19) | (1UL << 18) | (3UL << 16) |
                    (0xFFUL << 8) | 0xFFUL;
    uint32_t value = ((uint32_t)(pwmLim & 0xF) << 28) | ((uint32_t)(pwmReg & 0xF) << 24) |
                     (autograd ? (1UL << 19) : 0) | (autoscale ? (1UL << 18) : 0) |
                     ((uint32_t)(pwmFreq & 3) << 16) | ((uint32_t)pwmGrad << 8) | pwmOfs;
    modify(REG_PWMCONF, mask, value);
}

bool TMC2209Full::setFreewheel(uint8_t mode) {
    if (mode > 3) return false;
    return modify(REG_PWMCONF, 3UL << 20, (uint32_t)mode << 20);
}

void TMC2209Full::setVsense(bool highSensitivity) {
    modify(REG_CHOPCONF, 1UL << 17, highSensitivity ? (1UL << 17) : 0);
    _vsense = highSensitivity;
}

void TMC2209Full::setInternalRsense(bool enabled) {
    modify(REG_GCONF, GCONF_INTERNAL_RSENSE, enabled ? GCONF_INTERNAL_RSENSE : 0);
    _internalRsense = enabled;
}

void TMC2209Full::setAnalogScaling(bool enabled) {
    modify(REG_GCONF, GCONF_I_SCALE_ANALOG, enabled ? GCONF_I_SCALE_ANALOG : 0);
}

void TMC2209Full::setDirectionInverted(bool inverted) {
    modify(REG_GCONF, GCONF_SHAFT, inverted ? GCONF_SHAFT : 0);
}

bool TMC2209Full::setIndexOutput(uint8_t mode) {
    if (mode > 2) return false;
    uint32_t bits = mode == 1 ? GCONF_INDEX_OTPW : (mode == 2 ? GCONF_INDEX_STEP : 0);
    return modify(REG_GCONF, GCONF_INDEX_OTPW | GCONF_INDEX_STEP, bits);
}

void TMC2209Full::setDriverProtection(bool shortLs, bool shortGnd) {
    uint32_t value = (shortLs ? 0 : (1UL << 31)) | (shortGnd ? 0 : (1UL << 30));
    modify(REG_CHOPCONF, (1UL << 31) | (1UL << 30), value);
}

void TMC2209Full::setDoubleEdge(bool enabled) {
    modify(REG_CHOPCONF, 1UL << 29, enabled ? (1UL << 29) : 0);
}

bool TMC2209Full::setSendDelay(uint8_t n) {
    if (n > 15) return false;
    return writeReg(REG_NODECONF, (uint32_t)n << 8);
}

void TMC2209Full::setStallThreshold(uint8_t sgthrs) { writeReg(REG_SGTHRS, sgthrs); }

bool TMC2209Full::setCoolstepThreshold(uint32_t tcoolthrs) {
    if (tcoolthrs > 0xFFFFFUL) return false;
    return writeReg(REG_TCOOLTHRS, tcoolthrs);
}

bool TMC2209Full::configureCoolstep(uint8_t semin, uint8_t semax, uint8_t seup, uint8_t sedn, uint8_t seimin) {
    if (semin > 15 || semax > 15 || seup > 3 || sedn > 3 || seimin > 1) return false;
    return writeReg(REG_COOLCONF, (uint32_t)semin | ((uint32_t)seup << 5) | ((uint32_t)semax << 8) |
                                  ((uint32_t)sedn << 13) | ((uint32_t)seimin << 15));
}

uint16_t TMC2209Full::readStallguard() { return (uint16_t)(readReg(REG_SG_RESULT) & 0x3FF); }

bool TMC2209Full::isStalled() {
    if (!_diagPin) { _ok = false; return false; }
    return _diagPin->isHigh();
}

bool TMC2209Full::step(int32_t count, float delayS) {
    if (!_stepPin) return false;
    if (count < 0 && !_dirPin) return false;
    if (_dirPin) _dirPin->set(count >= 0);
    unsigned us = (unsigned)(delayS * 1e6f);
    int32_t n = count < 0 ? -count : count;
    for (int32_t i = 0; i < n; i++) {
        _stepPin->set(true);
        if (us >= 1000) periph_delay_ms(us / 1000); else if (us) periph_busy_us(us);
        _stepPin->set(false);
        if (us >= 1000) periph_delay_ms(us / 1000); else if (us) periph_busy_us(us);
    }
    return true;
}

uint16_t TMC2209Full::microstepPosition() { return (uint16_t)(readReg(REG_MSCNT) & 0x3FF); }

uint8_t TMC2209Full::readCurrentScale() { return (uint8_t)((readReg(REG_DRV_STATUS) >> 16) & 0x1F); }

TMC2209Full::CoilCurrents TMC2209Full::readCoilCurrents() {
    uint32_t raw = readReg(REG_MSCURACT);
    return { (int16_t)signExtend(raw, 9), (int16_t)signExtend(raw >> 16, 9) };
}

uint32_t TMC2209Full::readTstep() { return readReg(REG_TSTEP) & 0xFFFFFUL; }

uint8_t TMC2209Full::readTemperatureFlags() { return (uint8_t)((readReg(REG_DRV_STATUS) >> 8) & 0xF); }

TMC2209Full::Status TMC2209Full::readStatus() {
    uint32_t s = readReg(REG_DRV_STATUS);
    Status st;
    st.stst = (s >> 31) & 1;
    st.stealth = (s >> 30) & 1;
    st.csActual = (uint8_t)((s >> 16) & 0x1F);
    st.otpw = s & 1;
    st.ot = (s >> 1) & 1;
    st.shortGnd = (s & 0x0C) != 0;
    st.shortSupply = (s & 0x30) != 0;
    st.openLoad = (s & 0xC0) != 0;
    st.t120 = (s >> 8) & 1;
    st.t143 = (s >> 9) & 1;
    st.t150 = (s >> 10) & 1;
    st.t157 = (s >> 11) & 1;
    return st;
}

TMC2209Full::GlobalStatus TMC2209Full::readGlobalStatus(bool clear) {
    uint32_t g = readReg(REG_GSTAT);
    if (clear) writeReg(REG_GSTAT, 0x07);
    return { (g & 1) != 0, (g & 2) != 0, (g & 4) != 0 };
}

uint8_t TMC2209Full::readIfcnt() { return (uint8_t)(readReg(REG_IFCNT) & 0xFF); }

TMC2209Full::Ioin TMC2209Full::readIoin() {
    uint32_t raw = readReg(REG_IOIN);
    return { raw, (raw & 1UL) != 0, (raw & 4UL) != 0, (raw & 8UL) != 0, (raw & 0x10UL) != 0,
             (raw & 0x40UL) != 0, (raw & 0x80UL) != 0, (raw & 0x100UL) != 0, (raw & 0x200UL) != 0,
             (uint8_t)(raw >> 24) };
}

uint32_t TMC2209Full::readOtp() { return readReg(REG_OTP_READ) & 0xFFFFFFUL; }

TMC2209Full::PwmAuto TMC2209Full::readPwmAuto() {
    uint32_t scale = readReg(REG_PWM_SCALE);
    uint32_t autoReg = readReg(REG_PWM_AUTO);
    return { (uint8_t)(scale & 0xFF), (int16_t)signExtend(scale >> 16, 9),
             (uint8_t)(autoReg & 0xFF), (uint8_t)((autoReg >> 16) & 0xFF) };
}
