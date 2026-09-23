#include "PCF8523.h"

// PCF8523Minimal

PCF8523Minimal::PCF8523Minimal(Connection& connection) : _connection(connection) {
    _readReg(REG_CONTROL_1);  // presence check (no identity register)
    // Battery switch-over standard mode, battery-low detection enabled
    // (PM=000); BSF/BSIE/BLIE left at their POR-default 0.
    _writeReg(REG_CONTROL_3, 0x00);
}

void PCF8523Minimal::_writeReg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

uint8_t PCF8523Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1];
    _connection.write_read(&reg, 1, buf, 1);
    return buf[0];
}

void PCF8523Minimal::_writeRegs(uint8_t startReg, const uint8_t* data, size_t len) {
    uint8_t buf[8];
    buf[0] = startReg;
    for (size_t i = 0; i < len; ++i) buf[1 + i] = data[i];
    _connection.write(buf, len + 1);
}

void PCF8523Minimal::_readRegs(uint8_t startReg, uint8_t* buf, size_t len) {
    _connection.write_read(&startReg, 1, buf, len);
}

uint8_t PCF8523Minimal::_readControl1() {
    // T must always be written 0 and SR always reads 0; mask both so a
    // read-modify-write never triggers a reset.
    return (uint8_t)(_readReg(REG_CONTROL_1) & (uint8_t)~(C1_T | C1_SR));
}

uint8_t PCF8523Minimal::_bcdToInt(uint8_t bcd) { return (uint8_t)(((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F)); }

uint8_t PCF8523Minimal::_intToBcd(uint8_t value) { return (uint8_t)(((value / 10) << 4) | (value % 10)); }

void PCF8523Minimal::getDatetime(DateTime& dt) {
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

void PCF8523Minimal::setDatetime(const DateTime& dt) {
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
}

// PCF8523Full

PCF8523Full* PCF8523Full::_activeInstance = nullptr;

// TBW[2:0] -> low-pulse width in ms (datasheet Table 36; not uniformly spaced).
static const float kTbwWidthsMs[8] = { 46.875f, 62.5f, 78.125f, 93.75f, 125.0f, 156.25f, 187.5f, 218.75f };

PCF8523Full::PCF8523Full(Connection& connection) : PCF8523Minimal(connection) {}

void PCF8523Full::_updateTmrClkout(uint8_t clearMask, uint8_t setBits) {
    uint8_t reg = _readReg(REG_TMR_CLKOUT_CTRL);
    _writeReg(REG_TMR_CLKOUT_CTRL, (uint8_t)((reg & (uint8_t)~clearMask) | setBits));
}

void PCF8523Full::_writeControl2(uint8_t enables) {
    // Re-supply the enable bits; write 1 to every clearable flag so none is
    // cleared by accident (AND semantics). WTAF is read-only.
    _writeReg(REG_CONTROL_2, (uint8_t)(C2_CLEARABLE | (enables & C2_ENABLES)));
}

void PCF8523Full::_writeControl3(uint8_t value) {
    // Write 1 to BSF so it is left unchanged; BLF is read-only.
    _writeReg(REG_CONTROL_3, (uint8_t)((value & (C3_PM_MASK | C3_BSIE | C3_BLIE)) | C3_BSF));
}

void PCF8523Full::getAlarm(Alarm& alarm) {
    uint8_t buf[4];
    _readRegs(REG_MINUTE_ALARM, buf, 4);
    alarm.minute  = (buf[0] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[0] & 0x7F));
    alarm.hour    = (buf[1] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[1] & 0x3F));
    alarm.day     = (buf[2] & 0x80) ? ALARM_DISABLED : _bcdToInt((uint8_t)(buf[2] & 0x3F));
    alarm.weekday = (buf[3] & 0x80) ? ALARM_DISABLED : (uint8_t)(buf[3] & 0x07);
}

void PCF8523Full::setAlarm(const Alarm& alarm) {
    uint8_t buf[4];
    buf[0] = alarm.minute  == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.minute) & 0x7F);
    buf[1] = alarm.hour    == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.hour) & 0x3F);
    buf[2] = alarm.day     == ALARM_DISABLED ? 0x80 : (uint8_t)(_intToBcd(alarm.day) & 0x3F);
    buf[3] = alarm.weekday == ALARM_DISABLED ? 0x80 : (uint8_t)(alarm.weekday & 0x07);
    _writeRegs(REG_MINUTE_ALARM, buf, 4);
}

