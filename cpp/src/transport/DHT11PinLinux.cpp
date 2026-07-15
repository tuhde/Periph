#ifdef __linux__
#include "DHT11PinLinux.h"
#include <gpiod.h>

DHT11PinLinux::DHT11PinLinux(struct gpiod_chip* chip, unsigned int line)
    : _chip(chip), _line(nullptr), _is_output(false)
{
    _line = gpiod_chip_get_line(_chip, line);
    if (!_line) return;
    gpiod_line_request_input(_line, "periph-dht11");
    _is_output = false;
}

DHT11PinLinux::~DHT11PinLinux() {
    close();
}

void DHT11PinLinux::set_output() {
    if (_is_output) return;
    gpiod_line_release(_line);
    gpiod_line_request_output(_line, "periph-dht11", 0);
    _is_output = true;
}

void DHT11PinLinux::set_input() {
    if (!_is_output) return;
    gpiod_line_release(_line);
    gpiod_line_request_input(_line, "periph-dht11");
    _is_output = false;
}

void DHT11PinLinux::drive(bool high) {
    if (!_is_output) set_output();
    gpiod_line_set_value(_line, high ? 1 : 0);
}

bool DHT11PinLinux::read() {
    if (_is_output) set_input();
    return gpiod_line_get_value(_line) == 1;
}

void DHT11PinLinux::close() {
    if (_line) {
        gpiod_line_release(_line);
        _line = nullptr;
    }
}
#endif
