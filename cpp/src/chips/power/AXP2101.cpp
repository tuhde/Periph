#include "AXP2101.h"
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

struct RailInfo {
    const char* name;
    uint8_t enReg;
    uint8_t enBit;
    uint8_t vReg;
    uint8_t kind;   // 0 dcdc1, 1 dcdc2/3, 2 dcdc4, 3 ldo 100 mV, 4 ldo 50 mV, 5 dldo1
    uint8_t vmask;
};

const RailInfo kRails[] = {
    {"dcdc1",   0x80, 0, 0x82, 0, 0x1F},
    {"dcdc2",   0x80, 1, 0x83, 1, 0x7F},
    {"dcdc3",   0x80, 2, 0x84, 1, 0x7F},
    {"dcdc4",   0x80, 3, 0x85, 2, 0x7F},
    {"aldo1",   0x90, 0, 0x92, 3, 0x1F},
    {"aldo2",   0x90, 1, 0x93, 3, 0x1F},
    {"aldo3",   0x90, 2, 0x94, 3, 0x1F},
    {"aldo4",   0x90, 3, 0x95, 3, 0x1F},
    {"bldo1",   0x90, 4, 0x96, 3, 0x1F},
    {"bldo2",   0x90, 5, 0x97, 3, 0x1F},
    {"cpusldo", 0x90, 6, 0x98, 4, 0x1F},
    {"dldo1",   0x90, 7, 0x99, 5, 0x1F},
    {"dldo2",   0x91, 0, 0x9A, 4, 0x1F},
};

struct FastInfo {
    const char* name;
    uint8_t offset;
    uint8_t shift;
};

const FastInfo kFast[] = {
    {"dcdc1", 0, 0}, {"dcdc2", 0, 2}, {"dcdc3", 0, 4}, {"dcdc4", 0, 6},
    {"aldo1", 1, 2}, {"aldo2", 1, 4}, {"aldo3", 1, 6},
    {"aldo4", 2, 0}, {"bldo1", 2, 2}, {"bldo2", 2, 4}, {"cpusldo", 2, 6},
    {"dldo1", 3, 0}, {"dldo2", 3, 2},
};

const float kCvKeys[]     = {4.0f, 4.1f, 4.2f, 4.35f, 4.4f};
const float kIinlimKeys[] = {0.1f, 0.5f, 0.9f, 1.0f, 1.5f, 2.0f};
const float kPreMinKeys[] = {40.0f, 50.0f, 60.0f, 70.0f};
const float kDoneHKeys[]  = {5.0f, 8.0f, 12.0f, 20.0f};
const float kThermKeys[]  = {60.0f, 80.0f, 100.0f, 120.0f};
const float kDieKeys[]    = {115.0f, 125.0f, 135.0f};
const float kTsCurKeys[]  = {20.0f, 40.0f, 50.0f, 60.0f};
const float kDebKeys[]    = {60.0f, 120.0f, 180.0f, 240.0f};
const float kOnKeys[]     = {128.0f, 512.0f, 1000.0f, 2000.0f};
const float kOffKeys[]    = {4.0f, 6.0f, 8.0f, 10.0f};
const float kIrqKeys[]    = {1.0f, 1.5f, 2.0f, 2.5f};
const float kPwrokKeys[]  = {8.0f, 16.0f, 32.0f, 64.0f};
const float kWdtKeys[]    = {1.0f, 2.0f, 4.0f, 8.0f, 16.0f, 32.0f, 64.0f, 128.0f};

int roundInt(float x) { return static_cast<int>(floorf(x + 0.5f)); }

// Nearest step code; false outside the range.
bool stepCode(float value, float start, float step, int maxCode, uint8_t& out) {
    int code = roundInt((value - start) / step);
    if (code < 0 || code > maxCode) return false;
    if (fabsf(value - (start + code * step)) > step / 2.0f + 1e-4f) return false;
    out = static_cast<uint8_t>(code);
    return true;
}

