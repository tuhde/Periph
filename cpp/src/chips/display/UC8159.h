#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @brief UC8159 seven-colour e-paper controller — minimal interface.
 *
 * Drives a 600 x 448 ACeP seven-colour electrophoretic panel such as the Waveshare
 * 5.65inch e-Paper (F). The picture is kept in a caller-supplied framebuffer and sent on
 * show(). Every update is `DTM1` (134 400 bytes, two pixels per byte), `PON`, wait on BUSY,
 * `DRF`, wait, `POF`, wait; show() blocks for the whole refresh (about 30 s).
 *
 * The connection is a display connection (`DisplayConnectionBase` family, see
 * `specs/transport_display.md`) that owns CS#, D/C#, RES# and BUSY. BUSY is active low, so the
 * driver passes `busyActiveHigh = false`. No heap allocation, no exceptions: failures are
 * reported through `valid()` and bool return values.
 *
 * Framebuffer: **3 bits per pixel**, packed little-endian (pixel i occupies bits 3i..3i+2 of
 * the byte stream), `buffer_size()` = 100 801 bytes. That is smaller than the 4 bits per pixel
 * of the wire format (134 400 bytes) so that the frame fits a 128 KB MCU; show() expands it
 * to nibbles on the fly.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class UC8159Minimal {
public:
    // Colours (the 3-bit DTM1 / CDI.VBD codes at DDX = 1)
    static constexpr uint8_t BLACK  = 0;
    static constexpr uint8_t WHITE  = 1;
    static constexpr uint8_t GREEN  = 2;
    static constexpr uint8_t BLUE   = 3;
    static constexpr uint8_t RED    = 4;
    static constexpr uint8_t YELLOW = 5;
    static constexpr uint8_t ORANGE = 6;
    static constexpr uint8_t CLEAN  = 7;

    static constexpr uint16_t WIDTH  = 600;
    static constexpr uint16_t HEIGHT = 448;

    // Commands
    static constexpr uint8_t CMD_PSR  = 0x00;
    static constexpr uint8_t CMD_PWR  = 0x01;
    static constexpr uint8_t CMD_POF  = 0x02;
    static constexpr uint8_t CMD_PFS  = 0x03;
    static constexpr uint8_t CMD_PON  = 0x04;
    static constexpr uint8_t CMD_BTST = 0x06;
    static constexpr uint8_t CMD_DSLP = 0x07;
    static constexpr uint8_t CMD_DTM1 = 0x10;
    static constexpr uint8_t CMD_DSP  = 0x11;
    static constexpr uint8_t CMD_DRF  = 0x12;
    static constexpr uint8_t CMD_IPC  = 0x13;
    static constexpr uint8_t CMD_PLL  = 0x30;
    static constexpr uint8_t CMD_TSC  = 0x40;
    static constexpr uint8_t CMD_TSE  = 0x41;
    static constexpr uint8_t CMD_CDI  = 0x50;
    static constexpr uint8_t CMD_LPD  = 0x51;
    static constexpr uint8_t CMD_TCON = 0x60;
    static constexpr uint8_t CMD_TRES = 0x61;
    static constexpr uint8_t CMD_PWS  = 0xE3;
    static constexpr uint8_t CMD_FLG  = 0x71;
    static constexpr uint8_t CMD_VV   = 0x81;
    static constexpr uint8_t CMD_VDCS = 0x82;

    /** @brief Bytes the framebuffer needs (3 bits per pixel, plus one pad byte). */
    static constexpr size_t buffer_size() {
        return (static_cast<size_t>(WIDTH) * HEIGHT * 3 + 7) / 8 + 1;
    }

    /**
     * @brief Construct the driver and run the initialisation commands.
     *
     * Hardware reset, wait for BUSY, then the vendor demo's settings: `PSR EF 08`,
     * `PWR 37 00 23 23`, `PFS 00`, `BTST C7 C7 1D`, `PLL 3C`, `TSE 00`, `CDI 37`,
     * `TCON 22`, `TRES 600 x 448`, `PWS AA`. The panel picture is unchanged until show().
     * The framebuffer is cleared to white. Check valid() afterwards.
     *
     * @param connection Display connection.
     * @param buffer     Framebuffer, at least `buffer_size()` bytes.
     * @param buffer_len Length of @p buffer in bytes (`valid()` is false when too small).
     * @param busy_timeout_ms Limit for every BUSY wait in milliseconds (default 60 000;
     *                   a refresh takes about 30 s).
     */
    UC8159Minimal(CONN& connection, uint8_t* buffer, size_t buffer_len,
                  uint32_t busy_timeout_ms = 60000)
        : _connection(connection), _fb(buffer), _timeout(busy_timeout_ms),
          _psr(0xEF), _cdi(0x37), _asleep(false), _valid(true) {
        if (_fb == nullptr || buffer_len < buffer_size() || !_connection.valid()) {
            _valid = false;
            return;
        }
        clear(WHITE);
        _valid = _init_sequence();
    }

    /** @brief True when construction succeeded and no BUSY wait has timed out since. */
    bool valid() const { return _valid; }

    /** @brief Panel width in pixels (600). */
    uint16_t width() const { return WIDTH; }
    /** @brief Panel height in pixels (448). */
    uint16_t height() const { return HEIGHT; }

    /**
     * @brief Fill the framebuffer with one colour. The panel is unchanged until show().
     * @param color 0..7 (WHITE default).
     */
    void clear(uint8_t color = WHITE) {
        uint32_t v = 0;
        for (uint8_t k = 0; k < 8; k++) v |= static_cast<uint32_t>(color & 7) << (3 * k);
        size_t n = buffer_size();
        for (size_t i = 0; i < n; i++) {
            _fb[i] = static_cast<uint8_t>(v >> (8 * (i % 3)));
        }
    }

    /**
     * @brief Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param color 0..7 (BLACK default).
     */
    void set_pixel(int16_t x, int16_t y, uint8_t color = BLACK) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return;
        _put(static_cast<size_t>(y) * WIDTH + static_cast<size_t>(x), color & 7);
    }

    /**
     * @brief Send the frame and run a full display update (about 30 s).
     *
     * `DTM1` + 134 400 bytes, `PON`, wait BUSY, `DRF`, wait BUSY, `POF`, wait BUSY. If the
     * controller was put to sleep it is woken (hardware reset + initialisation) first.
     *
     * @return false if the driver is invalid or a BUSY wait timed out.
     */
    bool show() {
        if (!_valid) return false;
        if (_asleep && !_wake()) return false;
        _send_frame();
        _connection.sendCommand(CMD_PON);
        if (!_wait_ready()) return false;
        _connection.sendCommand(CMD_DRF);
        if (!_wait_ready()) return false;
        _connection.sendCommand(CMD_POF);
        return _wait_ready();
    }

    /**
     * @brief Put the controller into deep sleep (`DSLP` with check code 0xA5).
     * Only a hardware reset restores communication; show() and Full::wake() do that.
     * @return false if the driver is invalid.
     */
    bool sleep() {
        if (!_valid) return false;
        uint8_t code = 0xA5;
        _connection.sendCommand(CMD_DSLP, &code, 1);
        _asleep = true;
        return true;
    }

