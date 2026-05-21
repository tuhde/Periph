#include "MCP23017.h"

#ifndef ARDUINO
#include <unistd.h>
static inline void delay_ms(unsigned long ms) { usleep(ms * 1000UL); }
#else
static inline void delay_ms(unsigned long ms) { delay(ms); }
#endif

// MCP23017Minimal

MCP23017Minimal::MCP23017Minimal(Transport& transport, uint8_t addr)
    : _transport(transport), _addr(addr) {
    _write_reg(REG_OLATA, 0x00);
    _write_reg(REG_OLATB, 0x00);
    _write_reg(REG_IODIRA, 0x7F);
    _write_reg(REG_IODIRB, 0x7F);
}

void MCP23017Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

uint8_t MCP23017Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[1];
    _transport.write_read(&reg, 1, buf, 1);
    return buf[0];
}

void MCP23017Minimal::_write_port(uint8_t port, uint8_t mask) {
    _shadow[port & 1] = mask;
    _write_reg(0x14 + (port & 1), mask);
}

uint8_t MCP23017Minimal::_read_port_raw(uint8_t port) {
    return _read_reg(0x12 + (port & 1));
}

void MCP23017Minimal::_set_pin(uint8_t n, uint8_t value) {
    uint8_t port = n >> 3;
    uint8_t bit = n & 7;
    if (value) {
        _shadow[port] |= (1 << bit);
    } else {
        _shadow[port] &= ~(1 << bit);
    }
    _write_port(port, _shadow[port]);
}

MCP23017Minimal::IOExpanderPin::IOExpanderPin(MCP23017Minimal& chip, uint8_t n)
    : _chip(chip), _n(n), _port(n >> 3), _bit(n & 7), _direction(INPUT) {}

void MCP23017Minimal::IOExpanderPin::mode(uint8_t m) {
    _direction = m;
    uint8_t dir_mask = _chip._direction[_port];
    if (m == INPUT || m == INPUT_PULLUP) {
        dir_mask |= (1 << _bit);
    } else {
        dir_mask &= ~(1 << _bit);
    }
    _chip._direction[_port] = dir_mask;
    _chip._write_reg(0x00 + _port, dir_mask);
}

void MCP23017Minimal::IOExpanderPin::write(uint8_t v) {
    _chip._set_pin(_n, v ? 1 : 0);
}

uint8_t MCP23017Minimal::IOExpanderPin::read() {
    return (_chip._read_port_raw(_port) >> _bit) & 1;
}

MCP23017Minimal::IOExpanderPin MCP23017Minimal::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint8_t MCP23017Minimal::read_port(uint8_t port) {
    return _read_port_raw(port & 1);
}

void MCP23017Minimal::write_port(uint8_t port, uint8_t mask) {
    _write_port(port & 1, mask);
}

// MCP23017Full

MCP23017Full::MCP23017Full(Transport& transport, uint8_t addr)
    : MCP23017Minimal(transport, addr) {}

MCP23017Full::IOExpanderPin::IOExpanderPin(MCP23017Full& chip, uint8_t n)
    : MCP23017Minimal::IOExpanderPin(chip, n), _full_chip(chip) {}

void MCP23017Full::IOExpanderPin::mode(uint8_t m) {
    MCP23017Minimal::IOExpanderPin::mode(m);
    if (m == INPUT_PULLUP) {
        uint8_t pull = _full_chip._pullup[_port] | (1 << _bit);
        _full_chip._pullup[_port] = pull;
        _full_chip._write_reg(0x0C + _port, pull);
    }
}

void MCP23017Full::IOExpanderPin::attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode) {
    _handler = handler;
    _irq_mode = mode;
}

void MCP23017Full::IOExpanderPin::detachInterrupt() {
    _handler = nullptr;
    _irq_mode = 0;
}

MCP23017Full::IOExpanderPin MCP23017Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

void MCP23017Full::configure_pullup(uint8_t port, uint8_t mask) {
    port &= 1;
    _pullup[port] = mask;
    _write_reg(0x0C + port, mask);
}

void MCP23017Full::configure_polarity(uint8_t port, uint8_t mask) {
    port &= 1;
    _write_reg(0x02 + port, mask);
}

void MCP23017Full::configure_interrupt(uint8_t port, int int_gpio_pin,
                                       void (*callback)(uint8_t), const char* mode,
                                       bool mirror) {
    port &= 1;
    _callback = callback;
    uint8_t intcon_val = (mode && mode[0] == 'd') ? 0xFF : 0x00;
    _write_reg(0x08 + port, intcon_val);
    _write_reg(0x04 + port, 0xFF);
    uint8_t iocon = _read_reg(REG_IOCON);
    if (mirror) iocon |= (1 << 6);
    _write_reg(REG_IOCON, iocon);
}

void MCP23017Full::set_default_value(uint8_t port, uint8_t mask) {
    _write_reg(0x06 + (port & 1), mask);
}

uint8_t MCP23017Full::clear_interrupt(uint8_t port) {
    return _read_reg(0x10 + (port & 1));
}

uint8_t MCP23017Full::read_interrupt_flags(uint8_t port) {
    return _read_reg(0x0E + (port & 1));
}

void MCP23017Full::stop_interrupt(uint8_t port) {
    _write_reg(0x04 + (port & 1), 0x00);
}

void MCP23017Full::_dispatch(MCP23017Full* chip, uint8_t changed) {
    if (chip->_callback) chip->_callback(changed);
}