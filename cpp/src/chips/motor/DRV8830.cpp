#include "DRV8830.h"

// DRV8830Minimal

DRV8830Minimal::DRV8830Minimal(Connection& connection) : _connection(connection) {}

void DRV8830Minimal::_writeReg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

uint8_t DRV8830Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1];
    _connection.write_read(&reg, 1, buf, 1);
    return buf[0];
}

uint8_t DRV8830Minimal::_voltageToVset(float voltage) {
    float mag = voltage < 0.0f ? -voltage : voltage;
    int vset = (int)(mag * 16.0f / VREF + 0.5f);
    if (vset < VSET_MIN) return 0;
    if (vset > VSET_MAX) return VSET_MAX;
    return (uint8_t)vset;
}

float DRV8830Minimal::_vsetToVoltage(uint8_t vset) {
    if (vset < VSET_MIN) return 0.0f;
    return VREF * (float)vset / 16.0f;
}

void DRV8830Minimal::drive(float voltage) {
    uint8_t vset = _voltageToVset(voltage);
    if (vset == 0) {
        _writeReg(REG_CONTROL, 0x00);
    } else if (voltage > 0.0f) {
        _writeReg(REG_CONTROL, (uint8_t)((vset << 2) | CTRL_IN1));
    } else {
        _writeReg(REG_CONTROL, (uint8_t)((vset << 2) | CTRL_IN2));
    }
}

void DRV8830Minimal::brake() { _writeReg(REG_CONTROL, (uint8_t)(CTRL_IN1 | CTRL_IN2)); }

void DRV8830Minimal::stop() { _writeReg(REG_CONTROL, 0x00); }

// DRV8830Full

DRV8830Full* DRV8830Full::_activeInstance = nullptr;

DRV8830Full::DRV8830Full(Connection& connection) : DRV8830Minimal(connection) {}

bool DRV8830Full::setOutput(uint8_t vset, bool in1, bool in2) {
    if (vset < VSET_MIN || vset > VSET_MAX) return false;
    uint8_t value = (uint8_t)((vset << 2) | (in1 ? CTRL_IN1 : 0) | (in2 ? CTRL_IN2 : 0));
    _writeReg(REG_CONTROL, value);
    return true;
}

DRV8830Full::Output DRV8830Full::readOutput() {
    uint8_t ctrl = _readReg(REG_CONTROL);
    Output out;
    out.voltage = _vsetToVoltage((uint8_t)(ctrl >> 2));
    out.direction = (Direction)(ctrl & 0x03);
    return out;
}

DRV8830Full::Fault DRV8830Full::readFault() {
    uint8_t f = _readReg(REG_FAULT);
    Fault out;
    out.fault  = (f & FAULT_FAULT) != 0;
    out.ocp    = (f & FAULT_OCP) != 0;
    out.uvlo   = (f & FAULT_UVLO) != 0;
    out.ots    = (f & FAULT_OTS) != 0;
    out.ilimit = (f & FAULT_ILIMIT) != 0;
    return out;
}

void DRV8830Full::clearFault() { _writeReg(REG_FAULT, FAULT_CLEAR); }

void DRV8830Full::onInterrupt(void (*callback)(const Fault& fault), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&DRV8830Full::_edgeTrampoline, InputPin::kFalling);
}

void DRV8830Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&DRV8830Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

DRV8830Full::Fault DRV8830Full::pollInterrupt() { return readFault(); }

void DRV8830Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void DRV8830Full::_handleEdge() {
    Fault status = pollInterrupt();
    if (status.fault && _callback) _callback(status);
}
