#include "DS3231.h"

#ifdef __ZEPHYR__
#include <zephyr/kernel.h>
static inline void ds3231_delay_ms(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void ds3231_delay_ms(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void ds3231_delay_ms(unsigned long ms) { sleep_ms(ms); }
#elif !defined(ARDUINO)
#include <unistd.h>
static inline void ds3231_delay_ms(unsigned long ms) { usleep(ms * 1000UL); }
#else
static inline void ds3231_delay_ms(unsigned long ms) { delay(ms); }
#endif

// DS3231Minimal

DS3231Minimal::DS3231Minimal(Connection& connection) : _connection(connection) {}

void DS3231Minimal::_writeReg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

uint8_t DS3231Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1];
    _connection.write_read(&reg, 1, buf, 1);
    return buf[0];
}

void DS3231Minimal::_writeRegs(uint8_t startReg, const uint8_t* data, size_t len) {
    uint8_t buf[8];
    buf[0] = startReg;
    for (size_t i = 0; i < len; ++i) buf[1 + i] = data[i];
    _connection.write(buf, len + 1);
}

void DS3231Minimal::_readRegs(uint8_t startReg, uint8_t* buf, size_t len) {
    _connection.write_read(&startReg, 1, buf, len);
}

void DS3231Minimal::_delayMs(uint32_t ms) { ds3231_delay_ms(ms); }

