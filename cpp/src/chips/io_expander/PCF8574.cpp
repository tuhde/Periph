#include "PCF8574.h"

// ============================================================
// PCF8574Minimal
// ============================================================

PCF8574Minimal::PCF8574Minimal(Connection& connection, uint8_t addr)
    : _connection(connection), _addr(addr), _shadow(0xFF)
{
    _write_port(0xFF);
}

void PCF8574Minimal::_write_port(uint8_t mask) {
    _connection.write(&mask, 1);
}

uint8_t PCF8574Minimal::_read_port() {
    uint8_t buf = 0;
    _connection.read(&buf, 1);
    return buf;
}

void PCF8574Minimal::_set_pin(uint8_t n, uint8_t value) {
    if (value)
        _shadow |= (1u << n);
    else
        _shadow &= ~(1u << n);
    _write_port(_shadow);
}

PCF8574Minimal::IOExpanderPin PCF8574Minimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint8_t PCF8574Minimal::read_port(uint8_t /*port*/) {
    return _read_port();
}

void PCF8574Minimal::write_port(uint8_t /*port*/, uint8_t mask) {
    _shadow = mask;
    _write_port(mask);
}

// ---- IOExpanderPin (Minimal) ----

PCF8574Minimal::IOExpanderPin::IOExpanderPin(PCF8574Minimal& chip, uint8_t n)
    : _chip(chip), _n(n) {}

void PCF8574Minimal::IOExpanderPin::mode(uint8_t m) {
    // INPUT or INPUT_PULLUP → release pin high (input mode)
    // OUTPUT → drive low initially (safe default)
    _chip._set_pin(_n, (m != OUTPUT) ? 1 : 0);
}

void PCF8574Minimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t PCF8574Minimal::IOExpanderPin::read() {
    return (_chip._read_port() >> _n) & 1;
}

// ============================================================
// PCF8574Full
// ============================================================

PCF8574Full* PCF8574Full::_activeInstance = nullptr;

PCF8574Full::PCF8574Full(Connection& connection, uint8_t addr)
    : PCF8574Minimal(connection, addr)
{
    _prev = _read_port();
}

PCF8574Full::IOExpanderPin PCF8574Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint8_t PCF8574Full::pollInterrupt() {
    uint8_t current = _read_port();
    uint8_t changed = current ^ _prev;
    _prev = current;
    return changed;
}

void PCF8574Full::onInterrupt(void (*callback)(uint8_t)) {
    _callback = callback;
    if (!_connection.intPin()) return;
    _activeInstance = this;
    _connection.intPin()->onEdge(&PCF8574Full::_edgeTrampoline, InputPin::kFalling);
}

void PCF8574Full::offInterrupt() {
    _callback = nullptr;
    if (_connection.intPin()) _connection.intPin()->offEdge(&PCF8574Full::_edgeTrampoline);
    if (_activeInstance == this) _activeInstance = nullptr;
}

void PCF8574Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void PCF8574Full::_handleEdge() {
    uint8_t changed = pollInterrupt();
    if (!changed) return;

    if (_callback) _callback(changed);

    for (uint8_t n = 0; n < 8; ++n) {
        if (!(changed & (1u << n))) continue;
        PinWatch& w = _pinWatches[n];
        if (!w.handler) continue;

        uint8_t current = (_prev >> n) & 1;
        bool fire = (w.trigger == InputPin::kChange) ||
                    (w.trigger == InputPin::kFalling && current == 0 && w.lastState == 1) ||
                    (w.trigger == InputPin::kRising  && current == 1 && w.lastState == 0);
        w.lastState = current;

        if (fire) {
            IOExpanderPin p(*this, n);
            w.handler(&p);
        }
    }
}

// ---- IOExpanderPin (Full) ----

PCF8574Full::IOExpanderPin::IOExpanderPin(PCF8574Full& chip, uint8_t n)
    : PCF8574Minimal::IOExpanderPin(chip, n), _full_chip(chip)
{}

void PCF8574Full::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    PCF8574Full::PinWatch& w = _full_chip._pinWatches[_n];
    w.handler   = handler;
    w.trigger   = trigger;
    w.lastState = (_full_chip._read_port() >> _n) & 1;
}

void PCF8574Full::IOExpanderPin::unwatch() {
    _full_chip._pinWatches[_n].handler = nullptr;
}
