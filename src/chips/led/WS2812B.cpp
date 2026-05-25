#include "WS2812B.h"
#include <string.h>
#include <stdlib.h>

// ── WS2812BMinimal ────────────────────────────────────────────────────────────

WS2812BMinimal::WS2812BMinimal(Transport& transport, size_t n)
    : _transport(transport), _n(n), _buf(new uint8_t[n * 3]())
{}

WS2812BMinimal::~WS2812BMinimal() {
    delete[] _buf;
}

void WS2812BMinimal::fill(uint8_t r, uint8_t g, uint8_t b) {
    for (size_t i = 0; i < _n; i++) {
        _buf[i * 3]     = g;
        _buf[i * 3 + 1] = r;
        _buf[i * 3 + 2] = b;
    }
    _send();
}

void WS2812BMinimal::off() {
    fill(0, 0, 0);
}

void WS2812BMinimal::_send() {
    _transport.write(_buf, _n * 3);
}

// ── WS2812BFull ───────────────────────────────────────────────────────────────

WS2812BFull::WS2812BFull(Transport& transport, size_t n)
    : WS2812BMinimal(transport, n), _brightness(255)
{}

void WS2812BFull::set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b) {
    if (index >= _n) index = _n - 1;
    _buf[index * 3]     = g;
    _buf[index * 3 + 1] = r;
    _buf[index * 3 + 2] = b;
}

void WS2812BFull::show() {
    if (_brightness == 255) {
        _transport.write(_buf, _n * 3);
        return;
    }
    uint8_t* scaled = new uint8_t[_n * 3];
    for (size_t i = 0; i < _n * 3; i++) {
        scaled[i] = (uint8_t)((uint16_t)_buf[i] * _brightness / 255u);
    }
    _transport.write(scaled, _n * 3);
    delete[] scaled;
}

uint8_t WS2812BFull::get_brightness() const {
    return _brightness;
}

void WS2812BFull::set_brightness(uint8_t value) {
    _brightness = value;
}

void WS2812BFull::rotate(size_t steps) {
    if (_n == 0) return;
    steps = steps % _n;
    if (steps == 0) return;
    size_t bytes = steps * 3;
    size_t total = _n * 3;
    uint8_t* tmp = new uint8_t[bytes];
    memcpy(tmp, _buf, bytes);
    memmove(_buf, _buf + bytes, total - bytes);
    memcpy(_buf + total - bytes, tmp, bytes);
    delete[] tmp;
}

void WS2812BFull::fill_hsv(float h, float s, float v) {
    uint8_t r, g, b;
    neopixel_hsv_to_rgb(h, s, v, r, g, b);
    fill(r, g, b);
}
