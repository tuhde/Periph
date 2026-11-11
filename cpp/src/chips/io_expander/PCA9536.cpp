#include "PCA9536.h"

// PCA9536Minimal

PCA9536Minimal::PCA9536Minimal(RegisterConnection& connection, uint8_t addr)
    : _connection(connection), _addr(addr) {
    _write_reg(REG_OUTPUT, 0xFF);
    _write_reg(REG_POLARITY, 0x00);
    _write_reg(REG_CONFIG, 0xFF);
    _lastInput = _read_reg(REG_INPUT) & MASK;
}

uint8_t PCA9536Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void PCA9536Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void PCA9536Minimal::_write_output(uint8_t mask) {
    // Bits 7:4 are unused; keep them at their reset value (1).
    _shadow = (mask & MASK) | 0xF0;
    _write_reg(REG_OUTPUT, _shadow);
}

void PCA9536Minimal::_set_pin(uint8_t n, uint8_t value) {
    if (value) {
        _shadow |= (1 << n);
    } else {
        _shadow &= ~(1 << n);
    }
    _write_output(_shadow);
}

void PCA9536Minimal::_set_direction(uint8_t n, bool input) {
    if (input) {
        _direction |= (1 << n);
    } else {
        // Latch the output level first so the pin never glitches to a stale level.
        _write_reg(REG_OUTPUT, _shadow);
        _direction &= ~(1 << n);
    }
    _write_reg(REG_CONFIG, _direction);
}

bool PCA9536Minimal::IOExpanderPin::mode(uint8_t m) {
    if (m == INPUT_PULLUP) return false;  // the weak pull-up is fixed in hardware
    _chip._set_direction(_n, m == INPUT);
    return true;
}

void PCA9536Minimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t PCA9536Minimal::IOExpanderPin::read() {
    return (_chip._read_reg(REG_INPUT) >> _n) & 1;
}

void PCA9536Minimal::IOExpanderPin::toggle() {
    write(((_chip._shadow >> _n) & 1) ^ 1);
}

PCA9536Minimal::IOExpanderPin PCA9536Minimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 3);
}

uint8_t PCA9536Minimal::read_port(uint8_t) {
    return _read_reg(REG_INPUT) & MASK;
}

void PCA9536Minimal::write_port(uint8_t, uint8_t mask) {
    _write_output(mask);
}

// PCA9536Full

PCA9536Full::PCA9536Full(RegisterConnection& connection, uint8_t addr)
    : PCA9536Minimal(connection, addr) {
    _level = _lastInput;
}

PCA9536Full::IOExpanderPin PCA9536Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 3);
}

bool PCA9536Full::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    if (!((_full._direction >> _n) & 1)) return false;  // output pins never report changes
    _full._pinWatches[_n].handler = handler;
    _full._pinWatches[_n].trigger = trigger;
    return true;
}

void PCA9536Full::IOExpanderPin::unwatch() {
    _full._pinWatches[_n].handler = nullptr;
}

void PCA9536Full::configure_polarity(uint8_t mask) {
    _polarity = mask & MASK;
    _write_reg(REG_POLARITY, _polarity);
    _lastInput = _read_reg(REG_INPUT) & MASK;
}

uint8_t PCA9536Full::get_direction() {
    return _direction & MASK;
}

void PCA9536Full::set_direction(uint8_t mask) {
    _write_reg(REG_OUTPUT, _shadow);
    _direction = (mask & MASK) | 0xF0;
    _write_reg(REG_CONFIG, _direction);
    _lastInput = _read_reg(REG_INPUT) & MASK;
}

uint8_t PCA9536Full::read_register(uint8_t reg) {
    return _read_reg(reg & 3);
}

bool PCA9536Full::write_register(uint8_t reg, uint8_t value) {
    switch (reg) {
        case REG_OUTPUT:   _shadow = value; break;
        case REG_POLARITY: _polarity = value; break;
        case REG_CONFIG:   _direction = value; break;
        default: return false;
    }
    _write_reg(reg, value);
    return true;
}

void PCA9536Full::reset() {
    unwatchChanges();
    _write_reg(REG_OUTPUT, 0xFF);
    _write_reg(REG_POLARITY, 0x00);
    _write_reg(REG_CONFIG, 0xFF);
    _shadow = 0xFF;
    _polarity = 0x00;
    _direction = 0xFF;
    _lastInput = _read_reg(REG_INPUT) & MASK;
    _level = _lastInput;
}

void PCA9536Full::watchChanges(void (*callback)(uint8_t)) {
    _callback = callback;
}

void PCA9536Full::unwatchChanges() {
    _callback = nullptr;
}

uint8_t PCA9536Full::pollChanges() {
    uint8_t now = _read_reg(REG_INPUT) & MASK;
    uint8_t changed = (now ^ _lastInput) & _direction & MASK;
    _lastInput = now;
    _level = now;
    return changed;
}

void PCA9536Full::service() {
    uint8_t changed = pollChanges();
    if (!changed) return;

    if (_callback) _callback(changed);

    for (uint8_t n = 0; n < 4; ++n) {
        if (!(changed & (1u << n))) continue;
        PinWatch& w = _pinWatches[n];
        if (!w.handler) continue;
        uint8_t current = (_level >> n) & 1;
        bool fire = (w.trigger == InputPin::kChange) ||
                    (w.trigger == InputPin::kFalling && current == 0) ||
                    (w.trigger == InputPin::kRising  && current == 1);
        if (fire) {
            IOExpanderPin p(*this, n);
            w.handler(&p);
        }
    }
}