// Index of value in keys (the register code of the enumerated settings); false when absent.
bool indexOf(float value, const float* keys, size_t n, uint8_t& out) {
    for (size_t i = 0; i < n; i++) {
        if (fabsf(keys[i] - value) < 1e-4f) { out = static_cast<uint8_t>(i); return true; }
    }
    return false;
}

#define KEYS(a) a, sizeof(a) / sizeof((a)[0])

const RailInfo* findRail(const char* name) {
    if (!name) return nullptr;
    for (size_t i = 0; i < sizeof(kRails) / sizeof(kRails[0]); i++) {
        if (strcmp(kRails[i].name, name) == 0) return &kRails[i];
    }
    return nullptr;
}

bool railCode(uint8_t kind, float volts, uint8_t& out) {
    uint8_t c = 0;
    switch (kind) {
        case 0: return stepCode(volts, 1.5f, 0.1f, 19, out);
        case 3: return stepCode(volts, 0.5f, 0.1f, 30, out);
        case 4: return stepCode(volts, 0.5f, 0.05f, 19, out);
        case 5: return stepCode(volts, 0.5f, 0.1f, 28, out);
        default: {
            int top = (kind == 1) ? 87 : 102;
            if (volts <= 1.21f) return stepCode(volts, 0.5f, 0.01f, 70, out);
            if (!stepCode(volts, 1.22f, 0.02f, top - 71, c)) return false;
            out = static_cast<uint8_t>(71 + c);
            return true;
        }
    }
}

float railVolts(uint8_t kind, uint8_t code) {
    switch (kind) {
        case 0: return 1.5f + code * 0.1f;
        case 3: case 5: return 0.5f + code * 0.1f;
        case 4: return 0.5f + code * 0.05f;
        default: return code <= 70 ? 0.5f + code * 0.01f : 1.22f + (code - 71) * 0.02f;
    }
}

inline uint8_t bitIf(bool on, uint8_t b) { return on ? b : 0; }

}  // namespace

// --- AXP2101Minimal -----------------------------------------------------

AXP2101Minimal::AXP2101Minimal(RegisterConnection& connection)
    : _connection(connection) {
    _clearErr();
    _readReg(REG_STATUS1);
    _valid = !_err;
    if (!_valid) return;
    _rmw(REG_ADC_ENABLE, 0x1F, 0x1F);
    _rmw(REG_MODULE_ENABLE, 0x08, 0x08);
    _valid = !_err;
    // Wait for the first ADC conversions (driver choice; the datasheet gives no conversion time).
    delay(20);
}

uint8_t AXP2101Minimal::_readReg(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    if (!_connection.ok()) {
        v = 0;
        _connection.read(reg, &v, 1);   // retry once on a NACK
        if (!_connection.ok()) _err = true;
    }
    return v;
}

void AXP2101Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
    if (!_connection.ok()) {
        _connection.write(reg, &value, 1);   // retry once on a NACK
        if (!_connection.ok()) _err = true;
    }
}

void AXP2101Minimal::_rmw(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t cur = _readReg(reg);
    _writeReg(reg, static_cast<uint8_t>((cur & ~mask) | (value & mask)));
}

void AXP2101Minimal::_command(uint8_t reg, uint8_t b) {
    uint8_t cur = _readReg(reg);
    _writeReg(reg, static_cast<uint8_t>(cur | b));
}

uint16_t AXP2101Minimal::_adc14(uint8_t reg) {
    uint8_t hi = _readReg(reg);       // high byte first
    uint8_t lo = _readReg(reg + 1);
    return static_cast<uint16_t>(((hi & 0x3F) << 8) | lo);
}

float AXP2101Minimal::batteryVoltage() { return _adc14(REG_VBAT_H) * 0.001f; }

uint8_t AXP2101Minimal::batteryPercent() { return _readReg(REG_BAT_PERCENT) & 0x7F; }

