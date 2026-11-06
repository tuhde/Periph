#include "MCP23008.h"

// MCP23x08Minimal

MCP23x08Minimal::MCP23x08Minimal(Connection& connection, RegisterConnection* regConnection, uint8_t addr)
    : _connection(connection), _reg(regConnection), _addr(addr), _iocon(0x00) {
    if (!_reg) {
        // HAEN resets to 0, so an un-addressed device decodes address 00: send the first IOCON
        // write with the address-0 opcode (it also reaches every device on a shared CS line).
        _iocon = IOCON_HAEN;
        const uint8_t frame[3] = { 0x40, REG_IOCON, IOCON_HAEN };
        _connection.write(frame, 3);
    }
    _write_reg(REG_OLAT, 0x00);
}

uint8_t MCP23x08Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    if (_reg) {
        _reg->read(reg, buf, 1);
    } else {
        const uint8_t header[2] = { (uint8_t)(0x40 | (_addr << 1) | 1), reg };
        _connection.write_read(header, 2, buf, 1);
    }
    return buf[0];
}

void MCP23x08Minimal::_write_reg(uint8_t reg, uint8_t value) {
    if (_reg) {
        _reg->write(reg, &value, 1);
    } else {
        const uint8_t frame[3] = { (uint8_t)(0x40 | (_addr << 1)), reg, value };
        _connection.write(frame, 3);
    }
}

void MCP23x08Minimal::_write_olat(uint8_t mask) {
    _shadow = mask;
    _write_reg(REG_OLAT, mask);
}

void MCP23x08Minimal::_set_pin(uint8_t n, uint8_t value) {
    if (value) {
        _shadow |= (1 << n);
    } else {
        _shadow &= ~(1 << n);
    }
    _write_olat(_shadow);
}

void MCP23x08Minimal::_set_direction(uint8_t n, bool input) {
    if (input) {
        _direction |= (1 << n);
    } else {
        // Latch the output level first so the pin never glitches to a stale level.
        _write_reg(REG_OLAT, _shadow);
        _direction &= ~(1 << n);
    }
    _write_reg(REG_IODIR, _direction);
}

void MCP23x08Minimal::IOExpanderPin::mode(uint8_t m) {
    _chip._set_direction(_n, m == INPUT || m == INPUT_PULLUP);
}

void MCP23x08Minimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t MCP23x08Minimal::IOExpanderPin::read() {
    return (_chip._read_reg(REG_GPIO) >> _n) & 1;
}

void MCP23x08Minimal::IOExpanderPin::toggle() {
    write(((_chip._shadow >> _n) & 1) ^ 1);
}

MCP23x08Minimal::IOExpanderPin MCP23x08Minimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 7);
}

uint8_t MCP23x08Minimal::read_port(uint8_t) {
    return _read_reg(REG_GPIO);
}

void MCP23x08Minimal::write_port(uint8_t, uint8_t mask) {
    _write_olat(mask);
}

MCP23008Minimal::MCP23008Minimal(RegisterConnection& connection, uint8_t addr)
    : MCP23x08Minimal(connection, &connection, addr) {}

MCP23S08Minimal::MCP23S08Minimal(Connection& connection, uint8_t addr)
    : MCP23x08Minimal(connection, nullptr, addr & 3) {}

// MCP23x08Full

MCP23x08Full* MCP23x08Full::_activeInstance = nullptr;

MCP23x08Full::MCP23x08Full(Connection& connection, RegisterConnection* regConnection, uint8_t addr)
    : MCP23x08Minimal(connection, regConnection, addr) {}

MCP23x08Full::IOExpanderPin MCP23x08Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n & 7);
}

void MCP23x08Full::IOExpanderPin::mode(uint8_t m) {
    MCP23x08Minimal::IOExpanderPin::mode(m);
    if (m == INPUT_PULLUP) {
        _full.configure_pullup(_full._pullup | (1 << _n));
    } else if (m == INPUT) {
        _full.configure_pullup(_full._pullup & ~(1 << _n));
    }
}

bool MCP23x08Full::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    if (!((_full._direction >> _n) & 1)) return false;  // output pins never raise INT
    _full._pinWatches[_n].handler = handler;
    _full._pinWatches[_n].trigger = trigger;
    _full.enable_pin_interrupt(_full._gpinten | (1 << _n));
    if (!_full._intPinUsed && _full._connection.intPin()) {
        _full._arm(_full._connection.intPin());
    }
    return true;
}

