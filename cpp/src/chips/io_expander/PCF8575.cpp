#include "PCF8575.h"

// ============================================================
// PCF8575Minimal
// ============================================================

PCF8575Minimal::PCF8575Minimal(Connection& connection, uint8_t addr)
    : _connection(connection), _addr(addr), _shadow{0xFF, 0xFF}
{
    _write_both();
}

void PCF8575Minimal::_write_both() {
    uint8_t buf[2] = { _shadow[0], _shadow[1] };
    _connection.write(buf, 2);
}

uint8_t PCF8575Minimal::_read_port(uint8_t port) {
    uint8_t buf[2] = {0, 0};
    _connection.read(buf, 2);
    return buf[port];
}

void PCF8575Minimal::_set_pin(uint8_t n, uint8_t value) {
    uint8_t port_idx = n / 8;
    uint8_t bit = n % 8;
    if (value)
        _shadow[port_idx] |= (1u << bit);
    else
        _shadow[port_idx] &= ~(1u << bit);
    _write_both();
}

PCF8575Minimal::IOExpanderPin PCF8575Minimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint8_t PCF8575Minimal::read_port(uint8_t port) {
    return _read_port(port);
}

void PCF8575Minimal::write_port(uint8_t port, uint8_t mask) {
    _shadow[port] = mask;
    _write_both();
}

// ---- IOExpanderPin (Minimal) ----

PCF8575Minimal::IOExpanderPin::IOExpanderPin(PCF8575Minimal& chip, uint8_t n)
    : _chip(chip), _n(n) {}

void PCF8575Minimal::IOExpanderPin::mode(uint8_t m) {
    _chip._set_pin(_n, (m != OUTPUT) ? 1 : 0);
}

void PCF8575Minimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t PCF8575Minimal::IOExpanderPin::read() {
    uint8_t port = _n / 8;
    uint8_t bit = _n % 8;
    return (_chip._read_port(port) >> bit) & 1;
}

// ============================================================
// PCF8575Full
// ============================================================

PCF8575Full* PCF8575Full::_activeInstance = nullptr;

PCF8575Full::PCF8575Full(Connection& connection, uint8_t addr)
    : PCF8575Minimal(connection, addr)
{
    uint8_t buf[2];
    _connection.read(buf, 2);
    _prev[0] = buf[0];
    _prev[1] = buf[1];
}

PCF8575Full::IOExpanderPin PCF8575Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint16_t PCF8575Full::pollInterrupt() {
    uint8_t buf[2];
    _connection.read(buf, 2);
    uint8_t changed0 = buf[0] ^ _prev[0];
    uint8_t changed1 = buf[1] ^ _prev[1];
    _prev[0] = buf[0];
    _prev[1] = buf[1];
    return (uint16_t)changed0 | ((uint16_t)changed1 << 8);
}

void PCF8575Full::onInterrupt(void (*callback)(uint16_t)) {
    _callback = callback;
    if (!_connection.intPin()) return;
    _activeInstance = this;
    _connection.intPin()->onEdge(&PCF8575Full::_edgeTrampoline, InputPin::kFalling);
}

void PCF8575Full::offInterrupt() {
    _callback = nullptr;
    if (_connection.intPin()) _connection.intPin()->offEdge(&PCF8575Full::_edgeTrampoline);
    if (_activeInstance == this) _activeInstance = nullptr;
}

void PCF8575Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void PCF8575Full::_handleEdge() {
    uint16_t changed = pollInterrupt();
    if (!changed) return;

    if (_callback) _callback(changed);

    for (uint8_t n = 0; n < 16; ++n) {
        if (!(changed & (1u << n))) continue;
        PinWatch& w = _pinWatches[n];
        if (!w.handler) continue;

        uint8_t port = n / 8;
        uint8_t bit  = n % 8;
        uint8_t current = (_prev[port] >> bit) & 1;
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

PCF8575Full::IOExpanderPin::IOExpanderPin(PCF8575Full& chip, uint8_t n)
    : PCF8575Minimal::IOExpanderPin(chip, n), _full_chip(chip)
{}

void PCF8575Full::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    PCF8575Full::PinWatch& w = _full_chip._pinWatches[_n];
    w.handler   = handler;
    w.trigger   = trigger;
    uint8_t port = _n / 8;
    uint8_t bit  = _n % 8;
    w.lastState = (_full_chip._read_port(port) >> bit) & 1;
}

void PCF8575Full::IOExpanderPin::unwatch() {
    _full_chip._pinWatches[_n].handler = nullptr;
}
