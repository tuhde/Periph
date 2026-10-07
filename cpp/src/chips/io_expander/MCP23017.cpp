#include "MCP23017.h"

#ifdef __ZEPHYR__
#include <zephyr/kernel.h>
static inline void delay_ms(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay_ms(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay_ms(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay_ms(unsigned long ms) { sleep_ms(ms); }
#elif !defined(ARDUINO)
#include <unistd.h>
static inline void delay_ms(unsigned long ms) { usleep(ms * 1000UL); }
#else
#include <Arduino.h>
static inline void delay_ms(unsigned long ms) { delay(ms); }
#endif

// MCP23017Minimal

MCP23017Minimal::MCP23017Minimal(RegisterConnection& connection, uint8_t addr)
    : _connection(connection), _addr(addr) {
    { uint8_t v =  0x00; _connection.write(REG_OLATA, &v, 1); }
    { uint8_t v =  0x00; _connection.write(REG_OLATB, &v, 1); }
    { uint8_t v = 0x7F; _connection.write(REG_IODIRA, &v, 1); }
    { uint8_t v = 0x7F; _connection.write(REG_IODIRB, &v, 1); }
    { uint8_t v =  0x00; _connection.write(REG_IPOLA, &v, 1); }
    { uint8_t v =  0x00; _connection.write(REG_IPOLB, &v, 1); }
    { uint8_t v =  0x00; _connection.write(REG_GPPUA, &v, 1); }
    { uint8_t v =  0x00; _connection.write(REG_GPPUB, &v, 1); }
}

uint8_t MCP23017Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[1];
    _connection.read(reg, buf, 1);
    return buf[0];
}

void MCP23017Minimal::_write_port(uint8_t port, uint8_t mask) {
    _shadow[port & 1] = mask;
    { uint8_t v = mask; _connection.write(REG_OLATA + (port & 1), &v, 1); }
}

uint8_t MCP23017Minimal::_read_port_raw(uint8_t port) {
    return _read_reg(REG_GPIOA + (port & 1));
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
    { uint8_t v = dir_mask; _chip._connection.write(REG_IODIRA + _port, &v, 1); }
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

MCP23017Full* MCP23017Full::_activeInstance = nullptr;

MCP23017Full::MCP23017Full(RegisterConnection& connection, uint8_t addr)
    : MCP23017Minimal(connection, addr) {}

MCP23017Full::IOExpanderPin::IOExpanderPin(MCP23017Full& chip, uint8_t n)
    : MCP23017Minimal::IOExpanderPin(chip, n), _full_chip(chip) {}

void MCP23017Full::IOExpanderPin::mode(uint8_t m) {
    MCP23017Minimal::IOExpanderPin::mode(m);
    if (m == INPUT_PULLUP) {
        uint8_t pull = _full_chip._pullup[_port] | (1 << _bit);
        _full_chip._pullup[_port] = pull;
        { uint8_t v = pull; _full_chip._connection.write(REG_GPPUA + _port, &v, 1); }
    }
}

void MCP23017Full::IOExpanderPin::watch(void (*handler)(IOExpanderPin*), uint8_t trigger) {
    MCP23017Full::PinWatch& w = _full_chip._pinWatches[_n];
    w.handler   = handler;
    w.trigger   = trigger;
    w.lastState = (_full_chip._read_port_raw(_port) >> _bit) & 1;
}

void MCP23017Full::IOExpanderPin::unwatch() {
    _full_chip._pinWatches[_n].handler = nullptr;
}

MCP23017Full::IOExpanderPin MCP23017Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

void MCP23017Full::configure_pullup(uint8_t port, uint8_t mask) {
    port &= 1;
    _pullup[port] = mask;
    { uint8_t v = mask; _connection.write(REG_GPPUA + port, &v, 1); }
}

void MCP23017Full::configure_polarity(uint8_t port, uint8_t mask) {
    port &= 1;
    { uint8_t v = mask; _connection.write(REG_IPOLA + port, &v, 1); }
}

void MCP23017Full::_armPort(uint8_t port, InputPin* intPin) {
    { uint8_t v = 0x00; _connection.write(REG_INTCONA  + port, &v, 1); }  // interrupt-on-change mode
    { uint8_t v = 0xFF; _connection.write(REG_GPINTENA + port, &v, 1); }
    _intPinUsed[port] = intPin;
    if (!intPin) return;
    _activeInstance = this;
    intPin->onEdge(port == 0 ? &MCP23017Full::_edgeTrampolineA : &MCP23017Full::_edgeTrampolineB,
                   InputPin::kFalling);
}

void MCP23017Full::onInterrupt(void (*callback)(uint8_t, uint8_t), InputPin* intPin, bool mirror) {
    _callbackBoth = callback;

    uint8_t iocon = _read_reg(REG_IOCON);
    if (mirror) iocon |= (1 << 6);
    { uint8_t v = iocon; _connection.write(REG_IOCON, &v, 1); }

    InputPin* pin = intPin ? intPin : _connection.intPin();
    _armPort(0, pin);
    _armPort(1, pin);
}

void MCP23017Full::onInterrupt(uint8_t port, void (*callback)(uint8_t), InputPin* intPin) {
    port &= 1;
    _callbackPort[port] = callback;
    _armPort(port, intPin ? intPin : _connection.intPin());
}

void MCP23017Full::offInterrupt() {
    offInterrupt(0);
    offInterrupt(1);
    _callbackBoth = nullptr;
}

void MCP23017Full::offInterrupt(uint8_t port) {
    port &= 1;
    { uint8_t v = 0x00; _connection.write(REG_GPINTENA + port, &v, 1); }
    if (_intPinUsed[port]) {
        _intPinUsed[port]->offEdge(port == 0 ? &MCP23017Full::_edgeTrampolineA
                                              : &MCP23017Full::_edgeTrampolineB);
        _intPinUsed[port] = nullptr;
    }
    _callbackPort[port] = nullptr;
}

void MCP23017Full::set_default_value(uint8_t port, uint8_t mask) {
    { uint8_t v = mask; _connection.write(REG_DEFVALA + (port & 1), &v, 1); }
}

uint8_t MCP23017Full::pollInterrupt(uint8_t port) {
    port &= 1;
    uint8_t flags = _read_reg(REG_INTFA + port);
    _read_reg(REG_INTCAPA + port);  // clears and re-arms the interrupt; value discarded
    return flags;
}

uint8_t MCP23017Full::read_capture(uint8_t port) {
    return _read_reg(REG_INTCAPA + (port & 1));
}

void MCP23017Full::_edgeTrampolineA() {
    if (_activeInstance) _activeInstance->_handleEdge(0);
}

void MCP23017Full::_edgeTrampolineB() {
    if (_activeInstance) _activeInstance->_handleEdge(1);
}

void MCP23017Full::_handleEdge(uint8_t port) {
    uint8_t status = pollInterrupt(port);
    if (!status) return;

    if (_callbackBoth) _callbackBoth(port, status);
    if (_callbackPort[port]) _callbackPort[port](status);

    for (uint8_t bit = 0; bit < 8; ++bit) {
        if (!(status & (1u << bit))) continue;
        uint8_t n = port * 8 + bit;
        PinWatch& w = _pinWatches[n];
        if (!w.handler) continue;

        uint8_t current = (_read_port_raw(port) >> bit) & 1;
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