uint8_t AXP2101Minimal::chargeState() { return _readReg(REG_STATUS2) & 0x07; }

bool AXP2101Minimal::vbusPresent() { return (_readReg(REG_STATUS1) & 0x20) != 0; }

// --- AXP2101Full --------------------------------------------------------

AXP2101Full* AXP2101Full::_activeInstance = nullptr;

AXP2101Full::AXP2101Full(RegisterConnection& connection) : AXP2101Minimal(connection) {}

float AXP2101Full::vbusVoltage() { return _adc14(REG_VBUS_H) * 0.001f; }

float AXP2101Full::systemVoltage() { return _adc14(REG_VSYS_H) * 0.001f; }

float AXP2101Full::tsVoltage() { return _adc14(REG_TS_H) * 0.0005f; }

float AXP2101Full::dieTemperature() { return 22.0f + (7274.0f - _adc14(REG_TDIE_H)) / 20.0f; }

void AXP2101Full::readAll(AXP2101Readings& out) {
    out.batteryVoltage = batteryVoltage();
    out.vbusVoltage    = vbusVoltage();
    out.systemVoltage  = systemVoltage();
    out.tsVoltage      = tsVoltage();
    out.dieTemperature = dieTemperature();
    out.batteryPercent = batteryPercent();
    out.chargeState    = chargeState();
    out.vbusPresent    = vbusPresent();
}

void AXP2101Full::status(AXP2101Status& out) {
    uint8_t s1 = _readReg(REG_STATUS1);
    uint8_t s2 = _readReg(REG_STATUS2);
    static const char* const directions[4] = {"standby", "charge", "discharge", "reserved"};
    out.vbusGood          = (s1 & 0x20) != 0;
    out.batfetOpen        = (s1 & 0x10) != 0;
    out.batteryPresent    = (s1 & 0x08) != 0;
    out.batteryActive     = (s1 & 0x04) != 0;
    out.thermalRegulation = (s1 & 0x02) != 0;
    out.currentLimit      = (s1 & 0x01) != 0;
    out.batteryDirection  = directions[(s2 >> 5) & 3];
    out.systemOn          = (s2 & 0x10) != 0;
    out.vindpm            = (s2 & 0x08) != 0;
    out.chargeState       = s2 & 0x07;
}

bool AXP2101Full::setAdcChannels(bool vbat, bool ts, bool vbus, bool vsys, bool tdie, bool gpadc) {
    _clearErr();
    _rmw(REG_ADC_ENABLE, 0x3F, bitIf(vbat, 1) | bitIf(ts, 2) | bitIf(vbus, 4) | bitIf(vsys, 8) | bitIf(tdie, 16) | bitIf(gpadc, 32));
    return !_err;
}

bool AXP2101Full::setAdcLowFrequency(bool enabled) {
    _clearErr();
    _rmw(REG_TS_H, 0x80, bitIf(enabled, 0x80));
    return !_err;
}

bool AXP2101Full::setCharging(bool enabled) {
    _clearErr();
    _rmw(REG_MODULE_ENABLE, 0x02, bitIf(enabled, 0x02));
    return !_err;
}

bool AXP2101Full::setChargeVoltage(float volts) {
    uint8_t i;
    if (!indexOf(volts, KEYS(kCvKeys), i)) return false;
    _clearErr();
    _rmw(REG_CV, 0x07, static_cast<uint8_t>(i + 1));
    return !_err;
}

bool AXP2101Full::setChargeCurrent(float amps) {
    if (amps < 0.0f || amps > 1.5f + 1e-4f) return false;
    _clearErr();
    uint8_t cv = _readReg(REG_CV) & 0x07;
    float limit = (cv == 3) ? 1.4f : (cv == 4) ? 1.1f : (cv == 5) ? 1.0f : 1.5f;
    if (_err || amps > limit + 1e-4f) return false;
    int code;
    if (amps <= 0.25f) {
        code = roundInt(amps / 0.025f);
        if (code > 8) code = 8;
    } else {
        code = 8 + roundInt((amps - 0.2f) / 0.1f);
    }
    _rmw(REG_ICC, 0x1F, static_cast<uint8_t>(code));
    return !_err;
}

