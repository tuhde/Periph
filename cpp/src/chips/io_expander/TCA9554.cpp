#include "TCA9554.h"

// TCA9554Minimal

TCA9554Minimal::TCA9554Minimal(RegisterConnection& connection, uint8_t addr)
    : _connection(connection), _addr(addr) {
    _write_reg(REG_OUTPUT, 0xFF);
    _write_reg(REG_POLARITY, 0x00);
    _write_reg(REG_CONFIG, 0xFF);
    _lastInput = _read_reg(REG_INPUT);
}

uint8_t TCA9554Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void TCA9554Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void TCA9554Minimal::_write_output(uint8_t mask) {
    _shadow = mask;
    _write_reg(REG_OUTPUT, mask);
}

void TCA9554Minimal::_set_pin(uint8_t n, uint8_t value) {
    if (value) {
        _shadow |= (1 << n);
    } else {
        _shadow &= ~(1 << n);
    }
    _write_output(_shadow);
}

void TCA9554Minimal::_set_direction(uint8_t n, bool input) {
    if (input) {
        _direction |= (1 << n);
    } else {
        // Latch the output level first so the pin never glitches to a stale level.
        _write_reg(REG_OUTPUT, _shadow);
        _direction &= ~(1 << n);
    }
    _write_reg(REG_CONFIG, _direction);
}

bool TCA9554Minimal::IOExpanderPin::mode(uint8_t m) {
    // The pull-up is fixed and always on: INPUT_PULLUP is accepted as plain INPUT.
    _chip._set_direction(_n, m != OUTPUT);
    return true;
}

void TCA9554Minimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t TCA9554Minimal::IOExpanderPin::read() {
    return (_chip._read_reg(REG_INPUT) >> _n) & 1;
}

void TCA9554Minimal::IOExpanderPin::toggle() {
    write(((_chip._shadow >> _n) & 1) ^ 1);
}

TCA9554Minimal::IOExpanderPin TCA9554Minimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 7);
}

uint8_t TCA9554Minimal::read_port(uint8_t) {
    return _read_reg(REG_INPUT);
}

void TCA9554Minimal::write_port(uint8_t, uint8_t mask) {
    _write_output(mask);
}

// TCA9554Full

TCA9554Full* TCA9554Full::_activeInstance = nullptr;

TCA9554Full::TCA9554Full(RegisterConnection& connection, uint8_t addr)
    : TCA9554Minimal(connection, addr) {
    _level = _lastInput;
}

TCA9554Full::IOExpanderPin TCA9554Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 7);
}

bool TCA9554Full::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    if (!((_full._direction >> _n) & 1)) return false;  // output pins never raise INT
    _full._pinWatches[_n].handler = handler;
    _full._pinWatches[_n].trigger = trigger;
    if (!_full._intPinUsed && _full._connection.intPin()) {
        _full._arm(_full._connection.intPin());
    }
    return true;
}

void TCA9554Full::IOExpanderPin::unwatch() {
    _full._pinWatches[_n].handler = nullptr;
}

void TCA9554Full::configure_polarity(uint8_t mask) {
    _polarity = mask;
    _write_reg(REG_POLARITY, mask);
    _lastInput = _read_reg(REG_INPUT);
}

uint8_t TCA9554Full::get_direction() {
    return _direction;
}

void TCA9554Full::set_direction(uint8_t mask) {
    _write_reg(REG_OUTPUT, _shadow);
    _direction = mask;
    _write_reg(REG_CONFIG, mask);
    _lastInput = _read_reg(REG_INPUT);
}

uint8_t TCA9554Full::read_register(uint8_t reg) {
    return _read_reg(reg & 3);
}

bool TCA9554Full::write_register(uint8_t reg, uint8_t value) {
    switch (reg) {
        case REG_OUTPUT:   _shadow = value; break;
        case REG_POLARITY: _polarity = value; break;
        case REG_CONFIG:   _direction = value; break;
        default: return false;
    }
    _write_reg(reg, value);
    return true;
}

void TCA9554Full::reset() {
    offInterrupt();
    _write_reg(REG_OUTPUT, 0xFF);
    _write_reg(REG_POLARITY, 0x00);
    _write_reg(REG_CONFIG, 0xFF);
    _shadow = 0xFF;
    _polarity = 0x00;
    _direction = 0xFF;
    _lastInput = _read_reg(REG_INPUT);
    _level = _lastInput;
}

void TCA9554Full::_arm(InputPin* intPin) {
    _intPinUsed = intPin;
    if (!intPin) return;
    _activeInstance = this;
    intPin->onEdge(&TCA9554Full::_edgeTrampoline, InputPin::kFalling);
}

void TCA9554Full::onInterrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    _arm(intPin ? intPin : _connection.intPin());
}

void TCA9554Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&TCA9554Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint8_t TCA9554Full::pollInterrupt() {
    uint8_t now = _read_reg(REG_INPUT);
    uint8_t changed = (now ^ _lastInput) & _direction;
    _lastInput = now;
    _level = now;
    return changed;
}

void TCA9554Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void TCA9554Full::service() {
    uint8_t changed = pollInterrupt();
    if (!changed) return;

    if (_callback) _callback(changed);

    for (uint8_t n = 0; n < 8; ++n) {
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
