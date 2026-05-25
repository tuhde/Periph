#include "PCF8574.h"

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
static PCF8574Full*           _zephyr_chip_ptr = nullptr;
static void _zephyr_gpio_cb(const struct device*, struct gpio_callback*, uint32_t);
#else
// Arduino
#include <Arduino.h>
static PCF8574Full*  _arduino_chip_ptr = nullptr;
static void          _arduino_isr();
#endif

// ============================================================
// PCF8574Minimal
// ============================================================

PCF8574Minimal::PCF8574Minimal(Transport& transport, uint8_t addr)
    : _transport(transport), _addr(addr), _shadow(0xFF)
{
    _write_port(0xFF);
}

void PCF8574Minimal::_write_port(uint8_t mask) {
    _transport.write(&mask, 1);
}

uint8_t PCF8574Minimal::_read_port() {
    uint8_t buf = 0;
    _transport.read(&buf, 1);
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

PCF8574Full::PCF8574Full(Transport& transport, uint8_t addr)
    : PCF8574Minimal(transport, addr)
{
    _prev = _read_port();
}

PCF8574Full::IOExpanderPin PCF8574Full::pin(uint8_t n) {
    return IOExpanderPin(*this, n);
}

uint8_t PCF8574Full::clear_interrupt() {
    uint8_t current = _read_port();
    uint8_t changed = current ^ _prev;
    _prev = current;
    return changed;
}

void PCF8574Full::_dispatch(PCF8574Full* chip, uint8_t changed) {
    if (chip->_callback)
        chip->_callback(changed);
}

void PCF8574Full::configure_interrupt(int int_gpio_pin, void (*callback)(uint8_t)) {
    _callback = callback;

#ifdef __linux__
    if (int_gpio_pin < 0) return;
    _linux_irq_stop = false;
    PCF8574Full* chip = this;
    _linux_irq_thread = std::thread([chip, int_gpio_pin]() {
        std::string path = "/sys/class/gpio/gpio" + std::to_string(int_gpio_pin) + "/value";
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) return;
        char buf[2];
        read(fd, buf, sizeof(buf));  // discard initial value
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
    // Application must provide the INT gpio_dt_spec and call gpio_add_callback
    // after this function. See the Zephyr example for the wiring.

#else
    // Arduino
    _arduino_chip_ptr = this;
    ::attachInterrupt(digitalPinToInterrupt(int_gpio_pin), _arduino_isr, FALLING);
#endif
}

// ---- IOExpanderPin (Full) ----

PCF8574Full::IOExpanderPin::IOExpanderPin(PCF8574Full& chip, uint8_t n)
    : PCF8574Minimal::IOExpanderPin(chip, n), _full_chip(chip), _last_state((chip._shadow >> n) & 1)
{}

void PCF8574Full::IOExpanderPin::attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode) {
    _handler   = handler;
    _irq_mode  = mode;
    _last_state = _full_chip._read_port() >> _n & 1;
    IOExpanderPin* self = this;
    uint8_t n = _n;
    _full_chip._callback = [](uint8_t changed) {
        // Dispatched from PCF8574Full::_dispatch; per-pin handlers are
        // stored on the chip and invoked here. The lambda captures nothing
        // from outer scope; the chip pointer is provided by the caller.
        (void)changed;
    };
    // Store self on chip for dispatch
    _full_chip._callback = [this](uint8_t changed) {
        if (!((changed >> _n) & 1)) return;
        uint8_t current = (_full_chip._read_port() >> _n) & 1;
        bool fire = (_irq_mode == CHANGE) ||
                    (_irq_mode == FALLING && current == 0 && _last_state == 1) ||
                    (_irq_mode == RISING  && current == 1 && _last_state == 0);
        _last_state = current;
        if (fire && _handler) _handler(this);
    };
}

void PCF8574Full::IOExpanderPin::detachInterrupt() {
    _handler  = nullptr;
    _full_chip._callback = nullptr;
}

// ---- Platform ISR stubs ----

#if defined(__linux__)
// thread-based delivery; no ISR needed

#elif defined(CONFIG_GPIO)
static void _zephyr_gpio_cb(const struct device*, struct gpio_callback*, uint32_t) {
    if (!_zephyr_chip_ptr) return;
    uint8_t changed = _zephyr_chip_ptr->clear_interrupt();
    if (changed) PCF8574Full::_dispatch(_zephyr_chip_ptr, changed);
}

#else
static void _arduino_isr() {
    if (!_arduino_chip_ptr) return;
    uint8_t changed = _arduino_chip_ptr->clear_interrupt();
    if (changed) PCF8574Full::_dispatch(_arduino_chip_ptr, changed);
}
#endif