void MCP23x08Full::IOExpanderPin::unwatch() {
    _full._pinWatches[_n].handler = nullptr;
    _full.enable_pin_interrupt(_full._gpinten & ~(1 << _n));
}

void MCP23x08Full::configure_pullup(uint8_t mask) {
    _pullup = mask;
    _write_reg(REG_GPPU, mask);
}

void MCP23x08Full::configure_polarity(uint8_t mask) {
    _write_reg(REG_IPOL, mask);
}

void MCP23x08Full::set_default_value(uint8_t mask) {
    _write_reg(REG_DEFVAL, mask);
}

void MCP23x08Full::enable_pin_interrupt(uint8_t mask) {
    _gpinten = mask;
    _write_reg(REG_GPINTEN, mask);
}

void MCP23x08Full::configure_int_pin(bool openDrain, bool activeHigh) {
    _activeHigh = activeHigh && !openDrain;
    _iocon &= ~(IOCON_ODR | IOCON_INTPOL);
    if (openDrain) _iocon |= IOCON_ODR;
    else if (activeHigh) _iocon |= IOCON_INTPOL;
    _write_reg(REG_IOCON, _iocon);
}

void MCP23x08Full::set_sequential(bool enabled) {
    _iocon &= ~IOCON_SEQOP;
    if (!enabled) _iocon |= IOCON_SEQOP;
    _write_reg(REG_IOCON, _iocon);
}

uint8_t MCP23x08Full::read_register(uint8_t reg) {
    return _read_reg(reg);
}

void MCP23x08Full::write_register(uint8_t reg, uint8_t value) {
    switch (reg) {
        case REG_IODIR:   _direction = value; break;
        case REG_OLAT:    _shadow = value; break;
        case REG_IOCON:   _iocon = value; break;
        case REG_GPPU:    _pullup = value; break;
        case REG_GPINTEN: _gpinten = value; break;
        default: break;
    }
    _write_reg(reg, value);
}

void MCP23x08Full::reset() {
    offInterrupt();
    const uint8_t zeroed[] = { REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT };
    for (uint8_t r : zeroed) _write_reg(r, 0x00);
    _write_reg(REG_IODIR, 0xFF);
    _iocon = _reg ? 0x00 : IOCON_HAEN;
    _write_reg(REG_IOCON, _iocon);
    _shadow = 0x00;
    _direction = 0xFF;
    _pullup = 0x00;
    _gpinten = 0x00;
    _activeHigh = false;
}

void MCP23x08Full::_arm(InputPin* intPin) {
    _intPinUsed = intPin;
    if (!intPin) return;
    _activeInstance = this;
    intPin->onEdge(&MCP23x08Full::_edgeTrampoline,
                   _activeHigh ? InputPin::kRising : InputPin::kFalling);
}

bool MCP23x08Full::onInterrupt(void (*callback)(uint8_t), InputPin* intPin, uint8_t mode) {
    if (!_gpinten) return false;
    _callback = callback;
    _write_reg(REG_INTCON, mode == INT_VS_DEFAULT ? _gpinten : 0x00);
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _arm(pin);
    return true;
}

void MCP23x08Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&MCP23x08Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint8_t MCP23x08Full::pollInterrupt() {
    uint8_t flags = _read_reg(REG_INTF);
    _read_reg(REG_INTCAP);  // clears the interrupt; value discarded
    return flags;
}

uint8_t MCP23x08Full::read_capture() {
    return _read_reg(REG_INTCAP);
}

void MCP23x08Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void MCP23x08Full::service() {
    uint8_t status = _read_reg(REG_INTF);
    if (!status) return;
    uint8_t captured = _read_reg(REG_INTCAP);  // clears the interrupt

    if (_callback) _callback(status);

    for (uint8_t n = 0; n < 8; ++n) {
        if (!(status & (1u << n))) continue;
        PinWatch& w = _pinWatches[n];
        if (!w.handler) continue;
        uint8_t current = (captured >> n) & 1;
        bool fire = (w.trigger == InputPin::kChange) ||
                    (w.trigger == InputPin::kFalling && current == 0) ||
                    (w.trigger == InputPin::kRising  && current == 1);
        if (fire) {
            IOExpanderPin p(*this, n);
            w.handler(&p);
        }
    }
}

MCP23008Full::MCP23008Full(RegisterConnection& connection, uint8_t addr)
    : MCP23x08Full(connection, &connection, addr) {}

MCP23S08Full::MCP23S08Full(Connection& connection, uint8_t addr)
    : MCP23x08Full(connection, nullptr, addr & 3) {}
