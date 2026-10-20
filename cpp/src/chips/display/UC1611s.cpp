#include "UC1611s.h"

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

constexpr uint16_t UC1611sMinimal::WIDTH;
constexpr uint16_t UC1611sMinimal::HEIGHT;
constexpr size_t   UC1611sMinimal::I2C_CHUNK;

UC1611sMinimal::UC1611sMinimal(Connection& command, Connection& data, uint16_t width, uint16_t height,
                               bool topView, uint8_t contrast)
    : _cmd_conn(command), _data_conn(data), _valid(true), _width(width), _height(height),
      _pages(static_cast<uint8_t>(height / 8)), _mx(topView ? 0 : 1), _my(topView ? 1 : 0),
      _pattern(0), _contrast(contrast), _retries(0), _errors(0), _rx{}, _fb{} {
    if (width < 1 || width > WIDTH || height < 8 || height > HEIGHT || (height % 8) != 0) {
        _valid = false;
        return;
    }
    // Power-up wait, System Reset, configuration, RAM clear, display on.
    _sleep_ms(150);
    if (!_command1(CMD_RESET)) {        // own transaction, ended with STOP; no answer at all = no chip
        _valid = false;
        return;
    }
    _sleep_ms(150);
    _configure();
    for (uint8_t page = 0; page < PAGES_RAM; page++) _write_page(page, nullptr, _width);
    _enable();
    _wait_display_enabled(200);
}

void UC1611sMinimal::_sleep_ms(uint32_t ms) {
    delay(ms);
}

bool UC1611sMinimal::_command(const uint8_t* bytes, size_t len) {
    for (uint8_t attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        _cmd_conn.write(bytes, len);
        if (_cmd_conn.ok()) return true;
        _retries++;
        _sleep_ms(RETRY_DELAY_MS);
    }
    _errors++;
    return false;
}

bool UC1611sMinimal::_command1(uint8_t a) {
    return _command(&a, 1);
}

bool UC1611sMinimal::_command2(uint8_t a, uint8_t b) {
    const uint8_t buf[2] = {a, b};
    return _command(buf, 2);
}

/** One pass over `len` data bytes in I2C_CHUNK pieces; `data == nullptr` writes zeros. Stops at the first failed chunk. */
bool UC1611sMinimal::_write_chunks(const uint8_t* data, size_t len) {
    static const uint8_t ZEROS[I2C_CHUNK] = {0};
    while (len > 0) {
        size_t n = len > I2C_CHUNK ? I2C_CHUNK : len;
        _data_conn.write(data ? data : ZEROS, n);
        if (!_data_conn.ok()) return false;
        if (data) data += n;
        len -= n;
    }
    return true;
}

bool UC1611sMinimal::_write_data(const uint8_t* data, size_t len) {
    if (_write_chunks(data, len)) return true;
    _errors++;
    return false;
}

/** Write one page: address command, then the data. A partial write cannot be continued, so a failure repeats both. */
bool UC1611sMinimal::_write_page(uint8_t page, const uint8_t* data, size_t len) {
    for (uint8_t attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        if (_set_address(page) && _write_chunks(data, len)) return true;
        _retries++;
        _sleep_ms(RETRY_DELAY_MS);
    }
    _errors++;
    return false;
}

/** The chip's status pointer advances by n + 1 per read transaction (the byte after the NACKed one is consumed too)
 *  and any other transaction resets it to Status1, so a NOP write before every read makes the three bytes come
 *  out as Status1, Status2, Status3. */
bool UC1611sMinimal::_read_status(uint8_t status[3]) {
    const uint8_t nop = CMD_NOP;
    for (uint8_t attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        _cmd_conn.write(&nop, 1);
        if (_cmd_conn.ok()) {
            _cmd_conn.read(status, 3);
            if (_cmd_conn.ok()) return true;
        }
        _retries++;
        _sleep_ms(RETRY_DELAY_MS);
    }
    status[0] = status[1] = status[2] = 0;
    _errors++;
    return false;
}

/** Poll Status1.DE (bit 3) until the pump is up and the display is enabled, or the timeout expires. */
void UC1611sMinimal::_wait_display_enabled(uint32_t timeout_ms) {
    uint8_t s[3];
    for (uint32_t waited = 0; waited <= timeout_ms; waited += RETRY_DELAY_MS) {
        uint32_t keep_retries = _retries, keep_errors = _errors;
        bool got = _read_status(s);
        if (got && (s[0] & 0x08)) return;
        _retries = keep_retries;         // a NACK while the pump starts is expected, not an error
        _errors = keep_errors;
        _sleep_ms(RETRY_DELAY_MS);
    }
}

