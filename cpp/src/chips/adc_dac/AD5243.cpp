#include "AD5243.h"

// AD5243Minimal

AD5243Minimal::AD5243Minimal(RegisterConnection& connection, float rAb, float rWiper)
    : _connection(connection), _rAb(rAb), _rW(rWiper) {
    _code[0] = _code[1] = MIDSCALE;
    _sd[0] = _sd[1] = false;
}

uint8_t AD5243Minimal::_clampCode(int code) {
    if (code < 0) return 0;
    if (code > 255) return 255;
    return (uint8_t)code;
}

uint8_t AD5243Minimal::_instr(uint8_t channel) const {
    return (uint8_t)((channel ? INSTR_A0 : 0) | (_sd[channel] ? INSTR_SD : 0));
}

bool AD5243Minimal::_writeCode(uint8_t channel, int code) {
    if (channel > 1) return false;
    uint8_t c = _clampCode(code);
    _connection.write(_instr(channel), &c, 1);
    _code[channel] = c;
    return true;
}

uint8_t AD5243Minimal::_readCode(uint8_t channel) {
    if (channel > 1) return 0;
    uint8_t buf[1];
    _connection.read(_instr(channel), buf, 1);
    _code[channel] = buf[0];
    return buf[0];
}

bool AD5243Minimal::setWiper(uint8_t channel, int code) { return _writeCode(channel, code); }

uint8_t AD5243Minimal::getWiper(uint8_t channel) { return _readCode(channel); }

bool AD5243Minimal::setResistance(uint8_t channel, float ohms) {
    return _writeCode(channel, (int)((ohms - 2.0f * _rW) / _rAb * 256.0f + 0.5f));
}

float AD5243Minimal::getResistance(uint8_t channel) {
    return (float)_readCode(channel) / 256.0f * _rAb + 2.0f * _rW;
}

// AD5243Full

AD5243Full::AD5243Full(RegisterConnection& connection, float rAb, float rWiper)
    : AD5243Minimal(connection, rAb, rWiper) {}

void AD5243Full::setBoth(int code1, int code2) {
    _writeCode(0, code1);
    _writeCode(1, code2);
}

bool AD5243Full::setRatio(uint8_t channel, float ratio) {
    return _writeCode(channel, (int)(ratio * 256.0f + 0.5f));
}

float AD5243Full::getRatio(uint8_t channel) { return (float)_readCode(channel) / 256.0f; }

bool AD5243Full::setResistanceWa(uint8_t channel, float ohms) {
    int steps = (int)((ohms - 2.0f * _rW) / _rAb * 256.0f + 0.5f);
    return _writeCode(channel, 256 - steps);
}

float AD5243Full::getResistanceWa(uint8_t channel) {
    return (float)(256 - _readCode(channel)) / 256.0f * _rAb + 2.0f * _rW;
}

uint8_t AD5243Full::increment(uint8_t channel, int steps) {
    if (channel > 1) return 0;
    uint8_t c = _clampCode((int)_readCode(channel) + steps);
    _writeCode(channel, c);
    return c;
}

void AD5243Full::shutdown(uint8_t channel) {
    if (channel > 1) return;
    _sd[channel] = true;
    _writeCode(channel, _code[channel]);
}

void AD5243Full::resume(uint8_t channel) {
    if (channel > 1) return;
    _sd[channel] = false;
    _writeCode(channel, _code[channel]);
}

bool AD5243Full::isShutdown(uint8_t channel) const { return channel <= 1 && _sd[channel]; }

void AD5243Full::reset() {
    _sd[0] = _sd[1] = false;
    _writeCode(0, MIDSCALE);
    _writeCode(1, MIDSCALE);
}

void AD5243Full::setRab(float rAb, float rWiper) {
    _rAb = rAb;
    if (rWiper >= 0.0f) _rW = rWiper;
}
