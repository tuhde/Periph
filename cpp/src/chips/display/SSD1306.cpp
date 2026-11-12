#include "SSD1306.h"

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

constexpr uint16_t SSD1306Minimal::WIDTH;
constexpr uint16_t SSD1306Minimal::HEIGHT;
constexpr size_t   SSD1306Minimal::I2C_CHUNK;

SSD1306Minimal::SSD1306Minimal(MonoOledBus* bus, Connection* connection, uint16_t width, uint16_t height,
                               bool external_vcc)
    : _i2c(connection), _bus(bus ? *bus : static_cast<MonoOledBus&>(_i2c)), _storage{},
      _canvas(_storage, width < 1 || width > WIDTH || height > HEIGHT ? WIDTH : width,
              height < 16 || height > HEIGHT ? HEIGHT : static_cast<uint16_t>(height - height % 8)),
      _valid(true), _width(width), _height(height), _pages(static_cast<uint8_t>(height / 8)),
      _external_vcc(external_vcc), _mode(ADDR_PAGE), _scrolling(false), _adc(0), _scan(0) {
    if (width < 1 || width > WIDTH || height < 16 || height > HEIGHT || (height % 8) != 0) {
        _valid = false;
        return;
    }
    _init_sequence();
}

void SSD1306Minimal::_sleep_ms(uint32_t ms) {
    delay(ms);
}

void SSD1306Minimal::_command(const uint8_t* bytes, size_t len) {
    _bus.write_commands(bytes, len);
}

void SSD1306Minimal::_command1(uint8_t a) {
    _command(&a, 1);
}

void SSD1306Minimal::_command2(uint8_t a, uint8_t b) {
    const uint8_t buf[2] = {a, b};
    _command(buf, 2);
}

void SSD1306Minimal::_address(uint8_t page) {
    const uint8_t buf[3] = {static_cast<uint8_t>(CMD_PAGE | (page & 7)), 0x00, 0x10};
    _command(buf, 3);
}

/** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
void SSD1306Minimal::_init_sequence() {
    _bus.hardware_reset();
    _sleep_ms(1);
    const uint8_t setup[24] = {CMD_DISPLAY,
                               CMD_CLOCK, 0x80,
                               CMD_MULTIPLEX, static_cast<uint8_t>(_height - 1),
                               CMD_OFFSET, 0x00,
                               CMD_START_LINE,
                               CMD_CHARGE_PUMP, static_cast<uint8_t>(_external_vcc ? 0x10 : 0x14),
                               CMD_ADDR_MODE, 0x02,
                               static_cast<uint8_t>(CMD_SEG_REMAP | _adc),
                               static_cast<uint8_t>(CMD_SCAN | (_scan << 3)),
                               CMD_COM, static_cast<uint8_t>(_height > 32 ? 0x12 : 0x02),
                               CMD_CONTRAST, 0x7F,
                               CMD_PRECHARGE, 0x22,
                               CMD_VCOMH, 0x20,
                               CMD_ALL_ON,
                               CMD_INVERT};
    _command(setup, sizeof(setup));
    for (uint8_t page = 0; page < 8; page++) {
        _address(page);
        _bus.write_data(nullptr, WIDTH);
    }
    _command1(CMD_DISPLAY | 1);
    _sleep_ms(100);
}

void SSD1306Minimal::_show_page(uint8_t page) {
    _address(page);
    _bus.write_data(_canvas.page(page), _width);
}

void SSD1306Minimal::_set_ranges(uint8_t page_first, uint8_t page_last) {
    const uint8_t buf[6] = {CMD_COL_RANGE, 0, static_cast<uint8_t>(_width - 1), CMD_PAGE_RANGE, page_first, page_last};
    _command(buf, 6);
}

bool SSD1306Minimal::_show_all() {
    if (!_valid || _scrolling) return false;
    if (_mode == ADDR_PAGE) {
        for (uint8_t page = 0; page < _pages; page++) _show_page(page);
        return true;
    }
    _set_ranges(0, static_cast<uint8_t>(_pages - 1));
    if (_mode == ADDR_HORIZONTAL) {
        _bus.write_data(_canvas.data(), _canvas.size());
        return true;
    }
    uint8_t tmp[WIDTH];
    size_t n = 0;
    const size_t full = sizeof(tmp) - (sizeof(tmp) % _pages);
    for (uint16_t x = 0; x < _width; x++) {
        for (uint8_t p = 0; p < _pages; p++) {
            tmp[n++] = _canvas.data()[static_cast<size_t>(p) * _width + x];
            if (n == full) { _bus.write_data(tmp, n); n = 0; }
        }
    }
    if (n) _bus.write_data(tmp, n);
    return true;
}

bool SSD1306Minimal::show() {
    return _show_all();
}

// ---- SSD1306Full -----------------------------------------------------------------------

bool SSD1306Full::show_page(uint8_t page) {
    if (!_valid || _scrolling) return false;
    if (page >= _pages) return true;
    if (_mode == ADDR_PAGE) { _show_page(page); return true; }
    _set_ranges(page, page);
    _bus.write_data(_canvas.page(page), _width);
    return true;
}

void SSD1306Full::set_contrast(uint8_t value) { _command2(CMD_CONTRAST, value); }

void SSD1306Full::set_display(bool on) { _command1(CMD_DISPLAY | (on ? 1 : 0)); }

void SSD1306Full::sleep() { set_display(false); }

void SSD1306Full::wake() {
    set_display(true);
    _sleep_ms(100);
}

void SSD1306Full::set_invert(bool on) { _command1(CMD_INVERT | (on ? 1 : 0)); }

void SSD1306Full::set_all_on(bool on) { _command1(CMD_ALL_ON | (on ? 1 : 0)); }

void SSD1306Full::set_flip(bool horizontal, bool vertical) {
    _adc = horizontal ? 1 : 0;
    _scan = vertical ? 1 : 0;
    _command2(static_cast<uint8_t>(CMD_SEG_REMAP | _adc), static_cast<uint8_t>(CMD_SCAN | (_scan << 3)));
}

void SSD1306Full::set_start_line(uint8_t line) { _command1(CMD_START_LINE | (line & 0x3F)); }

void SSD1306Full::set_display_offset(uint8_t offset) { _command2(CMD_OFFSET, offset & 0x3F); }

void SSD1306Full::set_multiplex(uint8_t ratio) { _command2(CMD_MULTIPLEX, (ratio - 1) & 0x3F); }

void SSD1306Full::set_clock(uint8_t divide, uint8_t osc) {
    _command2(CMD_CLOCK, static_cast<uint8_t>(((osc & 0x0F) << 4) | ((divide - 1) & 0x0F)));
}

void SSD1306Full::set_precharge(uint8_t phase1, uint8_t phase2) {
    _command2(CMD_PRECHARGE, static_cast<uint8_t>(((phase2 & 0x0F) << 4) | (phase1 & 0x0F)));
}

void SSD1306Full::set_com_config(bool alternative, bool remap) {
    _command2(CMD_COM, static_cast<uint8_t>(0x02 | (alternative ? 0x10 : 0) | (remap ? 0x20 : 0)));
}

void SSD1306Full::set_vcomh(uint8_t value) { _command2(CMD_VCOMH, value & 0x70); }

void SSD1306Full::set_charge_pump(bool enabled) { _command2(CMD_CHARGE_PUMP, enabled ? 0x14 : 0x10); }

void SSD1306Full::set_addressing_mode(AddressingMode mode) {
    _mode = mode;
    _command2(CMD_ADDR_MODE, mode);
}

void SSD1306Full::set_scroll_horizontal(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval) {
    const uint8_t buf[7] = {static_cast<uint8_t>(right ? 0x26 : 0x27), 0x00, static_cast<uint8_t>(start_page & 7),
                            static_cast<uint8_t>(interval & 7), static_cast<uint8_t>(end_page & 7), 0x00, 0xFF};
    _command(buf, 7);
}

void SSD1306Full::set_scroll_vertical(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval, uint8_t rows) {
    const uint8_t buf[6] = {static_cast<uint8_t>(right ? 0x29 : 0x2A), 0x00, static_cast<uint8_t>(start_page & 7),
                            static_cast<uint8_t>(interval & 7), static_cast<uint8_t>(end_page & 7),
                            static_cast<uint8_t>(rows & 0x3F)};
    _command(buf, 6);
}

void SSD1306Full::set_scroll_area(uint8_t top_fixed, uint8_t scroll_rows) {
    const uint8_t buf[3] = {CMD_SCROLL_AREA, static_cast<uint8_t>(top_fixed & 0x3F), static_cast<uint8_t>(scroll_rows & 0x7F)};
    _command(buf, 3);
}

void SSD1306Full::scroll_start() {
    _command1(0x2F);
    _scrolling = true;
}

void SSD1306Full::scroll_stop() {
    _command1(0x2E);
    _scrolling = false;
    _show_all();
}

void SSD1306Full::send_command(const uint8_t* bytes, size_t len) { _command(bytes, len); }