void PCF8523Full::configureTimerA(TimerAMode mode, uint8_t value, SourceClock sourceClock, bool pulsed) {
    uint8_t tac = mode == TimerAMode::Watchdog ? TMR_TAC_WATCHDOG : TMR_TAC_COUNTDOWN;
    _writeReg(REG_TMR_A_FREQ_CTRL, (uint8_t)sourceClock);
    _writeReg(REG_TMR_A_REG, value);
    _updateTmrClkout((uint8_t)(TMR_TAM | TMR_TAC_MASK), (uint8_t)((pulsed ? TMR_TAM : 0) | tac));
}

void PCF8523Full::disableTimerA() { _updateTmrClkout(TMR_TAC_MASK, 0); }

uint8_t PCF8523Full::readTimerA() { return _readReg(REG_TMR_A_REG); }

void PCF8523Full::configureTimerB(uint8_t value, SourceClock sourceClock, float pulseWidthMs, bool pulsed) {
    uint8_t tbw = 0;
    for (uint8_t i = 1; i < 8; ++i) {
        float d = kTbwWidthsMs[i] - pulseWidthMs;
        float best = kTbwWidthsMs[tbw] - pulseWidthMs;
        if ((d < 0 ? -d : d) < (best < 0 ? -best : best)) tbw = i;
    }
    _writeReg(REG_TMR_B_FREQ_CTRL, (uint8_t)((tbw << 4) | (uint8_t)sourceClock));
    _writeReg(REG_TMR_B_REG, value);
    _updateTmrClkout((uint8_t)(TMR_TBM | TMR_TBC), (uint8_t)((pulsed ? TMR_TBM : 0) | TMR_TBC));
}

void PCF8523Full::disableTimerB() { _updateTmrClkout(TMR_TBC, 0); }

uint8_t PCF8523Full::readTimerB() { return _readReg(REG_TMR_B_REG); }

void PCF8523Full::setClockOutput(uint32_t frequencyHz) {
    uint8_t cof;
    switch (frequencyHz) {
        case 32768: cof = 0; break;
        case 16384: cof = 1; break;
        case 8192:  cof = 2; break;
        case 4096:  cof = 3; break;
        case 1024:  cof = 4; break;
        case 32:    cof = 5; break;
        case 1:     cof = 6; break;
        default:    cof = 7; break;  // disabled
    }
    _updateTmrClkout(TMR_COF_MASK, (uint8_t)(cof << 3));
}

void PCF8523Full::disableClockOutput() { _updateTmrClkout(TMR_COF_MASK, TMR_COF_MASK); }

void PCF8523Full::getOffset(int8_t& offset, OffsetMode& mode) {
    uint8_t raw = _readReg(REG_OFFSET);
    uint8_t v = (uint8_t)(raw & 0x7F);
    offset = (v & 0x40) ? (int8_t)(v | 0x80) : (int8_t)v;
    mode = (raw & 0x80) ? OffsetMode::EveryMinute : OffsetMode::EveryTwoHours;
}

void PCF8523Full::setOffset(int8_t offset, OffsetMode mode) {
    _writeReg(REG_OFFSET, (uint8_t)((mode == OffsetMode::EveryMinute ? 0x80 : 0x00) | ((uint8_t)offset & 0x7F)));
}

void PCF8523Full::configureBatteryBackup(BatteryMode mode, bool lowDetection) {
    uint8_t pm;
    switch (mode) {
        case BatteryMode::Standard: pm = lowDetection ? 0x00 : 0x04; break;
        case BatteryMode::Direct:   pm = lowDetection ? 0x01 : 0x05; break;
        default:                    pm = lowDetection ? 0x02 : 0x07; break;
    }
    uint8_t ctrl3 = _readReg(REG_CONTROL_3);
    _writeControl3((uint8_t)((ctrl3 & (uint8_t)~C3_PM_MASK) | (pm << 5)));
}

bool PCF8523Full::isBatterySwitchedOver() { return (_readReg(REG_CONTROL_3) & C3_BSF) != 0; }

void PCF8523Full::clearBatterySwitchover() {
    uint8_t ctrl3 = _readReg(REG_CONTROL_3);
    _writeReg(REG_CONTROL_3, (uint8_t)(ctrl3 & (C3_PM_MASK | C3_BSIE | C3_BLIE)));
}

