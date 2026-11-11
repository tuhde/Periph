#include "CAT5171.h"

// CAT5171Minimal

CAT5171Minimal::CAT5171Minimal(Connection& connection, float rAb, float rWiper)
    : _connection(connection), _rAb(rAb), _rW(rWiper), _code(MIDSCALE), _sd(false) {}

uint8_t CAT5171Minimal::_clampCode(int code) {
    if (code < 0) return 0;
    if (code > 255) return 255;
    return (uint8_t)code;
}

void CAT5171Minimal::_write(uint8_t instruction, uint8_t code) {
    uint8_t buf[2] = { instruction, code };
    _connection.write(buf, 2);
}

void CAT5171Minimal::_writeCode(int code) {
    uint8_t c = _clampCode(code);
    _write(_sd ? INSTR_SD : 0, c);
    _code = c;
}

uint8_t CAT5171Minimal::_readCode() {
    uint8_t buf[1] = { 0 };
    _connection.read(buf, 1);
    _code = buf[0];
    return buf[0];
}

void CAT5171Minimal::setWiper(int code) { _writeCode(code); }

uint8_t CAT5171Minimal::getWiper() { return _readCode(); }

void CAT5171Minimal::setResistance(float ohms) {
    _writeCode((int)((ohms - _rW) / _rAb * 256.0f + 0.5f));
}

float CAT5171Minimal::getResistance() {
    return (float)_readCode() / 256.0f * _rAb + _rW;
}

// CAT5171Full

CAT5171Full::CAT5171Full(Connection& connection, float rAb, float rWiper)
    : CAT5171Minimal(connection, rAb, rWiper) {}

void CAT5171Full::setRatio(float ratio) { _writeCode((int)(ratio * 256.0f + 0.5f)); }

float CAT5171Full::getRatio() { return (float)_readCode() / 256.0f; }

void CAT5171Full::setResistanceWa(float ohms) {
    int steps = (int)((ohms - _rW) / _rAb * 256.0f + 0.5f);
    _writeCode(256 - steps);
}

float CAT5171Full::getResistanceWa() {
    return (float)(256 - _readCode()) / 256.0f * _rAb + _rW;
}

uint8_t CAT5171Full::increment(int steps) {
    uint8_t c = _clampCode((int)_readCode() + steps);
    _writeCode(c);
    return c;
}

void CAT5171Full::midscale() {
    _write((uint8_t)(INSTR_RS | (_sd ? INSTR_SD : 0)), MIDSCALE);
    _code = MIDSCALE;
}

void CAT5171Full::shutdown() {
    _sd = true;
    _write(INSTR_SD, _code);
}

void CAT5171Full::resume() {
    _sd = false;
    _write(0, _code);
}

bool CAT5171Full::isShutdown() const { return _sd; }

void CAT5171Full::reset() {
    _sd = false;
    _write(INSTR_RS, MIDSCALE);
    _code = MIDSCALE;
}

void CAT5171Full::setRab(float rAb, float rWiper) {
    _rAb = rAb;
    if (rWiper >= 0.0f) _rW = rWiper;
}