bool UC1611sMinimal::_set_address(uint8_t page, uint8_t column) {
    uint8_t pa = static_cast<uint8_t>((_pattern << 5) | (page & 0x1F));
    const uint8_t buf[4] = {
        static_cast<uint8_t>(CMD_PAGE_LO | (pa & 0x0F)),
        static_cast<uint8_t>(CMD_PAGE_HI | ((pa >> 4) & 0x07)),
        static_cast<uint8_t>(column & 0x0F),
        static_cast<uint8_t>(0x10 | (column >> 4)),
    };
    return _command(buf, 4);
}

uint8_t UC1611sMinimal::_mapping() const {
    return static_cast<uint8_t>((_my << 2) | (_mx << 1));
}

void UC1611sMinimal::_reset_chip() {
    _command1(CMD_RESET);       // own transaction, ended with STOP
    _sleep_ms(150);
}

void UC1611sMinimal::_configure() {
    const uint8_t last = static_cast<uint8_t>(_height - 1);
    const uint8_t buf[] = {
        static_cast<uint8_t>(CMD_TEMP | 1),
        CMD_MAPPING, _mapping(),
        static_cast<uint8_t>(CMD_LINE_RATE | 3),
        CMD_COM_END, last,
        CMD_PART_START, 0,
        CMD_PART_END, last,
        CMD_POTENTIOMETER, _contrast,
        static_cast<uint8_t>(CMD_RAM_ADDR | 1),
        static_cast<uint8_t>(CMD_PATTERN | 1),
    };
    _command(buf, sizeof(buf));
}

void UC1611sMinimal::_enable() {
    _command1(CMD_DISPLAY | 1);
}

void UC1611sMinimal::_show_page(uint8_t page) {
    _write_page(page, &_fb[static_cast<size_t>(page) * _width], _width);
}

void UC1611sMinimal::clear() {
    for (size_t i = 0; i < FB_SIZE; i++) _fb[i] = 0;
}

void UC1611sMinimal::set_pixel(int16_t x, int16_t y, bool on) {
    if (x < 0 || y < 0 || x >= _width || y >= _height) return;
    size_t i = static_cast<size_t>(y >> 3) * _width + x;
    if (on) _fb[i] |= static_cast<uint8_t>(1 << (y & 7));
    else    _fb[i] &= static_cast<uint8_t>(~(1 << (y & 7)));
}

void UC1611sMinimal::show() {
    if (!_valid) return;
    for (uint8_t page = 0; page < _pages; page++) _show_page(page);
}

// ---- UC1611sFull ---------------------------------------------------------------------

bool UC1611sFull::get_pixel(int16_t x, int16_t y) const {
    if (x < 0 || y < 0 || x >= _width || y >= _height) return false;
    return (_fb[static_cast<size_t>(y >> 3) * _width + x] & (1 << (y & 7))) != 0;
}

void UC1611sFull::fill(bool on) {
    uint8_t value = on ? 0xFF : 0x00;
    for (size_t i = 0; i < FB_SIZE; i++) _fb[i] = value;
}

void UC1611sFull::draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on) {
    int16_t dx = x1 > x0 ? x1 - x0 : x0 - x1;
    int16_t dy = y1 > y0 ? y0 - y1 : y1 - y0;   // -abs(y1 - y0)
    int16_t sx = x0 < x1 ? 1 : -1;
    int16_t sy = y0 < y1 ? 1 : -1;
    int16_t err = dx + dy;
    for (;;) {
        set_pixel(x0, y0, on);
        if (x0 == x1 && y0 == y1) break;
        int16_t e2 = static_cast<int16_t>(2 * err);
        if (e2 >= dy) { err += dy; x0 += sx; }
        if (e2 <= dx) { err += dx; y0 += sy; }
    }
}

void UC1611sFull::draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on, bool fill) {
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

void UC1611sFull::blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages) {
    if (columns == nullptr || height_pages == 0 || (y % 8) != 0) return;
    size_t n = len / height_pages;
    for (uint8_t p = 0; p < height_pages; p++) {
        int16_t page = static_cast<int16_t>(y / 8 + p);
        if (page < 0 || page >= _pages) continue;
        for (size_t c = 0; c < n; c++) {
            int16_t xx = static_cast<int16_t>(x + c);
            if (xx >= 0 && xx < _width) _fb[static_cast<size_t>(page) * _width + xx] = columns[p * n + c];
        }
    }
}

void UC1611sFull::show_page(uint8_t page) {
    if (_valid && page < _pages) _show_page(page);
}

void UC1611sFull::set_contrast(uint8_t value) {
    _contrast = value;
    _command2(CMD_POTENTIOMETER, value);
}

void UC1611sFull::set_display(bool on) {
    _command1(static_cast<uint8_t>(CMD_DISPLAY | (on ? 1 : 0)));
}