protected:
    /** @brief Colour of pixel @p i (0 .. WIDTH*HEIGHT-1). */
    uint8_t _get(size_t i) const {
        size_t bit = i * 3;
        size_t b = bit >> 3;
        uint8_t s = static_cast<uint8_t>(bit & 7);
        uint16_t w = static_cast<uint16_t>(_fb[b] | (static_cast<uint16_t>(_fb[b + 1]) << 8));
        return static_cast<uint8_t>((w >> s) & 7);
    }

    void _put(size_t i, uint8_t c) {
        size_t bit = i * 3;
        size_t b = bit >> 3;
        uint8_t s = static_cast<uint8_t>(bit & 7);
        uint16_t w = static_cast<uint16_t>(_fb[b] | (static_cast<uint16_t>(_fb[b + 1]) << 8));
        w = static_cast<uint16_t>((w & ~(7u << s)) | (static_cast<uint16_t>(c) << s));
        _fb[b] = static_cast<uint8_t>(w & 0xFF);
        _fb[b + 1] = static_cast<uint8_t>(w >> 8);
    }

    bool _wait_ready() {
        if (!_connection.waitBusy(false, _timeout)) {
            _valid = false;
            return false;
        }
        return true;
    }

    /** @brief Hardware reset and the vendor demo's initialisation commands. */
    bool _init_sequence() {
        _connection.reset();
        if (!_wait_ready()) return false;
        uint8_t psr[2] = {_psr, 0x08};
        _connection.sendCommand(CMD_PSR, psr, 2);
        uint8_t pwr[4] = {0x37, 0x00, 0x23, 0x23};
        _connection.sendCommand(CMD_PWR, pwr, 4);
        uint8_t pfs = 0x00;
        _connection.sendCommand(CMD_PFS, &pfs, 1);
        uint8_t btst[3] = {0xC7, 0xC7, 0x1D};
        _connection.sendCommand(CMD_BTST, btst, 3);
        uint8_t pll = 0x3C;
        _connection.sendCommand(CMD_PLL, &pll, 1);
        uint8_t tse = 0x00;
        _connection.sendCommand(CMD_TSE, &tse, 1);
        _connection.sendCommand(CMD_CDI, &_cdi, 1);
        uint8_t tcon = 0x22;
        _connection.sendCommand(CMD_TCON, &tcon, 1);
        uint8_t tres[4] = {0x02, 0x58, 0x01, 0xC0};   // 600 x 448
        _connection.sendCommand(CMD_TRES, tres, 4);
        uint8_t pws = 0xAA;
        _connection.sendCommand(CMD_PWS, &pws, 1);
        return true;
    }

    bool _wake() {
        _asleep = false;
        return _init_sequence();
    }

    /** @brief DTM1 plus the whole frame as nibbles, 128 pixels (64 bytes) per transfer. */
    void _send_frame() {
        _connection.sendCommand(CMD_DTM1);
        uint8_t out[64];
        const size_t total = static_cast<size_t>(WIDTH) * HEIGHT;
        for (size_t p = 0; p < total; p += 128) {
            for (uint8_t k = 0; k < 64; k++) {
                size_t i = p + 2u * k;
                out[k] = static_cast<uint8_t>((_get(i) << 4) | _get(i + 1));
            }
            _connection.sendData(out, sizeof(out));
        }
    }

    CONN&    _connection;
    uint8_t* _fb;
    uint32_t _timeout;
    uint8_t  _psr;
    uint8_t  _cdi;
    bool     _asleep;
    bool     _valid;
};

