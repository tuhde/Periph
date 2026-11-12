#include "SSD1305.h"

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

constexpr uint16_t SSD1305Minimal::WIDTH;
constexpr uint16_t SSD1305Minimal::HEIGHT;
constexpr size_t   SSD1305Minimal::I2C_CHUNK;

constexpr size_t   SSD1305I2CBus::CHUNK;

void SSD1305I2CBus::write_commands(const uint8_t* bytes, size_t len) {
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
void SSD1305I2CBus::write_data(const uint8_t* bytes, size_t len) {
    while (len > 0) {
        size_t n = len > CHUNK - 1 ? CHUNK - 1 : len;
        _buf[0] = 0x40;
        for (size_t i = 0; i < n; i++) _buf[1 + i] = bytes ? bytes[i] : 0;
        _conn->write(_buf, n + 1);
        if (bytes) bytes += n;
        len -= n;
    }
}

bool SSD1305I2CBus::read_status(uint8_t& status) {
    const uint8_t control = 0x00;
    _conn->write(&control, 1);
    if (!_conn->ok()) return false;
    _conn->read(&status, 1);
    return _conn->ok();
}

bool SSD1305I2CBus::read_ram(uint8_t* buf, size_t n) {
    if (n > CHUNK - 1) n = CHUNK - 1;
    const uint8_t control = 0x40;
    _conn->write(&control, 1);
    if (!_conn->ok()) return false;
    _conn->read(_buf, n + 1);
    if (!_conn->ok()) return false;
    for (size_t i = 0; i < n; i++) buf[i] = _buf[i + 1];
    return true;
}

SSD1305Minimal::SSD1305Minimal(SSD1305Bus* bus, Connection* connection, uint16_t width, uint16_t height,
                             uint8_t column_offset)
    : _i2c(connection), _bus(bus ? *bus : static_cast<SSD1305Bus&>(_i2c)), _valid(true), _width(width),
      _height(height), _pages(static_cast<uint8_t>(height / 8)), _offset(column_offset), _mode(ADDR_PAGE),
      _adc(0), _scan(0), _fb{} {
    if (width < 1 || width > WIDTH || height < 16 || height > HEIGHT || (height % 8) != 0 ||
        static_cast<uint16_t>(column_offset) + width > WIDTH) {
        _valid = false;
        return;
    }
    _init_sequence();
}

void SSD1305Minimal::_sleep_ms(uint32_t ms) {
    delay(ms);
}

void SSD1305Minimal::_command(const uint8_t* bytes, size_t len) {
    _bus.write_commands(bytes, len);
}

void SSD1305Minimal::_command1(uint8_t a) {
    _command(&a, 1);
}

void SSD1305Minimal::_command2(uint8_t a, uint8_t b) {
    const uint8_t buf[2] = {a, b};
    _command(buf, 2);
}

void SSD1305Minimal::_address(uint8_t page, uint8_t column) {
    const uint8_t buf[3] = {static_cast<uint8_t>(CMD_PAGE | (page & 7)),
                            static_cast<uint8_t>(CMD_COL_LO | (column & 0x0F)),
                            static_cast<uint8_t>(CMD_COL_HI | ((column >> 4) & 0x0F))};
    _command(buf, 3);
}

/** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
void SSD1305Minimal::_init_sequence() {
    _bus.hardware_reset();
    _sleep_ms(1);
    const uint8_t setup[24] = {CMD_DISPLAY,
                               CMD_CLOCK, 0x70,
                               CMD_MULTIPLEX, static_cast<uint8_t>(_height - 1),
                               CMD_OFFSET, 0x00,
                               CMD_START_LINE,
                               static_cast<uint8_t>(CMD_SEG_REMAP | _adc),
                               static_cast<uint8_t>(CMD_SCAN | (_scan << 3)),
                               CMD_COM, static_cast<uint8_t>(_height > 32 ? 0x12 : 0x02),
                               CMD_CONTRAST, 0x80,
                               CMD_PRECHARGE, 0x22,
                               CMD_VCOMH, 0x34,
                               CMD_MASTER, 0x8E,
                               CMD_ALL_ON,
                               CMD_INVERT,
                               CMD_ADDR_MODE, 0x02};
    _command(setup, sizeof(setup));
    for (uint8_t page = 0; page < 8; page++) {
        _address(page, 0);
        _bus.write_data(nullptr, WIDTH);
    }
    _command1(CMD_DISPLAY | 1);
    _sleep_ms(100);
}

void SSD1305Minimal::_show_page(uint8_t page) {
    _address(page, _offset);
    _bus.write_data(&_fb[static_cast<size_t>(page) * _width], _width);
}

void SSD1305Minimal::_set_ranges(uint8_t page_first, uint8_t page_last) {
    const uint8_t buf[6] = {CMD_COL_RANGE, _offset, static_cast<uint8_t>(_offset + _width - 1),
                            CMD_PAGE_RANGE, page_first, page_last};
    _command(buf, 6);
}

void SSD1305Minimal::_show_all() {
    if (_mode == ADDR_PAGE) {
        for (uint8_t page = 0; page < _pages; page++) _show_page(page);
        return;
    }
    _set_ranges(0, static_cast<uint8_t>(_pages - 1));
    if (_mode == ADDR_HORIZONTAL) {
        _bus.write_data(_fb, static_cast<size_t>(_width) * _pages);
        return;
    }
    uint8_t tmp[WIDTH];
    size_t n = 0;
    for (uint16_t x = 0; x < _width; x++) {
        for (uint8_t p = 0; p < _pages; p++) {
            tmp[n++] = _fb[static_cast<size_t>(p) * _width + x];
            if (n == sizeof(tmp) - (sizeof(tmp) % _pages)) { _bus.write_data(tmp, n); n = 0; }
        }
    }
    if (n) _bus.write_data(tmp, n);
}

void SSD1305Minimal::clear() {
    for (size_t i = 0; i < sizeof(_fb); i++) _fb[i] = 0;
}

void SSD1305Minimal::set_pixel(int16_t x, int16_t y, bool on) {
    if (!_valid || x < 0 || y < 0 || x >= _width || y >= _height) return;
    size_t i = static_cast<size_t>(y >> 3) * _width + x;
    if (on) _fb[i] |= static_cast<uint8_t>(1 << (y & 7));
    else    _fb[i] &= static_cast<uint8_t>(~(1 << (y & 7)));
}

void SSD1305Minimal::show() {
    if (!_valid) return;
    _show_all();
}

// ---- SSD1305Full -----------------------------------------------------------------------

bool SSD1305Full::get_pixel(int16_t x, int16_t y) const {
    if (x < 0 || y < 0 || x >= _width || y >= _height) return false;
    return (_fb[static_cast<size_t>(y >> 3) * _width + x] >> (y & 7)) & 1;
}

void SSD1305Full::fill(bool on) {
    for (size_t i = 0; i < sizeof(_fb); i++) _fb[i] = on ? 0xFF : 0x00;
}

void SSD1305Full::draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on) {
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

void SSD1305Full::draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on, bool fill) {
    if (w <= 0 || h <= 0) return;
    if (fill) {
        for (int16_t yy = y; yy < y + h; yy++)
            for (int16_t xx = x; xx < x + w; xx++) set_pixel(xx, yy, on);
        return;
    }
    draw_line(x, y, x + w - 1, y, on);
    draw_line(x, y + h - 1, x + w - 1, y + h - 1, on);
    draw_line(x, y, x, y + h - 1, on);
    draw_line(x + w - 1, y, x + w - 1, y + h - 1, on);
}

bool SSD1305Full::blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages) {
    if ((y % 8) != 0 || height_pages == 0) return false;
    size_t n = len / height_pages;
    for (uint8_t p = 0; p < height_pages; p++) {
        int16_t page = y / 8 + p;
        if (page < 0 || page >= _pages) continue;
        for (size_t c = 0; c < n; c++) {
            int16_t xx = x + static_cast<int16_t>(c);
            if (xx >= 0 && xx < _width) _fb[static_cast<size_t>(page) * _width + xx] = columns[p * n + c];
        }
    }
    return true;
}

void SSD1305Full::show_page(uint8_t page) {
    if (!_valid || page >= _pages) return;
    if (_mode == ADDR_PAGE) { _show_page(page); return; }
    _set_ranges(page, page);
    _bus.write_data(&_fb[static_cast<size_t>(page) * _width], _width);
}

void SSD1305Full::set_contrast(uint8_t value) { _command2(CMD_CONTRAST, value); }

void SSD1305Full::set_display(bool on) { _command1(CMD_DISPLAY | (on ? 1 : 0)); }

void SSD1305Full::sleep() { set_display(false); }

void SSD1305Full::wake() {
    set_display(true);
    _sleep_ms(100);
}

void SSD1305Full::set_dim(bool on) { _command1(on ? CMD_DIM_ON : static_cast<uint8_t>(CMD_DISPLAY | 1)); }

void SSD1305Full::set_dim_mode(uint8_t contrast, uint8_t brightness) {
    const uint8_t buf[4] = {CMD_DIM_MODE, 0x00, contrast, brightness};
    _command(buf, 4);
}

void SSD1305Full::set_invert(bool on) { _command1(CMD_INVERT | (on ? 1 : 0)); }

void SSD1305Full::set_all_on(bool on) { _command1(CMD_ALL_ON | (on ? 1 : 0)); }

void SSD1305Full::set_flip(bool horizontal, bool vertical) {
    _adc = horizontal ? 1 : 0;
    _scan = vertical ? 1 : 0;
    _command2(static_cast<uint8_t>(CMD_SEG_REMAP | _adc), static_cast<uint8_t>(CMD_SCAN | (_scan << 3)));
}

void SSD1305Full::set_start_line(uint8_t line) { _command1(CMD_START_LINE | (line & 0x3F)); }

void SSD1305Full::set_display_offset(uint8_t offset) { _command2(CMD_OFFSET, offset & 0x3F); }

void SSD1305Full::set_multiplex(uint8_t ratio) { _command2(CMD_MULTIPLEX, (ratio - 1) & 0x3F); }

void SSD1305Full::set_clock(uint8_t divide, uint8_t osc) {
    _command2(CMD_CLOCK, static_cast<uint8_t>(((osc & 0x0F) << 4) | ((divide - 1) & 0x0F)));
}

void SSD1305Full::set_precharge(uint8_t phase1, uint8_t phase2) {
    _command2(CMD_PRECHARGE, static_cast<uint8_t>(((phase2 & 0x0F) << 4) | (phase1 & 0x0F)));
}

void SSD1305Full::set_com_config(bool alternative, bool remap) {
    _command2(CMD_COM, static_cast<uint8_t>(0x02 | (alternative ? 0x10 : 0) | (remap ? 0x20 : 0)));
}

void SSD1305Full::set_vcomh(uint8_t value) { _command2(CMD_VCOMH, value & 0x3C); }

void SSD1305Full::set_addressing_mode(AddressingMode mode) {
    _mode = mode;
    _command2(CMD_ADDR_MODE, mode);
}

static const uint8_t SSD1305_INTERVALS[7] = {0, 1, 2, 3, 4, 5, 6};   // 6, 32, 64, 128, 3, 4, 2 frames

void SSD1305Full::set_scroll_horizontal(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval, uint8_t columns) {
    const uint8_t buf[5] = {static_cast<uint8_t>(right ? 0x26 : 0x27), static_cast<uint8_t>(columns & 7),
                            static_cast<uint8_t>(start_page & 7), SSD1305_INTERVALS[interval > 6 ? 6 : interval],
                            static_cast<uint8_t>(end_page & 7)};
    _command(buf, 5);
}

void SSD1305Full::set_scroll_vertical(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval, uint8_t columns, uint8_t rows) {
    const uint8_t buf[6] = {static_cast<uint8_t>(right ? 0x29 : 0x2A), static_cast<uint8_t>(columns & 7),
                            static_cast<uint8_t>(start_page & 7), SSD1305_INTERVALS[interval > 6 ? 6 : interval],
                            static_cast<uint8_t>(end_page & 7), static_cast<uint8_t>(rows & 0x3F)};
    _command(buf, 6);
}

void SSD1305Full::set_scroll_area(uint8_t top_fixed, uint8_t scroll_rows) {
    const uint8_t buf[3] = {CMD_SCROLL_AREA, static_cast<uint8_t>(top_fixed & 0x3F), static_cast<uint8_t>(scroll_rows & 0x7F)};
    _command(buf, 3);
}

void SSD1305Full::scroll_start() { _command1(0x2F); }

void SSD1305Full::scroll_stop() {
    _command1(0x2E);
    if (_valid) _show_all();
}

void SSD1305Full::set_area_color(bool enable, bool low_power) {
    _command2(CMD_AREA_COLOR, static_cast<uint8_t>((enable ? 0x30 : 0) | (low_power ? 0x05 : 0)));
}

void SSD1305Full::set_brightness(uint8_t value) { _command2(CMD_BRIGHTNESS, value); }

void SSD1305Full::set_lut(uint8_t bank0, uint8_t a, uint8_t b, uint8_t c) {
    const uint8_t buf[5] = {CMD_LUT, static_cast<uint8_t>(bank0 & 0x3F), static_cast<uint8_t>(a & 0x3F),
                            static_cast<uint8_t>(b & 0x3F), static_cast<uint8_t>(c & 0x3F)};
    _command(buf, 5);
}

void SSD1305Full::set_bank_colors(const uint8_t* banks_1_16, const uint8_t* banks_17_32) {
    uint8_t buf[5] = {CMD_BANK_1_16, banks_1_16[0], banks_1_16[1], banks_1_16[2], banks_1_16[3]};
    _command(buf, 5);
    if (banks_17_32) {
        buf[0] = CMD_BANK_17_32;
        for (int i = 0; i < 4; i++) buf[1 + i] = banks_17_32[i];
        _command(buf, 5);
    }
}

bool SSD1305Full::read_status(uint8_t& status) {
    status = 0;
    if (!_bus.can_read()) return false;
    return _bus.read_status(status);
}

bool SSD1305Full::read_page(uint8_t page, uint8_t column, uint8_t* buf, uint8_t n) {
    if (!_bus.can_read()) return false;
    _address(page, column);
    return _bus.read_ram(buf, n);
}

void SSD1305Full::send_command(const uint8_t* bytes, size_t len) { _command(bytes, len); }