void UC1611sFull::sleep() {
    set_display(false);
}

void UC1611sFull::wake() {
    set_display(true);
    _wait_display_enabled(50);      // the chip NACKs and DE stays 0 for about 6 ms while the pump restarts
}

void UC1611sFull::set_invert(bool on) {
    _command1(static_cast<uint8_t>(CMD_INVERSE | (on ? 1 : 0)));
}

void UC1611sFull::set_all_on(bool on) {
    _command1(static_cast<uint8_t>(CMD_ALL_ON | (on ? 1 : 0)));
}

void UC1611sFull::set_flip(bool mirror_x, bool mirror_y) {
    _mx = mirror_x ? 1 : 0;
    _my = mirror_y ? 1 : 0;
    _command2(CMD_MAPPING, _mapping());
}

void UC1611sFull::set_scroll_line(uint8_t line) {
    const uint8_t buf[2] = {
        static_cast<uint8_t>(CMD_SCROLL_LO | (line & 0x0F)),
        static_cast<uint8_t>(CMD_SCROLL_HI | ((line >> 4) & 0x0F)),
    };
    _command(buf, 2);
}

void UC1611sFull::set_fixed_lines(uint8_t lines) {
    _command1(static_cast<uint8_t>(CMD_FIXED | (lines & 0x0F)));
}

void UC1611sFull::set_bias_ratio(uint8_t ratio) {
    _command1(static_cast<uint8_t>(CMD_BIAS | (ratio & 3)));
}

void UC1611sFull::set_temperature_compensation(uint8_t curve) {
    _command1(static_cast<uint8_t>(CMD_TEMP | (curve & 3)));
}

void UC1611sFull::set_line_rate(uint8_t rate) {
    _command1(static_cast<uint8_t>(CMD_LINE_RATE | (rate & 3)));
}

void UC1611sFull::set_pump(bool internal) {
    _command1(static_cast<uint8_t>(CMD_PUMP | (internal ? 3 : 0)));
}

void UC1611sFull::set_panel_loading(bool high) {
    _command1(static_cast<uint8_t>(CMD_PANEL | (high ? 3 : 0)));
}

void UC1611sFull::set_partial_display(bool on, uint8_t start, uint16_t end) {
    if (!on) {
        _command1(CMD_PARTIAL);
        return;
    }
    const uint8_t last = static_cast<uint8_t>(end == 0xFFFF ? _height - 1 : end);
    const uint8_t buf[] = {
        CMD_PART_START, start,
        CMD_PART_END, last,
        static_cast<uint8_t>(CMD_PARTIAL | 3),
    };
    _command(buf, sizeof(buf));
}

void UC1611sFull::set_nline_inversion(uint8_t lines, bool xor_mode) {
    _command2(CMD_NLINE, static_cast<uint8_t>((xor_mode ? 0x40 : 0) | (lines & 0x3F)));
}

void UC1611sFull::set_isolation_clock(uint8_t front, uint8_t back) {
    const uint8_t f[3] = {CMD_ISOLATION, 0x13, static_cast<uint8_t>(front & 0x0F)};
    const uint8_t b[3] = {CMD_ISOLATION, 0x14, static_cast<uint8_t>(back & 0x0F)};
    _command(f, 3);
    _command(b, 3);
}

void UC1611sFull::set_display_pattern(uint8_t pattern) {
    _command1(static_cast<uint8_t>(CMD_PATTERN | ((pattern & 3) << 1) | 1));
}

void UC1611sFull::set_write_pattern(uint8_t pattern) {
    _pattern = pattern & 3;
}

bool UC1611sFull::read_status(uint8_t status[3]) {
    return _read_status(status);
}

bool UC1611sFull::read_ram(uint8_t page, uint8_t column, uint8_t* buf, uint8_t n) {
    if (n > I2C_CHUNK) n = static_cast<uint8_t>(I2C_CHUNK);
    for (uint8_t attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        if (_set_address(page, column)) {
            _data_conn.read(_rx, static_cast<size_t>(n) + 1);
            if (_data_conn.ok()) {
                for (uint8_t i = 0; i < n; i++) buf[i] = _rx[i + 1];
                return true;
            }
        }
        _retries++;
        _sleep_ms(RETRY_DELAY_MS);
    }
    for (uint8_t i = 0; i < n; i++) buf[i] = 0;
    _errors++;
    return false;
}

void UC1611sFull::reset() {
    _reset_chip();
    _configure();
    _enable();
    _wait_display_enabled(50);
}

bool UC1611sFull::send_command(const uint8_t* bytes, size_t len) {
    return _command(bytes, len);
}

bool UC1611sFull::send_data(const uint8_t* bytes, size_t len) {
    return _write_data(bytes, len);
}