bool AXP2101Full::setPrechargeCurrent(float amps) {
    uint8_t code;
    if (!stepCode(amps, 0.0f, 0.025f, 8, code)) return false;
    _clearErr();
    _rmw(REG_IPRECHG, 0x0F, code);
    return !_err;
}

bool AXP2101Full::setTermination(float amps, bool enabled) {
    uint8_t code;
    if (!stepCode(amps, 0.0f, 0.025f, 8, code)) return false;
    _clearErr();
    _rmw(REG_ITERM, 0x1F, static_cast<uint8_t>(code | bitIf(enabled, 0x10)));
    return !_err;
}

bool AXP2101Full::setInputCurrentLimit(float amps) {
    uint8_t i;
    if (!indexOf(amps, KEYS(kIinlimKeys), i)) return false;
    _clearErr();
    _rmw(REG_IINLIM, 0x07, i);
    return !_err;
}

bool AXP2101Full::setInputVoltageLimit(float volts) {
    uint8_t code;
    if (!stepCode(volts, 3.88f, 0.08f, 15, code)) return false;
    _clearErr();
    _rmw(REG_VINDPM, 0x0F, code);
    return !_err;
}

bool AXP2101Full::setMinSystemVoltage(float volts) {
    uint8_t code;
    if (!stepCode(volts, 3.2f, 0.1f, 7, code)) return false;
    _clearErr();
    _rmw(REG_MIN_VSYS, 0x07, code);
    return !_err;
}

bool AXP2101Full::setChargeTimers(uint8_t prechargeMin, uint8_t doneHours, bool prechargeEnabled,
                                  bool doneEnabled, bool slowInDpm) {
    uint8_t pre, done;
    if (!indexOf(prechargeMin, KEYS(kPreMinKeys), pre)) return false;
    if (!indexOf(doneHours, KEYS(kDoneHKeys), done)) return false;
    _clearErr();
    _rmw(REG_CHG_TIMER, 0xF7, static_cast<uint8_t>(pre | bitIf(prechargeEnabled, 0x04) | (done << 4) |
                                                     bitIf(doneEnabled, 0x40) | bitIf(slowInDpm, 0x80)));
    return !_err;
}

bool AXP2101Full::setThermalRegulation(uint8_t celsius) {
    uint8_t i;
    if (!indexOf(celsius, KEYS(kThermKeys), i)) return false;
    _clearErr();
    _rmw(REG_THERMAL_REG, 0x03, i);
    return !_err;
}

bool AXP2101Full::setDieProtection(uint8_t level1Celsius, bool detect) {
    uint8_t i;
    if (!indexOf(level1Celsius, KEYS(kDieKeys), i)) return false;
    _clearErr();
    _rmw(REG_DIE_TEMP_CTRL, 0x07, static_cast<uint8_t>((i << 1) | bitIf(detect, 1)));
    return !_err;
}

bool AXP2101Full::setBatteryDetection(bool enabled) {
    _clearErr();
    _rmw(REG_BAT_DETECT, 0x01, bitIf(enabled, 1));
    return !_err;
}

bool AXP2101Full::setBackupCharging(bool enabled, float volts) {
    uint8_t code;
    if (!stepCode(volts, 2.6f, 0.1f, 7, code)) return false;
    _clearErr();
    _rmw(REG_BTN_BAT_VTERM, 0x07, code);
    _rmw(REG_MODULE_ENABLE, 0x04, bitIf(enabled, 0x04));
    return !_err;
}