bool PCF8523Full::isBatteryLow() { return (_readReg(REG_CONTROL_3) & C3_BLF) != 0; }

bool PCF8523Full::oscillatorStopped() { return (_readReg(REG_SECONDS) & SECONDS_OS) != 0; }

void PCF8523Full::softwareReset() { _writeReg(REG_CONTROL_1, 0x58); }

void PCF8523Full::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&PCF8523Full::_edgeTrampoline, InputPin::kFalling);
}

void PCF8523Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&PCF8523Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint8_t PCF8523Full::pollInterrupt() {
    uint8_t buf[2];
    _readRegs(REG_CONTROL_2, buf, 2);
    uint8_t ctrl2 = buf[0], ctrl3 = buf[1];
    uint8_t status = 0;
    if (ctrl2 & C2_SF)               status |= SOURCE_SECOND;
    if (ctrl2 & (C2_CTAF | C2_WTAF)) status |= SOURCE_TIMER_A;
    if (ctrl2 & C2_CTBF)             status |= SOURCE_TIMER_B;
    if (ctrl2 & C2_AF)               status |= SOURCE_ALARM;
    if (ctrl3 & C3_BSF)              status |= SOURCE_BATTERY_SWITCH;
    if (ctrl3 & C3_BLF)              status |= SOURCE_BATTERY_LOW;
    // Write 0 only to the flags seen set, 1 to the rest, so a flag that sets
    // between the read and this write is not lost.
    if (ctrl2 & C2_CLEARABLE)
        _writeReg(REG_CONTROL_2, (uint8_t)((C2_CLEARABLE & (uint8_t)~ctrl2) | (ctrl2 & C2_ENABLES)));
    if (ctrl3 & C3_BSF)
        _writeReg(REG_CONTROL_3, (uint8_t)(ctrl3 & (C3_PM_MASK | C3_BSIE | C3_BLIE)));
    return status;
}

void PCF8523Full::enableInterrupt(uint8_t source) { _setInterruptEnables(source, true); }

void PCF8523Full::disableInterrupt(uint8_t source) { _setInterruptEnables(source, false); }

void PCF8523Full::_setInterruptEnables(uint8_t source, bool enable) {
    if (source & (SOURCE_SECOND | SOURCE_ALARM)) {
        uint8_t bits = (uint8_t)(((source & SOURCE_SECOND) ? C1_SIE : 0) | ((source & SOURCE_ALARM) ? C1_AIE : 0));
        uint8_t ctrl1 = _readControl1();
        ctrl1 = enable ? (uint8_t)(ctrl1 | bits) : (uint8_t)(ctrl1 & (uint8_t)~bits);
        _writeReg(REG_CONTROL_1, ctrl1);
    }
    if (source & (SOURCE_TIMER_A | SOURCE_TIMER_B)) {
        uint8_t bits = 0;
        if (source & SOURCE_TIMER_A) {
            if (enable) {
                uint8_t tac = (uint8_t)(_readReg(REG_TMR_CLKOUT_CTRL) & TMR_TAC_MASK);
                bits |= tac == TMR_TAC_WATCHDOG ? C2_WTAIE : C2_CTAIE;
            } else {
                bits |= C2_WTAIE | C2_CTAIE;
            }
        }
        if (source & SOURCE_TIMER_B) bits |= C2_CTBIE;
        uint8_t enables = (uint8_t)(_readReg(REG_CONTROL_2) & C2_ENABLES);
        enables = enable ? (uint8_t)(enables | bits) : (uint8_t)(enables & (uint8_t)~bits);
        _writeControl2(enables);
    }
    if (source & (SOURCE_BATTERY_SWITCH | SOURCE_BATTERY_LOW)) {
        uint8_t bits = (uint8_t)(((source & SOURCE_BATTERY_SWITCH) ? C3_BSIE : 0) | ((source & SOURCE_BATTERY_LOW) ? C3_BLIE : 0));
        uint8_t ctrl3 = _readReg(REG_CONTROL_3);
        ctrl3 = enable ? (uint8_t)(ctrl3 | bits) : (uint8_t)(ctrl3 & (uint8_t)~bits);
        _writeControl3(ctrl3);
    }
}

void PCF8523Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void PCF8523Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (status && _callback) _callback(status);
}
