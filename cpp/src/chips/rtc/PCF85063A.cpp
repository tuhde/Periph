#include "PCF85063A.h"

// PCF85063AMinimal

PCF85063AMinimal::PCF85063AMinimal(RegisterConnection& connection) : _connection(connection) {
    _readReg(REG_CONTROL_1);  // presence check (no identity register)
    uint8_t ctrl1 = _readControl1();
    _writeReg(REG_CONTROL_1, (uint8_t)(ctrl1 & (uint8_t)~(C1_STOP | C1_12_24)));
}

void PCF85063AMinimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t PCF85063AMinimal::_readReg(uint8_t reg) {
    uint8_t buf[1];
    _connection.read(reg, buf, 1);
    return buf[0];
}

void PCF85063AMinimal::_writeRegs(uint8_t startReg, const uint8_t* data, size_t len) {
    _connection.write(startReg, data, len);
}

void PCF85063AMinimal::_readRegs(uint8_t startReg, uint8_t* buf, size_t len) {
    _connection.read(startReg, buf, len);
}

uint8_t PCF85063AMinimal::_readControl1() {
    // SR always reads 0; mask it so a read-modify-write never resets.
    return (uint8_t)(_readReg(REG_CONTROL_1) & (uint8_t)~C1_SR);
}

uint8_t PCF85063AMinimal::_bcdToInt(uint8_t bcd) { return (uint8_t)(((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F)); }

uint8_t PCF85063AMinimal::_intToBcd(uint8_t value) { return (uint8_t)(((value / 10) << 4) | (value % 10)); }

void PCF85063AMinimal::getDatetime(DateTime& dt) {
    uint8_t buf[7];
    _readRegs(REG_SECONDS, buf, 7);
    dt.second  = _bcdToInt((uint8_t)(buf[0] & 0x7F));
    dt.minute  = _bcdToInt((uint8_t)(buf[1] & 0x7F));
    dt.hour    = _bcdToInt((uint8_t)(buf[2] & 0x3F));
    dt.day     = _bcdToInt((uint8_t)(buf[3] & 0x3F));
    dt.weekday = (uint8_t)(buf[4] & 0x07);
    dt.month   = _bcdToInt((uint8_t)(buf[5] & 0x1F));
    dt.year    = (uint16_t)(2000 + _bcdToInt(buf[6]));
}

bool PCF85063AMinimal::setDatetime(const DateTime& dt) {
    if (dt.year < 2000 || dt.year > 2099) return false;
    uint8_t ctrl1 = (uint8_t)(_readControl1() & (uint8_t)~C1_12_24);
    _writeReg(REG_CONTROL_1, (uint8_t)(ctrl1 | C1_STOP));
    uint8_t buf[7];
    buf[0] = (uint8_t)(_intToBcd(dt.second) & 0x7F);  // OS = 0
    buf[1] = _intToBcd(dt.minute);
    buf[2] = (uint8_t)(_intToBcd(dt.hour) & 0x3F);
    buf[3] = _intToBcd(dt.day);
    buf[4] = (uint8_t)(dt.weekday & 0x07);
    buf[5] = _intToBcd(dt.month);
    buf[6] = _intToBcd((uint8_t)(dt.year - 2000));
    _writeRegs(REG_SECONDS, buf, 7);
    _writeReg(REG_CONTROL_1, (uint8_t)(ctrl1 & (uint8_t)~C1_STOP));
    return true;
}

// PCF85063AFull

PCF85063AFull* PCF85063AFull::_activeInstance = nullptr;

PCF85063AFull::PCF85063AFull(RegisterConnection& connection) : PCF85063AMinimal(connection) {}

void PCF85063AFull::_updateControl1(uint8_t clearMask, uint8_t setBits) {
    uint8_t ctrl1 = _readControl1();
    _writeReg(REG_CONTROL_1, (uint8_t)((ctrl1 & (uint8_t)~clearMask) | setBits));
}

void PCF85063AFull::_updateControl2(uint8_t clearMask, uint8_t setBits) {
    // AF and TF are written 1 (unchanged) unless explicitly cleared through
    // clearMask (AND semantics: write 0 clears, 1 keeps).
    uint8_t reg = _readReg(REG_CONTROL_2);
    uint8_t value = (uint8_t)((reg & (uint8_t)~clearMask) | setBits);
    _writeReg(REG_CONTROL_2, (uint8_t)(value | (C2_FLAGS & (uint8_t)~clearMask)));
}

void PCF85063AFull::_updateTimerMode(uint8_t clearMask, uint8_t setBits) {
    uint8_t reg = _readReg(REG_TIMER_MODE);
    _writeReg(REG_TIMER_MODE, (uint8_t)((((reg & (uint8_t)~clearMask)) | setBits) & 0x1F));
}

bool PCF85063AFull::oscillatorStopped() { return (_readReg(REG_SECONDS) & SECONDS_OS) != 0; }

void PCF85063AFull::clearOscillatorStop() {
    uint8_t sec = _readReg(REG_SECONDS);
    _writeReg(REG_SECONDS, (uint8_t)(sec & 0x7F));
}

void PCF85063AFull::stopClock() { _updateControl1(0, C1_STOP); }

void PCF85063AFull::startClock() { _updateControl1(C1_STOP, 0); }

void PCF85063AFull::getAlarm(Alarm& alarm) {
    uint8_t buf[5];
    _readRegs(REG_SECOND_ALARM, buf, 5);
    alarm.second  = (buf[0] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[0] & 0x7F));
    alarm.minute  = (buf[1] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[1] & 0x7F));
    alarm.hour    = (buf[2] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[2] & 0x3F));
    alarm.day     = (buf[3] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[3] & 0x3F));
    alarm.weekday = (buf[4] & 0x80) ? ALARM_DISABLED : (uint8_t)(buf[4] & 0x07);
}