bool AXP2101Full::setChgled(const char* mode, const char* output) {
    uint8_t value, out;
    if (strcmp(mode, "off") == 0) value = 0x00;
    else if (strcmp(mode, "type_a") == 0) value = 0x01;
    else if (strcmp(mode, "type_b") == 0) value = 0x03;
    else if (strcmp(mode, "manual") == 0) value = 0x05;
    else return false;
    if (strcmp(output, "hiz") == 0) out = 0;
    else if (strcmp(output, "blink_1hz") == 0) out = 1;
    else if (strcmp(output, "blink_4hz") == 0) out = 2;
    else if (strcmp(output, "low") == 0) out = 3;
    else return false;
    if (strcmp(mode, "manual") == 0) value = static_cast<uint8_t>(value | (out << 4));
    _clearErr();
    _rmw(REG_CHGLED_CTRL, 0x37, value);
    return !_err;
}

bool AXP2101Full::configureTs(bool batterySensor, uint8_t currentUa, const char* source) {
    uint8_t s, cur;
    if (strcmp(source, "off") == 0) s = 0;
    else if (strcmp(source, "when_adc") == 0) s = 1;
    else if (strcmp(source, "when_ts") == 0) s = 2;
    else if (strcmp(source, "always") == 0) s = 3;
    else return false;
    if (!indexOf(currentUa, KEYS(kTsCurKeys), cur)) return false;
    _clearErr();
    _rmw(REG_TS_CTRL, 0x1F, static_cast<uint8_t>(cur | (s << 2) | bitIf(!batterySensor, 0x10)));
    return !_err;
}

bool AXP2101Full::setTsLimits(float chargeCold, float chargeHot, float workCold, float workHot) {
    uint8_t a, b, c, d;
    if (!stepCode(chargeCold, 0.0f, 0.032f, 255, a) || !stepCode(chargeHot, 0.0f, 0.002f, 255, b) ||
        !stepCode(workCold, 0.0f, 0.032f, 255, c) || !stepCode(workHot, 0.0f, 0.002f, 255, d)) return false;
    _clearErr();
    _writeReg(REG_VLTF_CHG, a);
    _writeReg(REG_VHTF_CHG, b);
    _writeReg(REG_VLTF_WORK, c);
    _writeReg(REG_VHTF_WORK, d);
    return !_err;
}

bool AXP2101Full::setTsHysteresis(float lowToNormal, float highToNormal) {
    uint8_t l2h, h2l;
    if (!stepCode(lowToNormal, 0.0f, 0.016f, 255, l2h) || !stepCode(highToNormal, 0.0f, 0.004f, 255, h2l)) return false;
    _clearErr();
    _writeReg(REG_TS_HYS_L2H, l2h);
    _writeReg(REG_TS_HYS_H2L, h2l);
    return !_err;
}

bool AXP2101Full::configureJeita(bool enabled, float coolVoltage, float warmVoltage, bool coolHalfCurrent,
                                 bool warmHalfCurrent, uint8_t coolVoltageDrop, uint8_t warmVoltageDrop) {
    if (coolVoltageDrop > 2 || warmVoltageDrop > 2) return false;
    uint8_t cool = 0, warm = 0;
    if (coolVoltage >= 0.0f && !stepCode(coolVoltage, 0.0f, 0.016f, 255, cool)) return false;
    if (warmVoltage >= 0.0f && !stepCode(warmVoltage, 0.0f, 0.008f, 255, warm)) return false;
    _clearErr();
    _rmw(REG_JEITA_CV, 0x5F, static_cast<uint8_t>(coolVoltageDrop | (warmVoltageDrop << 2) |
                                                   bitIf(coolHalfCurrent, 0x10) | bitIf(warmHalfCurrent, 0x40)));
    if (coolVoltage >= 0.0f) _writeReg(REG_JEITA_COOL, cool);
    if (warmVoltage >= 0.0f) _writeReg(REG_JEITA_WARM, warm);
    _rmw(REG_JEITA_EN, 0x01, bitIf(enabled, 1));
    return !_err;
}

