#include "PCA6416A.h"

// PCA6416AMinimal

PCA6416AMinimal::PCA6416AMinimal(RegisterConnection& connection, uint8_t addr)
    : _connection(connection), _addr(addr) {
    _write16(REG_OUTPUT, 0xFFFF);
    _write16(REG_POLARITY, 0x0000);
    _write16(REG_CONFIG, 0xFFFF);
    _lastInput = _read16(REG_INPUT);
}

uint8_t PCA6416AMinimal::_read_reg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void PCA6416AMinimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

// One two-byte transfer at the port-0 register returns/writes port 0 then port 1 (register-pair toggling).
uint16_t PCA6416AMinimal::_read16(uint8_t base) {
    uint8_t buf[2] = { 0, 0 };
    _connection.read(base, buf, 2);
    return (uint16_t)(buf[0] | (buf[1] << 8));
}

void PCA6416AMinimal::_write16(uint8_t base, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value & 0xFF), (uint8_t)(value >> 8) };
    _connection.write(base, buf, 2);
}

void PCA6416AMinimal::_write_output(uint16_t mask) {
    _shadow = mask;
    _write16(REG_OUTPUT, mask);
}

void PCA6416AMinimal::_set_pin(uint8_t n, uint8_t value) {
    if (value) {
        _shadow |= (uint16_t)(1u << n);
    } else {
        _shadow &= (uint16_t)~(1u << n);
    }
    _write_reg(REG_OUTPUT + (n >> 3), (uint8_t)(_shadow >> (8 * (n >> 3))));
}

void PCA6416AMinimal::_set_direction(uint8_t n, bool input) {
    uint8_t port = n >> 3;
    if (input) {
        _direction |= (uint16_t)(1u << n);
    } else {
        // Latch the output level first so the pin never glitches to a stale level.
        _write_reg(REG_OUTPUT + port, (uint8_t)(_shadow >> (8 * port)));
        _direction &= (uint16_t)~(1u << n);
    }
    _write_reg(REG_CONFIG + port, (uint8_t)(_direction >> (8 * port)));
}

bool PCA6416AMinimal::IOExpanderPin::mode(uint8_t m) {
    if (m == INPUT_PULLUP) return false;  // no pull-up resistors on this chip
    _chip._set_direction(_n, m == INPUT);
    return true;
}

void PCA6416AMinimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t PCA6416AMinimal::IOExpanderPin::read() {
    return (_chip._read_reg(REG_INPUT + (_n >> 3)) >> (_n & 7)) & 1;
}

void PCA6416AMinimal::IOExpanderPin::toggle() {
    write(((_chip._shadow >> _n) & 1) ^ 1);
}

PCA6416AMinimal::IOExpanderPin PCA6416AMinimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 15);
}

uint8_t PCA6416AMinimal::read_port(uint8_t port) {
    return _read_reg(REG_INPUT + (port & 1));
}

void PCA6416AMinimal::write_port(uint8_t port, uint8_t mask) {
    port &= 1;
    _shadow = (uint16_t)((_shadow & ~(0xFFu << (8 * port))) | ((uint16_t)mask << (8 * port)));
    _write_reg(REG_OUTPUT + port, mask);
}

// PCA6416AFull

PCA6416AFull* PCA6416AFull::_activeInstance = nullptr;

PCA6416AFull::PCA6416AFull(RegisterConnection& connection, uint8_t addr)
    : PCA6416AMinimal(connection, addr) {
    _level = _lastInput;
}

PCA6416AFull::IOExpanderPin PCA6416AFull::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 15);
}

bool PCA6416AFull::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    if (!((_full._direction >> _n) & 1)) return false;  // output pins never raise INT
    _full._pinWatches[_n].handler = handler;
    _full._pinWatches[_n].trigger = trigger;
    if (!_full._intPinUsed && _full._connection.intPin()) {
        _full._arm(_full._connection.intPin());
    }
    return true;
}

void PCA6416AFull::IOExpanderPin::unwatch() {
    _full._pinWatches[_n].handler = nullptr;
}

uint16_t PCA6416AFull::read_all() {
    return _read16(REG_INPUT);
}

void PCA6416AFull::write_all(uint16_t value) {
    _write_output(value);
}

void PCA6416AFull::configure_polarity(uint16_t mask) {
    _polarity = mask;
    _write16(REG_POLARITY, mask);
    _lastInput = _read16(REG_INPUT);
}

uint16_t PCA6416AFull::get_direction() {
    return _direction;
}

void PCA6416AFull::set_direction(uint16_t mask) {
    _write16(REG_OUTPUT, _shadow);
    _direction = mask;
    _write16(REG_CONFIG, mask);
    _lastInput = _read16(REG_INPUT);
}

uint8_t PCA6416AFull::read_register(uint8_t reg) {
    return _read_reg(reg & 7);
}

bool PCA6416AFull::write_register(uint8_t reg, uint8_t value) {
    if (reg < 2 || reg > 7) return false;  // INPUT0/1 are read-only; nothing above 0x07
    uint8_t shift = (reg & 1) ? 8 : 0;
    uint16_t keep = (uint16_t)~(0xFFu << shift);
    uint16_t bits = (uint16_t)((uint16_t)value << shift);
    switch (reg >> 1) {
        case 1: _shadow    = (_shadow    & keep) | bits; break;
        case 2: _polarity  = (_polarity  & keep) | bits; break;
        default: _direction = (_direction & keep) | bits; break;
    }
    _write_reg(reg, value);
    return true;
}

void PCA6416AFull::reset() {
    offInterrupt();
    _write16(REG_OUTPUT, 0xFFFF);
    _write16(REG_POLARITY, 0x0000);
    _write16(REG_CONFIG, 0xFFFF);
    _shadow = 0xFFFF;
    _polarity = 0x0000;
    _direction = 0xFFFF;
    _lastInput = _read16(REG_INPUT);
    _level = _lastInput;
}

void PCA6416AFull::_arm(InputPin* intPin) {
    _intPinUsed = intPin;
    if (!intPin) return;
    _activeInstance = this;
    intPin->onEdge(&PCA6416AFull::_edgeTrampoline, InputPin::kFalling);
}

void PCA6416AFull::onInterrupt(void (*callback)(uint16_t), InputPin* intPin) {
    _callback = callback;
    _arm(intPin ? intPin : _connection.intPin());
}

void PCA6416AFull::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&PCA6416AFull::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint16_t PCA6416AFull::pollInterrupt() {
    uint16_t now = _read16(REG_INPUT);
    uint16_t changed = (now ^ _lastInput) & _direction;
    _lastInput = now;
    _level = now;
    return changed;
}

void PCA6416AFull::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void PCA6416AFull::service() {
    uint16_t changed = pollInterrupt();
    if (!changed) return;

    if (_callback) _callback(changed);

    for (uint8_t n = 0; n < 16; ++n) {
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