void PCF85063AFull::setAlarm(const Alarm& alarm) {
    uint8_t buf[5];
    buf[0] = alarm.second  == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.second) & 0x7F);
    buf[1] = alarm.minute  == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.minute) & 0x7F);
    buf[2] = alarm.hour    == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.hour) & 0x3F);
    buf[3] = alarm.day     == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.day) & 0x3F);
    buf[4] = alarm.weekday == ALARM_DISABLED ? 0x80 : (uint8_t)(alarm.weekday & 0x07);
    _writeRegs(REG_SECOND_ALARM, buf, 5);
    _updateControl2(C2_AF, 0);
}

void PCF85063AFull::configureTimer(uint8_t value, SourceClock sourceClock, bool pulsed) {
    _updateTimerMode(TM_TE, 0);
    _writeReg(REG_TIMER_VALUE, value);
    _updateTimerMode((uint8_t)(TM_TCF_MASK | TM_TI_TP),
                     (uint8_t)(((uint8_t)sourceClock << 3) | (pulsed ? TM_TI_TP : 0) | TM_TE));
}

void PCF85063AFull::disableTimer() {
    _updateTimerMode((uint8_t)(TM_TE | TM_TIE | TM_TCF_MASK), TM_TCF_MASK);
}

uint8_t PCF85063AFull::readTimer() {
    uint8_t prev = _readReg(REG_TIMER_VALUE);
    for (;;) {
        uint8_t cur = _readReg(REG_TIMER_VALUE);
        if (cur == prev) return cur;
        prev = cur;
    }
}

void PCF85063AFull::setMinuteInterrupt(MinuteInterrupt mode) {
    uint8_t bits = mode == MinuteInterrupt::Minute ? C2_MI : mode == MinuteInterrupt::HalfMinute ? C2_HMI : 0;
    _updateControl2((uint8_t)(C2_MI | C2_HMI), bits);
}

void PCF85063AFull::setClockOutput(uint32_t frequencyHz) {
    uint8_t cof;
    switch (frequencyHz) {
        case 32768: cof = 0; break;
        case 16384: cof = 1; break;
        case 8192:  cof = 2; break;
        case 4096:  cof = 3; break;
        case 2048:  cof = 4; break;
        case 1024:  cof = 5; break;
        case 1:     cof = 6; break;
        default:    cof = 7; break;  // disabled
    }
    _updateControl2(C2_COF, cof);
}

void PCF85063AFull::disableClockOutput() { _updateControl2(C2_COF, C2_COF); }

void PCF85063AFull::getOffset(int8_t& offset, OffsetMode& mode) {
    uint8_t raw = _readReg(REG_OFFSET);
    uint8_t v = (uint8_t)(raw & 0x7F);
    offset = (v & 0x40) ? (int8_t)(v | 0x80) : (int8_t)v;
    mode = (raw & 0x80) ? OffsetMode::Course : OffsetMode::Normal;
}

void PCF85063AFull::setOffset(int8_t offset, OffsetMode mode) {
    _writeReg(REG_OFFSET, (uint8_t)((mode == OffsetMode::Course ? 0x80 : 0x00) | ((uint8_t)offset & 0x7F)));
}

void PCF85063AFull::setOscillatorLoad(float picofarads) {
    bool high = picofarads > 10.0f;
    _updateControl1(C1_CAP_SEL, high ? C1_CAP_SEL : 0);
}

uint8_t PCF85063AFull::readRam() { return _readReg(REG_RAM); }

void PCF85063AFull::writeRam(uint8_t value) { _writeReg(REG_RAM, value); }

void PCF85063AFull::softwareReset() { _writeReg(REG_CONTROL_1, 0x58); }

void PCF85063AFull::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&PCF85063AFull::_edgeTrampoline, InputPin::kFalling);
}

void PCF85063AFull::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&PCF85063AFull::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint8_t PCF85063AFull::pollInterrupt() {
    uint8_t ctrl2 = _readReg(REG_CONTROL_2);
    uint8_t status = 0;
    if (ctrl2 & C2_AF) status |= SOURCE_ALARM;
    if (ctrl2 & C2_TF) status |= SOURCE_TIMER;
    if (status) {
        // Write 0 only to the flags seen set, 1 to the others, so a flag that
        // sets between the read and this write is not lost.
        uint8_t keep = (uint8_t)(C2_FLAGS & (uint8_t)~ctrl2);
        _writeReg(REG_CONTROL_2, (uint8_t)((ctrl2 & (uint8_t)~C2_FLAGS) | keep));
    }
    return status;
}

void PCF85063AFull::enableInterrupt(uint8_t source) { _setInterruptEnables(source, true); }

void PCF85063AFull::disableInterrupt(uint8_t source) { _setInterruptEnables(source, false); }

void PCF85063AFull::_setInterruptEnables(uint8_t source, bool enable) {
    uint8_t bits2 = (uint8_t)(((source & SOURCE_ALARM) ? C2_AIE : 0)
                              | ((source & SOURCE_MINUTE) ? C2_MI : 0)
                              | ((source & SOURCE_HALF_MINUTE) ? C2_HMI : 0));
    if (bits2) _updateControl2(enable ? 0 : bits2, enable ? bits2 : 0);
    if (source & SOURCE_TIMER) _updateTimerMode(enable ? 0 : TM_TIE, enable ? TM_TIE : 0);
    if (source & SOURCE_CORRECTION) _updateControl1(enable ? 0 : C1_CIE, enable ? C1_CIE : 0);
}

void PCF85063AFull::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void PCF85063AFull::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (status && _callback) _callback(status);
}
