#include "SH1106.h"

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

constexpr uint16_t SH1106Minimal::WIDTH;
constexpr uint16_t SH1106Minimal::HEIGHT;
constexpr size_t   SH1106Minimal::I2C_CHUNK;

constexpr size_t   SH1106I2CBus::CHUNK;

void SH1106I2CBus::write_commands(const uint8_t* bytes, size_t len) {
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
void SH1106I2CBus::write_data(const uint8_t* bytes, size_t len) {
    while (len > 0) {
        size_t n = len > CHUNK - 1 ? CHUNK - 1 : len;
        _buf[0] = 0x40;
        for (size_t i = 0; i < n; i++) _buf[1 + i] = bytes ? bytes[i] : 0;
        _conn->write(_buf, n + 1);
        if (bytes) bytes += n;
        len -= n;
    }
}

bool SH1106I2CBus::read_status(uint8_t& status) {
    const uint8_t control = 0x00;
    _conn->write(&control, 1);
    if (!_conn->ok()) return false;
    _conn->read(&status, 1);
    return _conn->ok();
}

bool SH1106I2CBus::read_ram(uint8_t* buf, size_t n) {
    if (n > CHUNK - 1) n = CHUNK - 1;
    const uint8_t control = 0x40;
    _conn->write(&control, 1);
    if (!_conn->ok()) return false;
    _conn->read(_buf, n + 1);
    if (!_conn->ok()) return false;
    for (size_t i = 0; i < n; i++) buf[i] = _buf[i + 1];
    return true;
}

SH1106Minimal::SH1106Minimal(SH1106Bus* bus, Connection* connection, uint16_t width, uint16_t height,
                             uint8_t column_offset, bool internal_dcdc)
    : _i2c(connection), _bus(bus ? *bus : static_cast<SH1106Bus&>(_i2c)), _valid(true), _width(width),
      _height(height), _pages(static_cast<uint8_t>(height / 8)), _offset(column_offset), _dcdc(internal_dcdc),
      _adc(0), _scan(0), _fb{} {
    if (width < 1 || width > WIDTH || height < 8 || height > HEIGHT || (height % 8) != 0 ||
        static_cast<uint16_t>(column_offset) + width > WIDTH) {
        _valid = false;
        return;
    }
    _init_sequence();
}

void SH1106Minimal::_sleep_ms(uint32_t ms) {
    delay(ms);
}

void SH1106Minimal::_command(const uint8_t* bytes, size_t len) {
    _bus.write_commands(bytes, len);
}

void SH1106Minimal::_command1(uint8_t a) {
    _command(&a, 1);
}

void SH1106Minimal::_command2(uint8_t a, uint8_t b) {
    const uint8_t buf[2] = {a, b};
    _command(buf, 2);
}

void SH1106Minimal::_address(uint8_t page, uint8_t column) {
    const uint8_t buf[3] = {static_cast<uint8_t>(CMD_PAGE | (page & 7)),
                            static_cast<uint8_t>(CMD_COL_LO | (column & 0x0F)),
                            static_cast<uint8_t>(CMD_COL_HI | ((column >> 4) & 0x0F))};
    _command(buf, 3);
}

/** Datasheet power-on flow: setup stream, DC-DC, RAM clear, display on. */
void SH1106Minimal::_init_sequence() {
    _bus.hardware_reset();
    _sleep_ms(1);
    const uint8_t setup[21] = {CMD_DISPLAY,
                               CMD_CLOCK, 0x50,
                               CMD_MULTIPLEX, static_cast<uint8_t>(_height - 1),
                               CMD_OFFSET, 0x00,
                               CMD_START_LINE,
                               static_cast<uint8_t>(CMD_SEG_REMAP | _adc),
                               static_cast<uint8_t>(CMD_SCAN | (_scan << 3)),
                               CMD_COM, 0x12,
                               CMD_CONTRAST, 0x80,
                               CMD_PRECHARGE, 0x22,
                               CMD_VCOM, 0x35,
                               CMD_PUMP,
                               CMD_ALL_ON,
                               CMD_INVERT};
    _command(setup, sizeof(setup));
    if (_dcdc) {
        _command2(CMD_DCDC, 0x8B);
        _sleep_ms(100);
    } else {
        _command2(CMD_DCDC, 0x8A);
    }
    for (uint8_t page = 0; page < 8; page++) {
        _address(page, 0);
        _bus.write_data(nullptr, WIDTH);
    }
    _command1(CMD_DISPLAY | 1);
    _sleep_ms(_dcdc ? 150 : 50);
}

void SH1106Minimal::_show_page(uint8_t page) {
    _address(page, _offset);
    _bus.write_data(&_fb[static_cast<size_t>(page) * _width], _width);
}

void SH1106Minimal::clear() {
    for (size_t i = 0; i < sizeof(_fb); i++) _fb[i] = 0;
}

void SH1106Minimal::set_pixel(int16_t x, int16_t y, bool on) {
    if (!_valid || x < 0 || y < 0 || x >= _width || y >= _height) return;
    size_t i = static_cast<size_t>(y >> 3) * _width + x;
    if (on) _fb[i] |= static_cast<uint8_t>(1 << (y & 7));
    else    _fb[i] &= static_cast<uint8_t>(~(1 << (y & 7)));
}

void SH1106Minimal::show() {
    if (!_valid) return;
    for (uint8_t page = 0; page < _pages; page++) _show_page(page);
}

// ---- SH1106Full -----------------------------------------------------------------------

bool SH1106Full::get_pixel(int16_t x, int16_t y) const {
    if (x < 0 || y < 0 || x >= _width || y >= _height) return false;
    return (_fb[static_cast<size_t>(y >> 3) * _width + x] >> (y & 7)) & 1;
}

void SH1106Full::fill(bool on) {
    for (size_t i = 0; i < sizeof(_fb); i++) _fb[i] = on ? 0xFF : 0x00;
}

void SH1106Full::draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on) {
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

void SH1106Full::draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on, bool fill) {
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

bool SH1106Full::blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages) {
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

void SH1106Full::show_page(uint8_t page) {
    if (_valid && page < _pages) _show_page(page);
}

void SH1106Full::set_contrast(uint8_t value) { _command2(CMD_CONTRAST, value); }

void SH1106Full::set_display(bool on) { _command1(CMD_DISPLAY | (on ? 1 : 0)); }

void SH1106Full::sleep() { set_display(false); }

void SH1106Full::wake() {
    set_display(true);
    _sleep_ms(_dcdc ? 100 : 50);
}

void SH1106Full::set_invert(bool on) { _command1(CMD_INVERT | (on ? 1 : 0)); }

void SH1106Full::set_all_on(bool on) { _command1(CMD_ALL_ON | (on ? 1 : 0)); }

void SH1106Full::set_flip(bool horizontal, bool vertical) {
    _adc = horizontal ? 1 : 0;
    _scan = vertical ? 1 : 0;
    _command2(static_cast<uint8_t>(CMD_SEG_REMAP | _adc), static_cast<uint8_t>(CMD_SCAN | (_scan << 3)));
}

void SH1106Full::set_start_line(uint8_t line) { _command1(CMD_START_LINE | (line & 0x3F)); }

void SH1106Full::set_display_offset(uint8_t offset) { _command2(CMD_OFFSET, offset & 0x3F); }

void SH1106Full::set_multiplex(uint8_t ratio) { _command2(CMD_MULTIPLEX, (ratio - 1) & 0x3F); }

void SH1106Full::set_clock(uint8_t divide, uint8_t osc) {
    _command2(CMD_CLOCK, static_cast<uint8_t>(((osc & 0x0F) << 4) | ((divide - 1) & 0x0F)));
}

void SH1106Full::set_precharge(uint8_t precharge, uint8_t discharge) {
    _command2(CMD_PRECHARGE, static_cast<uint8_t>(((discharge & 0x0F) << 4) | (precharge & 0x0F)));
}

void SH1106Full::set_com_config(bool alternative) { _command2(CMD_COM, alternative ? 0x12 : 0x02); }

void SH1106Full::set_vcom_deselect(uint8_t value) { _command2(CMD_VCOM, value); }

void SH1106Full::set_pump_voltage(uint8_t level) { _command1(CMD_PUMP | (level & 3)); }

void SH1106Full::set_dcdc(bool on) {
    _dcdc = on;
    _command2(CMD_DCDC, on ? 0x8B : 0x8A);
}

bool SH1106Full::read_status(uint8_t& status) {
    status = 0;
    if (!_bus.can_read()) return false;
    return _bus.read_status(status);
}

bool SH1106Full::read_page(uint8_t page, uint8_t column, uint8_t* buf, uint8_t n) {
    if (!_bus.can_read()) return false;
    _address(page, column);
    return _bus.read_ram(buf, n);
}

void SH1106Full::send_command(const uint8_t* bytes, size_t len) { _command(bytes, len); }
