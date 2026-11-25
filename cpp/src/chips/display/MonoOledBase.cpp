#include "MonoOledBase.h"

constexpr size_t MonoOledI2CBus::CHUNK;

void MonoOledI2CBus::write_commands(const uint8_t* bytes, size_t len) {
    while (len > 0) {
        size_t n = len > CHUNK - 1 ? CHUNK - 1 : len;
        _buf[0] = 0x00;
        for (size_t i = 0; i < n; i++) _buf[1 + i] = bytes[i];
        _conn->write(_buf, n + 1);
        bytes += n;
        len -= n;
    }
}

/** `bytes == nullptr` writes zeros. Every chunk repeats the 0x40 control byte; the column pointer keeps advancing. */
void MonoOledI2CBus::write_data(const uint8_t* bytes, size_t len) {
    while (len > 0) {
        size_t n = len > CHUNK - 1 ? CHUNK - 1 : len;
        _buf[0] = 0x40;
        for (size_t i = 0; i < n; i++) _buf[1 + i] = bytes ? bytes[i] : 0;
        _conn->write(_buf, n + 1);
        if (bytes) bytes += n;
        len -= n;
    }
}

MonoFramebuffer::MonoFramebuffer(uint8_t* storage, uint16_t width, uint16_t height)
    : _buf(storage), _width(width), _height(height), _pages(static_cast<uint8_t>(height / 8)) {
    clear();
}

void MonoFramebuffer::fill(bool on) {
    size_t n = size();
    for (size_t i = 0; i < n; i++) _buf[i] = on ? 0xFF : 0x00;
}

void MonoFramebuffer::set_pixel(int16_t x, int16_t y, bool on) {
    if (x < 0 || y < 0 || x >= _width || y >= _height) return;
    size_t i = static_cast<size_t>(y >> 3) * _width + x;
    if (on) _buf[i] |= static_cast<uint8_t>(1 << (y & 7));
    else    _buf[i] &= static_cast<uint8_t>(~(1 << (y & 7)));
}

bool MonoFramebuffer::get_pixel(int16_t x, int16_t y) const {
    if (x < 0 || y < 0 || x >= _width || y >= _height) return false;
    return (_buf[static_cast<size_t>(y >> 3) * _width + x] >> (y & 7)) & 1;
}

void MonoFramebuffer::draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on) {
    int16_t dx = x1 > x0 ? x1 - x0 : x0 - x1;
    int16_t dy = y1 > y0 ? y0 - y1 : y1 - y0;     // -abs(y1 - y0)
    int16_t sx = x0 < x1 ? 1 : -1;
    int16_t sy = y0 < y1 ? 1 : -1;
    int16_t err = dx + dy;
    for (;;) {
        set_pixel(x0, y0, on);
        if (x0 == x1 && y0 == y1) break;
        int16_t e2 = 2 * err;
        if (e2 >= dy) { err += dy; x0 += sx; }
        if (e2 <= dx) { err += dx; y0 += sy; }
    }
}

void MonoFramebuffer::draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on, bool fill_) {
    if (w <= 0 || h <= 0) return;
    if (fill_) {
        for (int16_t yy = y; yy < y + h; yy++)
            for (int16_t xx = x; xx < x + w; xx++) set_pixel(xx, yy, on);
        return;
    }
    draw_line(x, y, x + w - 1, y, on);
    draw_line(x, y + h - 1, x + w - 1, y + h - 1, on);
    draw_line(x, y, x, y + h - 1, on);
    draw_line(x + w - 1, y, x + w - 1, y + h - 1, on);
}

bool MonoFramebuffer::blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages) {
    if ((y % 8) != 0 || height_pages == 0) return false;
    size_t n = len / height_pages;
    for (uint8_t p = 0; p < height_pages; p++) {
        int16_t pg = y / 8 + p;
        if (pg < 0 || pg >= _pages) continue;
        for (size_t c = 0; c < n; c++) {
            int16_t xx = x + static_cast<int16_t>(c);
            if (xx >= 0 && xx < _width) _buf[static_cast<size_t>(pg) * _width + xx] = columns[p * n + c];
        }
    }
    return true;
}
