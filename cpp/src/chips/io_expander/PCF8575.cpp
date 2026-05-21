#include "PCF8575.h"

#ifdef __linux__
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <thread>
#include <atomic>
#include <string>
static std::thread            _linux_irq_thread;
static std::atomic<bool>      _linux_irq_stop{false};
#elif defined(CONFIG_GPIO)
#include <zephyr/drivers/gpio.h>
static struct gpio_callback   _zephyr_cb_data;
static PCF8575Full*           _zephyr_chip_ptr = nullptr;
static void _zephyr_gpio_cb(const struct device*, struct gpio_callback*, uint32_t);
#else
#include <Arduino.h>
static PCF8575Full*  _arduino_chip_ptr = nullptr;
static void          _arduino_isr();
#endif

// ============================================================
// PCF8575Minimal
// ============================================================

PCF8575Minimal::PCF8575Minimal(Transport& transport, uint8_t addr)
    : _transport(transport), _addr(addr), _shadow{0xFF, 0xFF}
{
    _write_both();
}

void PCF8575Minimal::_write_both() {
    uint8_t buf[2] = { _shadow[0], _shadow[1] };
    _transport.write(buf, 2);
}

uint8_t PCF8575Minimal::_read_port(uint8_t port) {
    uint8_t buf[2] = {0, 0};
    _transport.read(buf, 2);
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

PCF8575Full::PCF8575Full(Transport& transport, uint8_t addr)
    : PCF8575Minimal(transport, addr)
{
    uint8_t buf[2];
    _transport.read(buf, 2);
    _prev[0] = buf[0];
    _prev[1] = buf[1];
}

PCF8575Full::IOExpanderPin PCF8575Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint16_t PCF8575Full::clear_interrupt() {
    uint8_t buf[2];
    _transport.read(buf, 2);
    uint8_t changed0 = buf[0] ^ _prev[0];
    uint8_t changed1 = buf[1] ^ _prev[1];
    _prev[0] = buf[0];
    _prev[1] = buf[1];
    return (uint16_t)changed0 | ((uint16_t)changed1 << 8);
}

void PCF8575Full::_dispatch(PCF8575Full* chip, uint8_t changed) {
    if (chip->_callback)
        chip->_callback(changed);
}

void PCF8575Full::configure_interrupt(int int_gpio_pin, void (*callback)(uint8_t)) {
    _callback = callback;

#ifdef __linux__
    if (int_gpio_pin < 0) return;
    _linux_irq_stop = false;
    PCF8575Full* chip = this;
    _linux_irq_thread = std::thread([chip, int_gpio_pin]() {
        std::string path = "/sys/class/gpio/gpio" + std::to_string(int_gpio_pin) + "/value";
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) return;
        char buf[2];
        read(fd, buf, sizeof(buf));
        struct pollfd pfd = { fd, POLLPRI | POLLERR, 0 };
        while (!_linux_irq_stop) {
            if (poll(&pfd, 1, 5) > 0) {
                lseek(fd, 0, SEEK_SET);
                read(fd, buf, sizeof(buf));
                uint8_t changed = chip->clear_interrupt();
                if (changed) _dispatch(chip, changed);
            }
        }
        close(fd);
    });
    _linux_irq_thread.detach();

#elif defined(CONFIG_GPIO)
    (void)int_gpio_pin;
    _zephyr_chip_ptr = this;

#else
    _arduino_chip_ptr = this;
    ::attachInterrupt(digitalPinToInterrupt(int_gpio_pin), _arduino_isr, FALLING);
#endif
}

// ---- IOExpanderPin (Full) ----

PCF8575Full::IOExpanderPin::IOExpanderPin(PCF8575Full& chip, uint8_t n)
    : PCF8575Minimal::IOExpanderPin(chip, n), _full_chip(chip), _last_state(0xFF)
{}

void PCF8575Full::IOExpanderPin::attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode) {
    _handler  = handler;
    _irq_mode = mode;
    uint8_t port = _n / 8;
    uint8_t bit = _n % 8;
    _last_state = (_full_chip._read_port(port) >> bit) & 1;
    _full_chip._callback = [this](uint8_t changed) {
        if (!((changed >> _n) & 1)) return;
        uint8_t port = _n / 8;
        uint8_t bit = _n % 8;
        uint8_t current = (_full_chip._read_port(port) >> bit) & 1;
        bool fire = (_irq_mode == CHANGE) ||
                    (_irq_mode == FALLING && current == 0 && _last_state == 1) ||
                    (_irq_mode == RISING  && current == 1 && _last_state == 0);
        _last_state = current;
        if (fire && _handler) _handler(this);
    };
}

void PCF8575Full::IOExpanderPin::detachInterrupt() {
    _handler = nullptr;
}

// ---- Platform ISR stubs ----

#if defined(__linux__)

#elif defined(CONFIG_GPIO)
static void _zephyr_gpio_cb(const struct device*, struct gpio_callback*, uint32_t) {
    if (!_zephyr_chip_ptr) return;
    uint8_t changed = _zephyr_chip_ptr->clear_interrupt();
    if (changed) PCF8575Full::_dispatch(_zephyr_chip_ptr, changed);
}

#else
static void _arduino_isr() {
    if (!_arduino_chip_ptr) return;
    uint8_t changed = _arduino_chip_ptr->clear_interrupt();
    if (changed) PCF8575Full::_dispatch(_arduino_chip_ptr, changed);
}
#endif