bool AXP2101Full::getRail(const char* name, bool& enabled, float& volts) {
    const RailInfo* r = findRail(name);
    if (!r) return false;
    _clearErr();
    enabled = (_readReg(r->enReg) & (1 << r->enBit)) != 0;
    volts = railVolts(r->kind, _readReg(r->vReg) & r->vmask);
    return !_err;
}

bool AXP2101Full::setRailVoltage(const char* name, float volts) {
    const RailInfo* r = findRail(name);
    uint8_t code;
    if (!r || !railCode(r->kind, volts, code)) return false;
    _clearErr();
    _rmw(r->vReg, r->vmask, code);
    return !_err;
}

bool AXP2101Full::setRailEnabled(const char* name, bool enabled) {
    const RailInfo* r = findRail(name);
    if (!r) return false;
    _clearErr();
    uint8_t b = static_cast<uint8_t>(1 << r->enBit);
    _rmw(r->enReg, b, bitIf(enabled, b));
    return !_err;
}

bool AXP2101Full::configureDcdc(const char* name, bool alwaysPwm, int8_t dvm) {
    const RailInfo* r = findRail(name);
    if (!r || r->enReg != 0x80) return false;
    if (dvm >= 0 && strcmp(name, "dcdc2") != 0 && strcmp(name, "dcdc3") != 0) return false;
    _clearErr();
    uint8_t b = static_cast<uint8_t>(1 << (r->enBit + 2));
    _rmw(REG_DCDC_PWM, b, bitIf(alwaysPwm, b));
    if (dvm >= 0) _rmw(r->vReg, 0x80, bitIf(dvm != 0, 0x80));
    return !_err;
}

bool AXP2101Full::configureDcdcGlobal(bool forceCcm, bool slowRamp, bool frequencySpread, bool spread100kHz,
                                      uint16_t uvpDebounceUs) {
    uint8_t deb;
    if (!indexOf(uvpDebounceUs, KEYS(kDebKeys), deb)) return false;
    _clearErr();
    _rmw(REG_DCDC_EN, 0x60, static_cast<uint8_t>(bitIf(forceCcm, 0x40) | bitIf(slowRamp, 0x20)));
    _rmw(REG_DCDC_PWM, 0xC3, static_cast<uint8_t>(bitIf(frequencySpread, 0x80) | bitIf(spread100kHz, 0x40) | deb));
    return !_err;
}

bool AXP2101Full::setOffDischarge(bool enabled) {
    _clearErr();
    _rmw(REG_COMMON_CONFIG, 0x20, bitIf(enabled, 0x20));
    return !_err;
}

bool AXP2101Full::powerOff() { _clearErr(); _command(REG_COMMON_CONFIG, 0x01); return !_err; }

bool AXP2101Full::restart() { _clearErr(); _command(REG_COMMON_CONFIG, 0x02); return !_err; }

bool AXP2101Full::sleep() { _clearErr(); _command(REG_SLEEP_CTRL, 0x01); return !_err; }

bool AXP2101Full::wakeup() { _clearErr(); _command(REG_SLEEP_CTRL, 0x02); return !_err; }

bool AXP2101Full::configureSleep(bool wakeOnIrq, bool pwrokLow, bool restoreVoltages) {
    _clearErr();
    _rmw(REG_SLEEP_CTRL, 0x1C, static_cast<uint8_t>(bitIf(wakeOnIrq, 0x10) | bitIf(pwrokLow, 0x08) | bitIf(restoreVoltages, 0x04)));
    return !_err;
}

uint8_t AXP2101Full::powerOnSource() { return _readReg(REG_PWRON_STATUS); }

uint8_t AXP2101Full::powerOffSource() { return _readReg(REG_PWROFF_STATUS); }

