#include "PCF8579.h"

#ifdef ARDUINO
#include <Arduino.h>
static inline void pcf8579_sleep_ms(unsigned long ms) { delay(ms); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void pcf8579_sleep_ms(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void pcf8579_sleep_ms(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void pcf8579_sleep_ms(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void pcf8579_sleep_ms(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void pcf8579_sleep_ms(unsigned long ms) { usleep(ms * 1000UL); }
#endif

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t PCF8579Minimal::RAM_COLUMNS;
constexpr uint8_t PCF8579Minimal::RAM_BANKS;
constexpr uint8_t PCF8579Minimal::CONT;
constexpr uint8_t PCF8579Minimal::CMD_SET_MODE;
constexpr uint8_t PCF8579Minimal::CMD_DEVICE_SELECT;
constexpr uint8_t PCF8579Minimal::CMD_RAM_ACCESS;
constexpr uint8_t PCF8579Minimal::CMD_SET_START_BANK;
constexpr uint8_t PCF8579Minimal::STATUS_BLANK;
constexpr uint8_t PCF8579Minimal::STATUS_NORMAL;
constexpr uint8_t PCF8579Minimal::ACCESS_CHARACTER;
constexpr uint8_t PCF8579Minimal::ACCESS_FULL_GRAPHIC;

static const uint8_t PER_COL[3] = {1, 2, 4};

PCF8579Minimal::PCF8579Minimal(Connection& connection, uint8_t subaddress, uint8_t rows, bool mixed)
    : _connection(connection), _subaddress(subaddress <= 15 ? subaddress : 0),
      _rows(_mux_code(rows) >= 0 ? rows : 16), _mixed(mixed),
      _status(STATUS_NORMAL), _start_bank(0) {
    _zero_shadow();
    _delay_ms(2);  // >= 1 ms for the internal power-on reset
    _init();
}

void PCF8579Minimal::_delay_ms(unsigned long ms) { pcf8579_sleep_ms(ms); }

int8_t PCF8579Minimal::_mux_code(uint8_t rows) {
    switch (rows) {
        case 8:  return 1;
        case 16: return 2;
        case 24: return 3;
        case 32: return 0;
        default: return -1;
    }
}

uint8_t PCF8579Minimal::_mode_byte(uint8_t status, bool cont) const {
    return (cont ? CONT : 0) | CMD_SET_MODE | ((_mixed ? 1 : 0) << 4) | (status << 2) |
           (uint8_t)_mux_code(_rows);
}

uint8_t PCF8579Minimal::_select_byte() const {
    return CONT | CMD_DEVICE_SELECT | _subaddress;
}

uint8_t PCF8579Minimal::_ram_access_byte(uint8_t access, uint8_t bank) {
    return CONT | CMD_RAM_ACCESS | (access << 2) | bank;
}

void PCF8579Minimal::_zero_shadow() {
    for (uint8_t b = 0; b < RAM_BANKS; b++)
        for (uint8_t i = 0; i < RAM_COLUMNS; i++) _shadow[b][i] = 0;
}

void PCF8579Minimal::_init() {
    _start_bank = 0;
    uint8_t buf[5 + RAM_COLUMNS * RAM_BANKS] = {0};
    buf[0] = _select_byte();
    buf[1] = _mode_byte(STATUS_BLANK, true);
    buf[2] = CONT | CMD_SET_START_BANK;
    buf[3] = _ram_access_byte(ACCESS_FULL_GRAPHIC, 0);
    buf[4] = 0x00;  // load-X 0 (C = 0); the 160 data bytes stay zero
    _connection.write(buf, sizeof(buf));
    _zero_shadow();
    _status = STATUS_NORMAL;
    uint8_t on = _mode_byte(STATUS_NORMAL, false);
    _connection.write(&on, 1);
}

void PCF8579Minimal::_ram_write(uint8_t x, uint8_t bank, const uint8_t* data, size_t len, uint8_t access) {
    uint8_t buf[3 + RAM_COLUMNS * RAM_BANKS];
    buf[0] = _select_byte();
    buf[1] = _ram_access_byte(access, bank);
    buf[2] = x & 0x3F;
    for (size_t i = 0; i < len; i++) buf[3 + i] = data[i];
    _connection.write(buf, 3 + len);
    const uint8_t per_col = PER_COL[access];
    for (size_t i = 0; i < len; i++) {
        const size_t col = x + i / per_col;
        if (col < RAM_COLUMNS) _shadow[bank + (i % per_col)][col] = data[i];
    }
}

void PCF8579Minimal::clear() {
    uint8_t buf[3 + RAM_COLUMNS * RAM_BANKS] = {0};
    buf[0] = _select_byte();
    buf[1] = _ram_access_byte(ACCESS_FULL_GRAPHIC, 0);
    buf[2] = 0x00;
    _connection.write(buf, sizeof(buf));
    _zero_shadow();
}

void PCF8579Minimal::set_pixel(int x, int y, bool on) {
    if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= _rows) return;
    const uint8_t bank = y / 8;
    const uint8_t bit = 1u << (y % 8);
    uint8_t v = _shadow[bank][x];
    v = on ? (v | bit) : (v & ~bit);
    _ram_write(x, bank, &v, 1, ACCESS_CHARACTER);
}

bool PCF8579Minimal::write_columns(int x, int bank, const uint8_t* data, size_t len) {
    if (bank < 0 || bank >= _rows / 8) return false;
    if (x < 0 || x >= RAM_COLUMNS) return true;
    if (len > (size_t)(RAM_COLUMNS - x)) len = RAM_COLUMNS - x;
    if (len == 0) return true;
    _ram_write(x, bank, data, len, ACCESS_CHARACTER);
    return true;
}

// ---------------------------------------------------------------------------

constexpr uint8_t PCF8579Full::DISPLAY_BLANK;
constexpr uint8_t PCF8579Full::DISPLAY_NORMAL;
constexpr uint8_t PCF8579Full::DISPLAY_ALL_ON;
constexpr uint8_t PCF8579Full::DISPLAY_INVERSE;

constexpr size_t PCF8579Full::MAX_CASCADE_BYTES;

PCF8579Full::PCF8579Full(Connection& connection, uint8_t subaddress, uint8_t rows, bool mixed)
    : PCF8579Minimal(connection, subaddress, rows, mixed) {
    for (uint8_t b = 0; b < RAM_BANKS; b++)
        for (uint8_t i = 0; i < RAM_COLUMNS / 8; i++) _dirty[b][i] = 0;
}

void PCF8579Full::_plot(int x, int y, bool on) {
    if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= _rows) return;
    const uint8_t bank = y / 8;
    const uint8_t bit = 1u << (y % 8);
    if (on) _shadow[bank][x] |= bit;
    else    _shadow[bank][x] &= (uint8_t)~bit;
    _dirty[bank][x / 8] |= 1u << (x % 8);
}

// One transaction per run of consecutive changed columns in a bank.
void PCF8579Full::_flush() {
    for (uint8_t bank = 0; bank < RAM_BANKS; bank++) {
        uint8_t x = 0;
        while (x < RAM_COLUMNS) {
            if (!(_dirty[bank][x / 8] & (1u << (x % 8)))) { x++; continue; }
            const uint8_t start = x;
            while (x < RAM_COLUMNS && (_dirty[bank][x / 8] & (1u << (x % 8)))) x++;
            uint8_t tmp[RAM_COLUMNS];
            for (uint8_t i = start; i < x; i++) tmp[i - start] = _shadow[bank][i];
            _ram_write(start, bank, tmp, x - start, ACCESS_CHARACTER);
        }
        for (uint8_t i = 0; i < RAM_COLUMNS / 8; i++) _dirty[bank][i] = 0;
    }
}

bool PCF8579Full::get_pixel(int x, int y) const {
    if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= _rows) return false;
    return (_shadow[y / 8][x] & (1u << (y % 8))) != 0;
}

void PCF8579Full::fill(bool on) {
    uint8_t col[RAM_COLUMNS];
    for (uint8_t i = 0; i < RAM_COLUMNS; i++) col[i] = on ? 0xFF : 0x00;
    for (uint8_t bank = 0; bank < _rows / 8; bank++) _ram_write(0, bank, col, RAM_COLUMNS, ACCESS_CHARACTER);
}

void PCF8579Full::draw_line(int x0, int y0, int x1, int y1, bool on) {
    const int dx = x1 > x0 ? x1 - x0 : x0 - x1;
    const int dy = -(y1 > y0 ? y1 - y0 : y0 - y1);
    const int sx = x0 < x1 ? 1 : -1;
    const int sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    for (;;) {
        _plot(x0, y0, on);
        if (x0 == x1 && y0 == y1) break;
        const int e2 = 2 * err;
        if (e2 >= dy) { err += dy; x0 += sx; }
        if (e2 <= dx) { err += dx; y0 += sy; }
    }
    _flush();
}

void PCF8579Full::draw_rect(int x, int y, int w, int h, bool on, bool fill) {
    if (w <= 0 || h <= 0) return;
    for (int yy = y; yy < y + h; yy++) {
        for (int xx = x; xx < x + w; xx++) {
            if (fill || yy == y || yy == y + h - 1 || xx == x || xx == x + w - 1) _plot(xx, yy, on);
        }
    }
    _flush();
}

bool PCF8579Full::show_bitmap(int x, int y, const uint8_t* columns, size_t width) {
    if (y < 0 || y % 8 != 0) return false;
    return write_columns(x, y / 8, columns, width);
}

bool PCF8579Full::set_display(uint8_t status) {
    if (status > 3) return false;
    _status = status;
    uint8_t b = _mode_byte(status, false);
    _connection.write(&b, 1);
    return true;
}

bool PCF8579Full::set_mode(uint8_t rows, bool mixed) {
    if (_mux_code(rows) < 0) return false;
    _rows = rows;
    _mixed = mixed;
    uint8_t b = _mode_byte(_status, false);
    _connection.write(&b, 1);
    return true;
}

bool PCF8579Full::set_start_bank(uint8_t bank) {
    if (bank > 3) return false;
    _start_bank = bank;
    uint8_t b = CMD_SET_START_BANK | bank;
    _connection.write(&b, 1);
    return true;
}

void PCF8579Full::scroll(int banks) {
    const int n = _rows / 8;
    int b = ((int)_start_bank + banks) % n;
    if (b < 0) b += n;
    set_start_bank((uint8_t)b);
}

bool PCF8579Full::_check_ram_args(int x, int bank, size_t count, uint8_t access, bool cascade) {
    if (access > 2 || x < 0 || x > 39 || bank < 0 || bank > 3) return false;
    if ((access == ACCESS_HALF_GRAPHIC && (bank % 2)) || (access == ACCESS_FULL_GRAPHIC && bank != 0)) return false;
    const size_t per_col = PER_COL[access];
    if (!cascade && (size_t)x + (count + per_col - 1) / per_col > RAM_COLUMNS) return false;
    return true;
}

bool PCF8579Full::write_ram(int x, int bank, const uint8_t* data, size_t len, uint8_t mode) {
    if (!_check_ram_args(x, bank, len, mode)) return false;
    if (len == 0) return true;
    _ram_write(x, bank, data, len, mode);
    return true;
}

bool PCF8579Full::write_ram_cascade(const uint8_t* data, size_t len, uint8_t mode) {
    if (len > MAX_CASCADE_BYTES || !_check_ram_args(0, 0, len, mode, true)) return false;
    if (len == 0) return true;
    uint8_t buf[3 + MAX_CASCADE_BYTES];
    buf[0] = _select_byte();
    buf[1] = _ram_access_byte(mode, 0);
    buf[2] = 0x00;
    for (size_t i = 0; i < len; i++) buf[3 + i] = data[i];
    _connection.write(buf, 3 + len);
    const uint8_t per_col = PER_COL[mode];
    for (size_t i = 0; i < len; i++) {
        const size_t col = i / per_col;
        if (col < RAM_COLUMNS) _shadow[i % per_col][col] = data[i];
    }
    return true;
}

bool PCF8579Full::read_ram(int x, int bank, uint8_t* buf, size_t n, uint8_t mode) {
    if (!_check_ram_args(x, bank, n, mode)) return false;
    const uint8_t cmds[3] = {
        _select_byte(),
        _ram_access_byte(mode, (uint8_t)bank),
        (uint8_t)(x & 0x3F),
    };
    _connection.write_read(cmds, sizeof(cmds), buf, n);
    return true;
}

void PCF8579Full::reset() {
    _init();
}