uint8_t DS3231Minimal::_bcdToInt(uint8_t bcd) { return (uint8_t)(((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F)); }

uint8_t DS3231Minimal::_intToBcd(uint8_t value) { return (uint8_t)(((value / 10) << 4) | (value % 10)); }

uint8_t DS3231Minimal::_decodeHour(uint8_t raw) {
    if (raw & 0x40) {
        // 12-hour mode: bit5=AM/PM, bit4=10-hour digit, bits3:0=units.
        bool pm = (raw & 0x20) != 0;
        uint8_t hour12 = (uint8_t)((((raw >> 4) & 0x01) * 10) + (raw & 0x0F));
        if (pm && hour12 != 12) return (uint8_t)(hour12 + 12);
        if (!pm && hour12 == 12) return 0;
        return hour12;
    }
    // 24-hour mode: bits5:0 are a plain BCD value 00-23.
    return _bcdToInt((uint8_t)(raw & 0x3F));
}

void DS3231Minimal::getDatetime(DateTime& dt) {
    uint8_t buf[7];
    _readRegs(REG_SECONDS, buf, 7);
    dt.second  = _bcdToInt((uint8_t)(buf[0] & 0x7F));
    dt.minute  = _bcdToInt((uint8_t)(buf[1] & 0x7F));
    dt.hour    = _decodeHour(buf[2]);
    dt.weekday = _bcdToInt((uint8_t)(buf[3] & 0x07));
    dt.day     = _bcdToInt((uint8_t)(buf[4] & 0x3F));
    dt.month   = _bcdToInt((uint8_t)(buf[5] & 0x1F));
    dt.year    = (uint16_t)(2000 + _bcdToInt(buf[6]));
}

void DS3231Minimal::setDatetime(const DateTime& dt) {
    uint8_t buf[7];
    buf[0] = _intToBcd(dt.second);
    buf[1] = _intToBcd(dt.minute);
    buf[2] = (uint8_t)(_intToBcd(dt.hour) & 0x3F);  // 24-hour mode, bit6=0
    buf[3] = _intToBcd(dt.weekday);
    buf[4] = _intToBcd(dt.day);
    buf[5] = (uint8_t)(_intToBcd(dt.month) & 0x1F);
    buf[6] = _intToBcd((uint8_t)(dt.year - 2000));
    _writeRegs(REG_SECONDS, buf, 7);

    // The time is now known-good: clear OSF (writing back the rest of the
    // status byte as read is safe, see the spec's Implementation Notes).
    uint8_t status = _readReg(REG_STATUS);
    _writeReg(REG_STATUS, (uint8_t)(status & (uint8_t)~STAT_OSF));
}

float DS3231Minimal::readTemperature() {
    uint8_t buf[2];
    _readRegs(REG_TEMP_MSB, buf, 2);
    int8_t msb = (int8_t)buf[0];
    uint8_t frac = (uint8_t)((buf[1] >> 6) & 0x03);
    return (float)msb + (float)frac * 0.25f;
}

// DS3231Full

DS3231Full* DS3231Full::_activeInstance = nullptr;

DS3231Full::DS3231Full(Connection& connection) : DS3231Minimal(connection) {}

void DS3231Full::_alarm1MaskBits(uint8_t matchMode, uint8_t& a1m1, uint8_t& a1m2,
                                 uint8_t& a1m3, uint8_t& a1m4, uint8_t& dydt) {
    a1m1 = a1m2 = a1m3 = a1m4 = dydt = 0;
    switch (matchMode) {
        case DS3231Full::ALARM1_EVERY_SECOND:                    a1m1 = a1m2 = a1m3 = a1m4 = 1; break;
        case DS3231Full::ALARM1_MATCH_SECONDS:                   a1m2 = a1m3 = a1m4 = 1; break;
        case DS3231Full::ALARM1_MATCH_MINUTES_SECONDS:           a1m3 = a1m4 = 1; break;
        case DS3231Full::ALARM1_MATCH_HOURS_MINUTES_SECONDS:     a1m4 = 1; break;
        case DS3231Full::ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS: dydt = 1; break;
        case DS3231Full::ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS:
        default: break;  // all mask bits 0, DY/DT=0 (date match)
    }
}

void DS3231Full::_alarm2MaskBits(uint8_t matchMode, uint8_t& a2m2, uint8_t& a2m3,
                                 uint8_t& a2m4, uint8_t& dydt) {
    a2m2 = a2m3 = a2m4 = dydt = 0;
    switch (matchMode) {
        case DS3231Full::ALARM2_EVERY_MINUTE:            a2m2 = a2m3 = a2m4 = 1; break;
        case DS3231Full::ALARM2_MATCH_MINUTES:            a2m3 = a2m4 = 1; break;
        case DS3231Full::ALARM2_MATCH_HOURS_MINUTES:      a2m4 = 1; break;
        case DS3231Full::ALARM2_MATCH_DAY_HOURS_MINUTES:  dydt = 1; break;
        case DS3231Full::ALARM2_MATCH_DATE_HOURS_MINUTES:
        default: break;
    }
}

uint8_t DS3231Full::_alarm1MatchMode(uint8_t a1m1, uint8_t a1m2, uint8_t a1m3,
                                     uint8_t a1m4, uint8_t dydt) {
    if (a1m4) {
        if (a1m3) return a1m2 ? (a1m1 ? ALARM1_EVERY_SECOND : ALARM1_MATCH_SECONDS)
                              : ALARM1_MATCH_MINUTES_SECONDS;
        return ALARM1_MATCH_HOURS_MINUTES_SECONDS;
    }
    return dydt ? ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS : ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS;
}

uint8_t DS3231Full::_alarm2MatchMode(uint8_t a2m2, uint8_t a2m3, uint8_t a2m4, uint8_t dydt) {
    if (a2m4) {
        if (a2m3) return a2m2 ? ALARM2_EVERY_MINUTE : ALARM2_MATCH_MINUTES;
        return ALARM2_MATCH_HOURS_MINUTES;
    }
    return dydt ? ALARM2_MATCH_DAY_HOURS_MINUTES : ALARM2_MATCH_DATE_HOURS_MINUTES;
}

void DS3231Full::getAlarm1(Alarm1& alarm) {
    uint8_t buf[4];
    _readRegs(REG_ALARM1_SECONDS, buf, 4);
    uint8_t a1m1 = (buf[0] >> 7) & 1, a1m2 = (buf[1] >> 7) & 1, a1m3 = (buf[2] >> 7) & 1;
    uint8_t a1m4 = (buf[3] >> 7) & 1, dydt = (buf[3] >> 6) & 1;
    alarm.second      = _bcdToInt((uint8_t)(buf[0] & 0x7F));
    alarm.minute      = _bcdToInt((uint8_t)(buf[1] & 0x7F));
    alarm.hour        = _decodeHour((uint8_t)(buf[2] & 0x7F));
    alarm.dayOrDate    = _bcdToInt((uint8_t)(buf[3] & 0x3F));
    alarm.isDayOfWeek  = dydt != 0;
    alarm.matchMode    = _alarm1MatchMode(a1m1, a1m2, a1m3, a1m4, dydt);
}

void DS3231Full::setAlarm1(const Alarm1& alarm) {
    uint8_t a1m1, a1m2, a1m3, a1m4, dydt;
    _alarm1MaskBits(alarm.matchMode, a1m1, a1m2, a1m3, a1m4, dydt);
    bool isDayOfWeek = alarm.matchMode == ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS ? true
                     : alarm.matchMode == ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS ? false
                     : alarm.isDayOfWeek;
    uint8_t buf[4];
    buf[0] = (uint8_t)((a1m1 << 7) | _intToBcd(alarm.second));
    buf[1] = (uint8_t)((a1m2 << 7) | _intToBcd(alarm.minute));
    buf[2] = (uint8_t)((a1m3 << 7) | (_intToBcd(alarm.hour) & 0x3F));
    buf[3] = (uint8_t)((a1m4 << 7) | ((isDayOfWeek ? 1 : 0) << 6) | (_intToBcd(alarm.dayOrDate) & 0x3F));
    _writeRegs(REG_ALARM1_SECONDS, buf, 4);
}

void DS3231Full::getAlarm2(Alarm2& alarm) {
    uint8_t buf[3];
    _readRegs(REG_ALARM2_MINUTES, buf, 3);
    uint8_t a2m2 = (buf[0] >> 7) & 1, a2m3 = (buf[1] >> 7) & 1;
    uint8_t a2m4 = (buf[2] >> 7) & 1, dydt = (buf[2] >> 6) & 1;
    alarm.minute      = _bcdToInt((uint8_t)(buf[0] & 0x7F));
    alarm.hour        = _decodeHour((uint8_t)(buf[1] & 0x7F));
    alarm.dayOrDate    = _bcdToInt((uint8_t)(buf[2] & 0x3F));
    alarm.isDayOfWeek  = dydt != 0;
    alarm.matchMode    = _alarm2MatchMode(a2m2, a2m3, a2m4, dydt);
}

void DS3231Full::setAlarm2(const Alarm2& alarm) {
    uint8_t a2m2, a2m3, a2m4, dydt;
    _alarm2MaskBits(alarm.matchMode, a2m2, a2m3, a2m4, dydt);
    bool isDayOfWeek = alarm.matchMode == ALARM2_MATCH_DAY_HOURS_MINUTES ? true
                     : alarm.matchMode == ALARM2_MATCH_DATE_HOURS_MINUTES ? false
                     : alarm.isDayOfWeek;
    uint8_t buf[3];
    buf[0] = (uint8_t)((a2m2 << 7) | _intToBcd(alarm.minute));
    buf[1] = (uint8_t)((a2m3 << 7) | (_intToBcd(alarm.hour) & 0x3F));
    buf[2] = (uint8_t)((a2m4 << 7) | ((isDayOfWeek ? 1 : 0) << 6) | (_intToBcd(alarm.dayOrDate) & 0x3F));
    _writeRegs(REG_ALARM2_MINUTES, buf, 3);
}

void DS3231Full::enableSquareWave(uint32_t rateHz, bool batteryBacked) {
    uint8_t rs;
    if (rateHz >= 8192)      rs = CTRL_RS2 | CTRL_RS1;
    else if (rateHz >= 4096) rs = CTRL_RS2;
    else if (rateHz >= 1024) rs = CTRL_RS1;
    else                     rs = 0;

    uint8_t ctrl = _readReg(REG_CONTROL);
    ctrl &= (uint8_t)~(CTRL_INTCN | CTRL_RS2 | CTRL_RS1 | CTRL_BBSQW);
    ctrl |= rs;
    if (batteryBacked) ctrl |= CTRL_BBSQW;
    _writeReg(REG_CONTROL, ctrl);
}

void DS3231Full::disableSquareWave() {
    uint8_t ctrl = _readReg(REG_CONTROL);
    _writeReg(REG_CONTROL, (uint8_t)(ctrl | CTRL_INTCN));
}

bool DS3231Full::is32kHzEnabled() { return (_readReg(REG_STATUS) & STAT_EN32KHZ) != 0; }

void DS3231Full::enable32kHzOutput() {
    uint8_t status = _readReg(REG_STATUS);
    _writeReg(REG_STATUS, (uint8_t)(status | STAT_EN32KHZ));
}

void DS3231Full::disable32kHzOutput() {
    uint8_t status = _readReg(REG_STATUS);
    _writeReg(REG_STATUS, (uint8_t)(status & (uint8_t)~STAT_EN32KHZ));
}

bool DS3231Full::oscillatorStopped() { return (_readReg(REG_STATUS) & STAT_OSF) != 0; }

void DS3231Full::clearOscillatorStopped() {
    uint8_t status = _readReg(REG_STATUS);
    _writeReg(REG_STATUS, (uint8_t)(status & (uint8_t)~STAT_OSF));
}

void DS3231Full::enableBatteryOscillator() {
    uint8_t ctrl = _readReg(REG_CONTROL);
    _writeReg(REG_CONTROL, (uint8_t)(ctrl & (uint8_t)~CTRL_EOSC));
}

void DS3231Full::disableBatteryOscillator() {
    uint8_t ctrl = _readReg(REG_CONTROL);
    _writeReg(REG_CONTROL, (uint8_t)(ctrl | CTRL_EOSC));
}

void DS3231Full::forceTemperatureConversion() {
    uint8_t ctrl = _readReg(REG_CONTROL);
    _writeReg(REG_CONTROL, (uint8_t)(ctrl | CTRL_CONV));
    // Max 200 ms per the datasheet AC Electrical Characteristics (tCONV).
    for (int i = 0; i < 40; ++i) {
        if ((_readReg(REG_STATUS) & STAT_BSY) == 0) return;
        _delayMs(5);
    }
}

int8_t DS3231Full::getAgingOffset() { return (int8_t)_readReg(REG_AGING_OFFSET); }

void DS3231Full::setAgingOffset(int8_t offset) { _writeReg(REG_AGING_OFFSET, (uint8_t)offset); }

void DS3231Full::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&DS3231Full::_edgeTrampoline, InputPin::kFalling);
}

void DS3231Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&DS3231Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint8_t DS3231Full::pollInterrupt() {
    uint8_t status = _readReg(REG_STATUS);
    uint8_t sources = (uint8_t)(status & (STAT_A1F | STAT_A2F));
    _writeReg(REG_STATUS, (uint8_t)(status & (uint8_t)~(STAT_A1F | STAT_A2F)));
    return sources;
}

void DS3231Full::enableInterrupt(uint8_t source) {
    uint8_t ctrl = _readReg(REG_CONTROL);
    if (source & SOURCE_ALARM1) ctrl |= CTRL_A1IE;
    if (source & SOURCE_ALARM2) ctrl |= CTRL_A2IE;
    ctrl |= CTRL_INTCN;
    _writeReg(REG_CONTROL, ctrl);
}

void DS3231Full::disableInterrupt(uint8_t source) {
    uint8_t ctrl = _readReg(REG_CONTROL);
    if (source & SOURCE_ALARM1) ctrl &= (uint8_t)~CTRL_A1IE;
    if (source & SOURCE_ALARM2) ctrl &= (uint8_t)~CTRL_A2IE;
    _writeReg(REG_CONTROL, ctrl);
}

void DS3231Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void DS3231Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (status && _callback) _callback(status);
}