bool AXP2101Full::configurePowerKey(uint16_t onMs, float offS, float irqS, bool longPressOff,
                                    bool restartInstead, bool forceOff16s) {
    uint8_t on, off, irq;
    if (!indexOf(onMs, KEYS(kOnKeys), on) || !indexOf(offS, KEYS(kOffKeys), off) ||
        !indexOf(irqS, KEYS(kIrqKeys), irq)) return false;
    _clearErr();
    _rmw(REG_PWRKEY_LEVEL, 0x3F, static_cast<uint8_t>(on | (off << 2) | (irq << 4)));
    _rmw(REG_PWROFF_EN, 0x03, static_cast<uint8_t>(bitIf(longPressOff, 0x02) | bitIf(restartInstead, 0x01)));
    _rmw(REG_COMMON_CONFIG, 0x04, bitIf(forceOff16s, 0x04));
    return !_err;
}

bool AXP2101Full::configurePowerOff(bool dieOverTemp, float offVoltage, bool dcdcOvp, uint8_t dcdcUvp) {
    uint8_t code;
    if (!stepCode(offVoltage, 2.6f, 0.1f, 7, code)) return false;
    _clearErr();
    _rmw(REG_PWROFF_EN, 0x04, bitIf(dieOverTemp, 0x04));
    _rmw(REG_VOFF, 0x07, code);
    _rmw(REG_DCDC_PROT, 0x2F, static_cast<uint8_t>(bitIf(dcdcOvp, 0x20) | (dcdcUvp & 0x0F)));
    return !_err;
}

bool AXP2101Full::configurePwrok(bool check, uint8_t delayMs, bool offDelay, bool reverseOffSequence,
                                 bool restartOnLow) {
    uint8_t delayCode;
    if (!indexOf(delayMs, KEYS(kPwrokKeys), delayCode)) return false;
    _clearErr();
    _rmw(REG_PWROK_CTRL, 0x1F, static_cast<uint8_t>(delayCode | bitIf(reverseOffSequence, 0x04) |
                                                      bitIf(offDelay, 0x08) | bitIf(check, 0x10)));
    _rmw(REG_COMMON_CONFIG, 0x08, bitIf(restartOnLow, 0x08));
    return !_err;
}

bool AXP2101Full::setBatfetOffEnable(bool enabled) {
    _clearErr();
    _rmw(REG_BATFET_CTRL, 0x08, bitIf(enabled, 0x08));
    return !_err;
}

bool AXP2101Full::configureFastPoweron(bool poweron, bool wakeup, const AXP2101FastEntry* sequence, size_t count) {
    for (size_t i = 0; sequence && i < count; i++) {
        bool known = false;
        for (size_t k = 0; k < sizeof(kFast) / sizeof(kFast[0]); k++) {
            if (strcmp(kFast[k].name, sequence[i].rail) == 0) known = true;
        }
        if (!known || sequence[i].code > 2) return false;
    }
    _clearErr();
    for (size_t i = 0; sequence && i < count; i++) {
        for (size_t k = 0; k < sizeof(kFast) / sizeof(kFast[0]); k++) {
            if (strcmp(kFast[k].name, sequence[i].rail) != 0) continue;
            uint8_t c = sequence[i].code < 0 ? 3 : static_cast<uint8_t>(sequence[i].code);
            _rmw(static_cast<uint8_t>(REG_FAST_PWRON0 + kFast[k].offset), static_cast<uint8_t>(3 << kFast[k].shift),
                 static_cast<uint8_t>(c << kFast[k].shift));
        }
    }
    _rmw(REG_FAST_PWRON0 + 3, 0xC0, static_cast<uint8_t>(bitIf(poweron, 0x80) | bitIf(wakeup, 0x40)));
    return !_err;
}

