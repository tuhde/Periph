#include "APA102.h"
#include <string.h>
#include <stdlib.h>

// ── APA102Minimal ────────────────────────────────────────────────────────────

APA102Minimal::APA102Minimal(Connection& connection, size_t n)
    : _connection(connection), _n(n), _buf(new uint8_t[n * 4]())
{
    // Initialize buffer with hardware brightness=31, all channels off
    for (size_t i = 0; i < _n; i++) {
        _buf[i * 4]     = 0xE0 | 31;  // brightness byte (3 high bits = 1)
        _buf[i * 4 + 1] = 0;          // blue
        _buf[i * 4 + 2] = 0;          // green
        _buf[i * 4 + 3] = 0;          // red
    }
}

APA102Minimal::~APA102Minimal() {
    delete[] _buf;
}

void APA102Minimal::fill(uint8_t r, uint8_t g, uint8_t b) {
    for (size_t i = 0; i < _n; i++) {
        _buf[i * 4]     = 0xE0 | 31;  // hardware brightness = 31 (max)
        _buf[i * 4 + 1] = b;          // blue
        _buf[i * 4 + 2] = g;          // green
        _buf[i * 4 + 3] = r;          // red
    }
    _send_frame();
}

void APA102Minimal::off() {
    fill(0, 0, 0);
}

void APA102Minimal::_send_frame() {
    uint8_t end_bytes = _end_frame_bytes(_n);
    size_t total_len = 4 + _n * 4 + end_bytes;

    uint8_t* frame = new uint8_t[total_len];
    // Start frame: 4 zero bytes
    frame[0] = frame[1] = frame[2] = frame[3] = 0x00;
    // Pixel data
    memcpy(frame + 4, _buf, _n * 4);
    // End frame: 0xFF bytes
    for (size_t i = 0; i < end_bytes; i++) {
        frame[4 + _n * 4 + i] = 0xFF;
    }

    _connection.write(frame, total_len);
    delete[] frame;
}

// ── APA102Full ───────────────────────────────────────────────────────────────

APA102Full::APA102Full(Connection& connection, size_t n)
    : APA102Minimal(connection, n), _brightness(255)
{}

void APA102Full::set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b, uint8_t pixel_brightness) {
    if (index >= _n) index = _n - 1;
    if (pixel_brightness > 31) pixel_brightness = 31;
    _buf[index * 4]     = 0xE0 | pixel_brightness;
    _buf[index * 4 + 1] = b;
    _buf[index * 4 + 2] = g;
    _buf[index * 4 + 3] = r;
}

void APA102Full::show() {
    uint8_t end_bytes = _end_frame_bytes(_n);
    size_t pixel_data_len = _n * 4;
    size_t total_len = 4 + pixel_data_len + end_bytes;

    uint8_t* frame = new uint8_t[total_len];
    frame[0] = frame[1] = frame[2] = frame[3] = 0x00;

    if (_brightness == 255) {
        memcpy(frame + 4, _buf, pixel_data_len);
    } else {
        // Scale RGB channels, leave hardware brightness byte unchanged
        for (size_t i = 0; i < _n; i++) {
            size_t base = i * 4;
            frame[4 + base]     = _buf[base];                                     // hardware brightness
            frame[4 + base + 1] = (uint8_t)((uint16_t)_buf[base + 1] * _brightness / 255u);  // blue
            frame[4 + base + 2] = (uint8_t)((uint16_t)_buf[base + 2] * _brightness / 255u);  // green
            frame[4 + base + 3] = (uint8_t)((uint16_t)_buf[base + 3] * _brightness / 255u);  // red
        }
    }

    for (size_t i = 0; i < end_bytes; i++) {
        frame[4 + pixel_data_len + i] = 0xFF;
    }

    _connection.write(frame, total_len);
    delete[] frame;
}

void APA102Full::set_pixels(const uint8_t* colors, size_t count, bool has_brightness) {
    size_t pixel_stride = has_brightness ? 4 : 3;
    size_t max_pixels = count < _n ? count : _n;
    for (size_t i = 0; i < max_pixels; i++) {
        size_t src = i * pixel_stride;
        uint8_t r = colors[src];
        uint8_t g = colors[src + 1];
        uint8_t b = colors[src + 2];
        uint8_t brightness = has_brightness ? colors[src + 3] : 31;
        if (brightness > 31) brightness = 31;
        _buf[i * 4]     = 0xE0 | brightness;
        _buf[i * 4 + 1] = b;
        _buf[i * 4 + 2] = g;
        _buf[i * 4 + 3] = r;
    }
}

uint8_t APA102Full::get_brightness() const {
    return _brightness;
}

void APA102Full::set_brightness(uint8_t value) {
    _brightness = value;
}

void APA102Full::rotate(size_t steps) {
    if (_n == 0) return;
    steps = steps % _n;
    if (steps == 0) return;
    size_t bytes = steps * 4;
    size_t total = _n * 4;
    uint8_t* tmp = new uint8_t[bytes];
    memcpy(tmp, _buf, bytes);
    memmove(_buf, _buf + bytes, total - bytes);
    memcpy(_buf + total - bytes, tmp, bytes);
    delete[] tmp;
}

void APA102Full::fill_hsv(float h, float s, float v) {
    uint8_t r, g, b;
    neopixel_hsv_to_rgb(h, s, v, r, g, b);
    fill(r, g, b);
}