/**
 * @brief UC8159 seven-colour e-paper controller — full interface.
 *
 * Extends UC8159Minimal with drawing helpers, scan direction, frame rate, border/data interval,
 * temperature-sensor source, VCOM_DC and the read commands (status, temperature, low power, VCOM).
 * The `read_*` methods need a half-duplex connection wire mode; with a simplex connection they
 * return false.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class UC8159Full : public UC8159Minimal<CONN> {
public:
    typedef UC8159Minimal<CONN> Base;

    UC8159Full(CONN& connection, uint8_t* buffer, size_t buffer_len,
               uint32_t busy_timeout_ms = 60000)
        : Base(connection, buffer, buffer_len, busy_timeout_ms) {}

    /** @brief Colour 0..7 of one framebuffer pixel (BLACK when out of range). */
    uint8_t get_pixel(int16_t x, int16_t y) const {
        if (x < 0 || y < 0 || x >= Base::WIDTH || y >= Base::HEIGHT) return Base::BLACK;
        return this->_get(static_cast<size_t>(y) * Base::WIDTH + static_cast<size_t>(x));
    }

    /** @brief Bresenham line into the framebuffer. */
    void draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, uint8_t color = Base::BLACK) {
        int16_t dx = x1 > x0 ? x1 - x0 : x0 - x1;
        int16_t dy = y1 > y0 ? y0 - y1 : y1 - y0;   // negative |dy|
        int16_t sx = x0 < x1 ? 1 : -1;
        int16_t sy = y0 < y1 ? 1 : -1;
        int16_t err = dx + dy;
        for (;;) {
            this->set_pixel(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int16_t e2 = static_cast<int16_t>(2 * err);
            if (e2 >= dy) { err = static_cast<int16_t>(err + dy); x0 = static_cast<int16_t>(x0 + sx); }
            if (e2 <= dx) { err = static_cast<int16_t>(err + dx); y0 = static_cast<int16_t>(y0 + sy); }
        }
    }

    /** @brief Rectangle outline or filled rectangle into the framebuffer. */
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint8_t color = Base::BLACK,
                   bool fill = false) {
        if (w <= 0 || h <= 0) return;
        if (fill) {
            for (int16_t j = 0; j < h; j++)
                for (int16_t i = 0; i < w; i++) this->set_pixel(x + i, y + j, color);
            return;
        }
        draw_line(x, y, x + w - 1, y, color);
        draw_line(x, y + h - 1, x + w - 1, y + h - 1, color);
        draw_line(x, y, x, y + h - 1, color);
        draw_line(x + w - 1, y, x + w - 1, y + h - 1, color);
    }

    /** @brief Copy a 1-bit-per-pixel bitmap (row-major, MSB-first); set bits are drawn in @p color. */
    void blit(int16_t x, int16_t y, const uint8_t* data, int16_t w, int16_t h,
              uint8_t color = Base::BLACK) {
        if (data == nullptr || w <= 0 || h <= 0) return;
        size_t rowBytes = static_cast<size_t>((w + 7) / 8);
        for (int16_t j = 0; j < h; j++)
            for (int16_t i = 0; i < w; i++)
                if (data[j * rowBytes + (i >> 3)] & (0x80 >> (i & 7))) this->set_pixel(x + i, y + j, color);
    }

    /** @brief Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept. */
    bool wake() {
        if (!this->_valid) return false;
        return this->_wake();
    }

    /** @brief Wait for BUSY to go high; @p timeout_ms 0xFFFFFFFF waits without limit. */
    bool wait_busy(uint32_t timeout_ms = 0xFFFFFFFFu) {
        return this->_connection.waitBusy(false, timeout_ms);
    }

    /** @brief Mirror the picture: horizontal clears SHL, vertical clears UD (framebuffer unchanged). */
    void set_flip(bool horizontal, bool vertical) {
        if (horizontal) this->_psr &= static_cast<uint8_t>(~0x04); else this->_psr |= 0x04;
        if (vertical)   this->_psr &= static_cast<uint8_t>(~0x08); else this->_psr |= 0x08;
        uint8_t p[2] = {this->_psr, 0x08};
        this->_connection.sendCommand(Base::CMD_PSR, p, 2);
    }

    /** @brief Raw `PLL` code: `0x3C` = 50 Hz (default), `0x3A` = 100 Hz, `0x39` = 200 Hz, `0x00..0x0F` = 12.5 .. 200 Hz. */
    void set_frame_rate(uint8_t code) {
        this->_connection.sendCommand(Base::CMD_PLL, &code, 1);
    }

    /**
     * @brief VCOM and data interval (`CDI`).
     * @param cdi      Interval 0..15 (0b0111 = 10 hsync).
     * @param border   Border colour code 0..7 (default WHITE).
     * @param polarity DDX, 1 = default (the colour codes assume 1).
     */
    void set_interval(uint8_t cdi, uint8_t border = Base::WHITE, uint8_t polarity = 1) {
        uint8_t v = static_cast<uint8_t>(((border & 7) << 5) | ((polarity & 1) << 4) | (cdi & 0x0F));
        this->_cdi = v;
        this->_connection.sendCommand(Base::CMD_CDI, &v, 1);
    }

    /** @brief Temperature sensor source (`TSE`): internal or external LM75, offset -8 .. +7 C. */
    void set_temperature_sensor(bool external, int8_t offset = 0) {
        uint8_t v = static_cast<uint8_t>((external ? 0x80 : 0x00) | (offset & 0x0F));
        this->_connection.sendCommand(Base::CMD_TSE, &v, 1);
    }

    /** @brief VCOM_DC (`VDCS`): -0.10 .. -4.0 V in 0.05 V steps. */
    void set_vcom_dc(float volts) {
        float c = (-0.10f - volts) / 0.05f + 2.5f;
        if (c < 2.0f) c = 2.0f;
        if (c > 80.0f) c = 80.0f;
        uint8_t code = static_cast<uint8_t>(c);
        this->_connection.sendCommand(Base::CMD_VDCS, &code, 1);
    }

    /** @brief Raw command and parameters (escape hatch). */
    void send_command(uint8_t command, const uint8_t* data = nullptr, size_t len = 0) {
        this->_connection.sendCommand(command, data, len);
    }

    /** @brief Read `FLG`: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N. */
    bool read_status(uint8_t* status) { return _read(Base::CMD_FLG, status, 1); }

    /** @brief Read the internal temperature sensor (`TSC`), signed degrees Celsius. */
    bool read_temperature(int8_t* celsius) {
        uint8_t raw[2] = {0, 0};
        if (!_read(Base::CMD_TSC, raw, 2)) return false;
        *celsius = static_cast<int8_t>(raw[0]);
        return true;
    }

    /** @brief Read low-power detection (`LPD`): @p low is true when VDD < 2.5 V. */
    bool read_low_power(bool* low) {
        uint8_t raw = 0;
        if (!_read(Base::CMD_LPD, &raw, 1)) return false;
        *low = (raw & 1) == 0;
        return true;
    }

    /** @brief Read the measured VCOM (`VV`): -0.10 V - 0.05 V x (VV - 2). */
    bool read_vcom(float* volts) {
        uint8_t raw = 0;
        if (!_read(Base::CMD_VV, &raw, 1)) return false;
        *volts = -0.10f - 0.05f * static_cast<float>((raw & 0x7F) > 2 ? (raw & 0x7F) - 2 : 0);
        return true;
    }

private:
    bool _read(uint8_t cmd, uint8_t* buf, size_t len) {
        if (!this->_valid) return false;
        this->_connection.sendCommand(cmd);
        return this->_connection.readData(buf, len);
    }
};