bool AXP2101Full::configureWatchdog(bool enabled, uint8_t timeoutS, const char* action) {
    uint8_t a, code;
    if (strcmp(action, "irq") == 0) a = 0;
    else if (strcmp(action, "reset") == 0) a = 1;
    else if (strcmp(action, "reset_pwrok") == 0) a = 2;
    else if (strcmp(action, "power_cycle") == 0) a = 3;
    else return false;
    if (!indexOf(timeoutS, KEYS(kWdtKeys), code)) return false;
    _clearErr();
    _rmw(REG_WATCHDOG_CTRL, 0x37, static_cast<uint8_t>(code | (a << 4)));
    _rmw(REG_MODULE_ENABLE, 0x01, bitIf(enabled, 1));
    return !_err;
}

bool AXP2101Full::feedWatchdog() { _clearErr(); _command(REG_WATCHDOG_CTRL, 0x08); return !_err; }

bool AXP2101Full::configureBatteryWarning(uint8_t level2, uint8_t level1) {
    if (level2 < 5 || level2 > 20 || level1 > 15) return false;
    _clearErr();
    _writeReg(REG_LOWBAT_WARN, static_cast<uint8_t>(((level2 - 5) << 4) | level1));
    return !_err;
}

bool AXP2101Full::setGaugeEnabled(bool enabled) {
    _clearErr();
    _rmw(REG_MODULE_ENABLE, 0x08, bitIf(enabled, 0x08));
    return !_err;
}

bool AXP2101Full::resetGauge(bool full) {
    _clearErr();
    _command(REG_GAUGE_RESET, full ? 0x04 : 0x08);
    return !_err;
}

bool AXP2101Full::setGpio1(bool low) {
    _clearErr();
    _rmw(REG_GPIO1_CTRL, 0x0C, bitIf(low, 0x04));
    return !_err;
}

bool AXP2101Full::readDataBuffer(uint8_t index, uint8_t& value) {
    if (index > 3) return false;
    _clearErr();
    value = _readReg(static_cast<uint8_t>(REG_DATA_BUFFER0 + index));
    return !_err;
}

bool AXP2101Full::writeDataBuffer(uint8_t index, uint8_t value) {
    if (index > 3) return false;
    _clearErr();
    _writeReg(static_cast<uint8_t>(REG_DATA_BUFFER0 + index), value);
    return !_err;
}

bool AXP2101Full::enableInterrupt(uint32_t source) {
    _clearErr();
    for (uint8_t k = 0; k < 3; k++) {
        uint8_t bits = static_cast<uint8_t>(source >> (8 * k));
        if (bits) _rmw(static_cast<uint8_t>(REG_IRQ_EN0 + k), bits, bits);
    }
    return !_err;
}

bool AXP2101Full::disableInterrupt(uint32_t source) {
    _clearErr();
    for (uint8_t k = 0; k < 3; k++) {
        uint8_t bits = static_cast<uint8_t>(source >> (8 * k));
        if (bits) _rmw(static_cast<uint8_t>(REG_IRQ_EN0 + k), bits, 0);
    }
    return !_err;
}

uint32_t AXP2101Full::pollInterrupt() {
    uint32_t status = 0;
    for (uint8_t k = 0; k < 3; k++) {
        uint8_t v = _readReg(static_cast<uint8_t>(REG_IRQ_STATUS0 + k));
        if (v) _writeReg(static_cast<uint8_t>(REG_IRQ_STATUS0 + k), v);   // write back exactly the bits seen
        status |= static_cast<uint32_t>(v) << (8 * k);
    }
    return status;
}

void AXP2101Full::onInterrupt(void (*callback)(uint32_t status)) {
    offInterrupt();
    _callback = callback;
    InputPin* pin = _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&AXP2101Full::_edgeTrampoline, InputPin::kFalling);
}

void AXP2101Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&AXP2101Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void AXP2101Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void AXP2101Full::_handleEdge() {
    uint32_t status = pollInterrupt();
    if (status && _callback) _callback(status);
}
