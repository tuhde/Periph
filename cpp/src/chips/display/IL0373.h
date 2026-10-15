#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @brief IL0373 black/white e-paper controller — minimal interface.
 *
 * Drives a 104 x 212 black/white electrophoretic panel such as the Waveshare
 * 2.13inch e-Paper (D) (up to 160 x 296 source x gate outputs). The driver
 * keeps the picture in a caller-supplied framebuffer and sends it on show().
 * Every update follows the vendor reference flow: `PON` (if the booster is
 * off), `DTM1` + `DTM2`, `DRF` and wait on BUSY, `CDI` border floating, `POF`
 * and wait; show() blocks for the whole refresh.
 *
 * The connection is a display connection (`DisplayConnectionBase` family, see
 * `specs/transport_display.md`) that owns CS#, D/C#, RES# and BUSY. The
 * driver is generic over its class (`CONN`): it needs `reset()`,
 * `waitBusy(busyActiveHigh, timeoutMs)`, `sendCommand(cmd, data, len)`,
 * `sendData(data, len)` and, for the Full read methods, `readData(buf, len)`.
 * BUSY is active low, so the driver passes `busyActiveHigh = false`.
 *
 * No heap allocation and no exceptions: the framebuffer is an array you
 * provide (`ceil(width / 8) * height` bytes, 2756 bytes at 104 x 212), and
 * failures are reported through `valid()` and bool return values.
 *
 * Pixel polarity: bit 1 = white, bit 0 = black (`DDX[0] = 1`, KW mode). The
 * controller drives a pixel only when its NEW and OLD bits differ, so the
 * driver sends OLD = NOT NEW: every pixel is driven to its final state and a
 * refresh does not depend on the previous picture.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class IL0373Minimal {
public:
    // Colours
    static constexpr uint8_t BLACK = 0;
    static constexpr uint8_t WHITE = 1;

    // Commands
    static constexpr uint8_t CMD_PSR   = 0x00;
    static constexpr uint8_t CMD_PWR   = 0x01;
    static constexpr uint8_t CMD_POF   = 0x02;
    static constexpr uint8_t CMD_PON   = 0x04;
    static constexpr uint8_t CMD_BTST  = 0x06;
    static constexpr uint8_t CMD_DSLP  = 0x07;
    static constexpr uint8_t CMD_DTM1  = 0x10;
    static constexpr uint8_t CMD_DRF   = 0x12;
    static constexpr uint8_t CMD_DTM2  = 0x13;
    static constexpr uint8_t CMD_LUTC  = 0x20;
    static constexpr uint8_t CMD_LUTWW = 0x21;
    static constexpr uint8_t CMD_LUTBW = 0x22;
    static constexpr uint8_t CMD_LUTWB = 0x23;
    static constexpr uint8_t CMD_LUTBB = 0x24;
    static constexpr uint8_t CMD_PLL   = 0x30;
    static constexpr uint8_t CMD_TSC   = 0x40;
    static constexpr uint8_t CMD_TSE   = 0x41;
    static constexpr uint8_t CMD_CDI   = 0x50;
    static constexpr uint8_t CMD_LPD   = 0x51;
    static constexpr uint8_t CMD_TRES  = 0x61;
    static constexpr uint8_t CMD_REV   = 0x70;
    static constexpr uint8_t CMD_FLG   = 0x71;
    static constexpr uint8_t CMD_VV    = 0x81;
    static constexpr uint8_t CMD_VDCS  = 0x82;
    static constexpr uint8_t CMD_PTL   = 0x90;
    static constexpr uint8_t CMD_PTIN  = 0x91;
    static constexpr uint8_t CMD_PTOUT = 0x92;
    static constexpr uint8_t CMD_ROTP  = 0xA2;

    static constexpr uint16_t MAX_WIDTH      = 160;
    static constexpr uint16_t MAX_HEIGHT     = 296;
    static constexpr uint16_t DEFAULT_WIDTH  = 104;
    static constexpr uint16_t DEFAULT_HEIGHT = 212;

    /** @brief Bytes the framebuffer needs for a panel of @p width x @p height. */
    static constexpr size_t buffer_size(uint16_t width, uint16_t height) {
        return static_cast<size_t>((width + 7) / 8) * height;
    }

    /**
     * @brief Construct the driver and run the initialisation sequence.
     *
     * Hardware reset, wait for BUSY, `BTST` `17 17 17`, `PON`, wait, `PSR`
     * `0x1F` (KW, LUT from OTP, scan up, shift right, booster on), `TRES`
     * with the panel size and `CDI` `0x97`. The picture on the panel is left
     * unchanged until show(). The framebuffer is cleared to white.
     *
     * Check valid() afterwards: it is false for a null buffer, an invalid
     * size, an invalid connection, or when BUSY never went high.
     *
     * @param connection Display connection (CS#, D/C#, RES#, BUSY).
     * @param buffer Framebuffer, `buffer_size(width, height)` bytes.
     * @param width  Pixels per row, a multiple of 8, 8..160 (default 104).
     * @param height Rows, 1..296 (default 212).
     * @param busy_timeout_ms Limit for every BUSY wait, milliseconds
     *              (default 30 000; the panel datasheet gives no refresh time).
     */
    IL0373Minimal(CONN& connection, uint8_t* buffer,
                  uint16_t width = DEFAULT_WIDTH, uint16_t height = DEFAULT_HEIGHT,
                  uint32_t busy_timeout_ms = 30000)
        : _connection(connection), _fb(buffer), _width(width), _height(height),
          _timeout(busy_timeout_ms), _psr(0x1F), _chunk(0), _powered(false),
          _asleep(false), _valid(true) {
        if (_fb == nullptr || _width < 8 || _width > MAX_WIDTH || (_width & 7) != 0 ||
            _height < 1 || _height > MAX_HEIGHT || !_connection.valid()) {
            _valid = false;
            return;
        }
        clear(WHITE);
        _valid = _init_sequence();
    }

    /** @brief True when construction succeeded and no BUSY wait has timed out since. */
    bool valid() const { return _valid; }

    /** @brief Panel width in pixels. */
    uint16_t width() const { return _width; }
    /** @brief Panel height in pixels. */
    uint16_t height() const { return _height; }

    /**
     * @brief Fill the framebuffer with one colour. The panel is unchanged until show().
     * @param color WHITE (default) or BLACK.
     */
    void clear(uint8_t color = WHITE) {
        size_t n = buffer_size(_width, _height);
        uint8_t v = (color == WHITE) ? 0xFF : 0x00;
        for (size_t i = 0; i < n; i++) _fb[i] = v;
    }

    /**
     * @brief Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param x     Column, 0 <= x < width().
     * @param y     Row, 0 <= y < height().
     * @param color BLACK (default) or WHITE.
     */
    void set_pixel(int16_t x, int16_t y, uint8_t color = BLACK) {
        if (x < 0 || y < 0 || x >= _width || y >= _height) return;
        size_t index = static_cast<size_t>(y) * ((_width + 7) / 8) + (x >> 3);
        uint8_t mask = static_cast<uint8_t>(0x80 >> (x & 7));
        if (color == WHITE) _fb[index] |= mask; else _fb[index] &= static_cast<uint8_t>(~mask);
    }

    /**
     * @brief Write OLD and NEW data to the controller and run a full display update.
     *
     * `PON` (if the booster is off), `DTM1` (OLD = NOT NEW), `DTM2` (NEW),
     * `DRF`, wait BUSY, `CDI` `0xF7` (border floating), `POF`, wait BUSY.
     * Blocks for the whole refresh. If the controller was put to sleep it is
     * woken (hardware reset + initialisation) first.
     *
     * @return false if the driver is invalid or a BUSY wait timed out.
     */
    bool show() {
        if (!_valid) return false;
        if (_asleep && !_wake()) return false;
        if (!_ensure_power()) return false;
        _write_planes();
        return _refresh_power_off();
    }

    /**
     * @brief Put the controller into deep sleep (border floating, `POF`, `DSLP` with check code 0xA5).
     *
     * Only a hardware reset restores communication; show() and Full::wake() do
     * that. The panel keeps its image.
     *
     * @return false if the driver is invalid or a BUSY wait timed out.
     */
    bool sleep() {
        if (!_valid) return false;
        if (_powered && !_power_off()) return false;
        uint8_t code = 0xA5;
        _connection.sendCommand(CMD_DSLP, &code, 1);
        _asleep = true;
        return true;
    }

protected:
    /** @brief Wait for BUSY to go high (= ready) within the configured timeout. */
    bool _wait_ready() {
        if (!_connection.waitBusy(false, _timeout)) {
            _valid = false;
            return false;
        }
        return true;
    }

    /** @brief Hardware reset and the initialisation commands (vendor OTP flow). */
    bool _init_sequence() {
        _connection.reset();
        if (!_wait_ready()) return false;
        uint8_t btst[3] = {0x17, 0x17, 0x17};
        _connection.sendCommand(CMD_BTST, btst, 3);
        _connection.sendCommand(CMD_PON);
        if (!_wait_ready()) return false;
        _powered = true;
        _connection.sendCommand(CMD_PSR, &_psr, 1);
        uint8_t tres[3] = {
            static_cast<uint8_t>(_width & 0xF8),
            static_cast<uint8_t>((_height >> 8) & 1),
            static_cast<uint8_t>(_height & 0xFF),
        };
        _connection.sendCommand(CMD_TRES, tres, 3);
        uint8_t cdi = 0x97;   // VBD = 10, DDX = 01, CDI = 0111
        _connection.sendCommand(CMD_CDI, &cdi, 1);
        return true;
    }

    /** @brief Exit deep sleep: hardware reset and initialisation. */
    bool _wake() {
        _asleep = false;
        _powered = false;
        return _init_sequence();
    }

    /** @brief PON + CDI 0x97 when the booster is off (after a refresh). */
    bool _ensure_power() {
        if (_powered) return true;
        _connection.sendCommand(CMD_PON);
        if (!_wait_ready()) return false;
        _powered = true;
        uint8_t cdi = 0x97;
        _connection.sendCommand(CMD_CDI, &cdi, 1);
        return true;
    }

    /** @brief CDI 0xF7 (border floating), POF, wait. */
    bool _power_off() {
        uint8_t cdi = 0xF7;
        _connection.sendCommand(CMD_CDI, &cdi, 1);
        _connection.sendCommand(CMD_POF);
        _powered = false;
        return _wait_ready();
    }

    /** @brief DRF, wait, then power off. */
    bool _refresh_power_off() {
        _connection.sendCommand(CMD_DRF);
        if (!_wait_ready()) return false;
        return _power_off();
    }

    /** @brief Send @p len bytes of @p src (inverted when @p invert) in 32-byte blocks or the configured chunk. */
    void _send_bytes(const uint8_t* src, size_t len, bool invert) {
        if (!invert) {
            size_t chunk = (_chunk == 0) ? len : _chunk;
            for (size_t off = 0; off < len;) {
                size_t n = (len - off < chunk) ? (len - off) : chunk;
                _connection.sendData(src + off, n);
                off += n;
            }
            return;
        }
        uint8_t tmp[32];
        size_t chunk = (_chunk == 0 || _chunk > sizeof(tmp)) ? sizeof(tmp) : _chunk;
        for (size_t off = 0; off < len;) {
            size_t n = (len - off < chunk) ? (len - off) : chunk;
            for (size_t i = 0; i < n; i++) tmp[i] = static_cast<uint8_t>(~src[off + i]);
            _connection.sendData(tmp, n);
            off += n;
        }
    }

    /** @brief DTM1 (OLD = NOT NEW) and DTM2 (NEW) for the whole frame. */
    void _write_planes() {
        size_t total = buffer_size(_width, _height);
        _connection.sendCommand(CMD_DTM1);
        _send_bytes(_fb, total, true);
        _connection.sendCommand(CMD_DTM2);
        _send_bytes(_fb, total, false);
    }

    CONN&    _connection;
    uint8_t* _fb;
    uint16_t _width;
    uint16_t _height;
    uint32_t _timeout;
    uint8_t  _psr;
    size_t   _chunk;     // pixel-data transfer size in bytes; 0 = whole plane in one transfer
    bool     _powered;
    bool     _asleep;
    bool     _valid;
};

/**
 * @brief IL0373 black/white e-paper controller — full interface.
 *
 * Extends IL0373Minimal with drawing helpers, partial-window updates, scan
 * direction, power and VCOM settings, frame rate, user LUTs and the read
 * commands (status, temperature, revision, VCOM, low power, OTP).
 *
 * The `read_*` methods need a half-duplex connection wire mode (the controller
 * drives the shared DIN/DOUT line); with a simplex connection they return
 * false.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class IL0373Full : public IL0373Minimal<CONN> {
public:
    typedef IL0373Minimal<CONN> Base;

    IL0373Full(CONN& connection, uint8_t* buffer,
               uint16_t width = Base::DEFAULT_WIDTH, uint16_t height = Base::DEFAULT_HEIGHT,
               uint32_t busy_timeout_ms = 30000)
        : Base(connection, buffer, width, height, busy_timeout_ms) {}

    // ---- framebuffer ---------------------------------------------------------------------

    /** @brief Colour of one framebuffer pixel: BLACK or WHITE (BLACK when out of range). */
    uint8_t get_pixel(int16_t x, int16_t y) const {
        if (x < 0 || y < 0 || x >= this->_width || y >= this->_height) return Base::BLACK;
        size_t index = static_cast<size_t>(y) * ((this->_width + 7) / 8) + (x >> 3);
        uint8_t mask = static_cast<uint8_t>(0x80 >> (x & 7));
        return (this->_fb[index] & mask) ? Base::WHITE : Base::BLACK;
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

    /**
     * @brief Copy a 1-bit-per-pixel bitmap into the framebuffer.
     *
     * Row-major, MSB-first, rows padded to whole bytes. Set bits are drawn in
     * @p color, clear bits leave the framebuffer unchanged.
     */
    void blit(int16_t x, int16_t y, const uint8_t* data, int16_t w, int16_t h,
              uint8_t color = Base::BLACK) {
        if (data == nullptr || w <= 0 || h <= 0) return;
        size_t rowBytes = static_cast<size_t>((w + 7) / 8);
        for (int16_t j = 0; j < h; j++) {
            for (int16_t i = 0; i < w; i++) {
                if (data[j * rowBytes + (i >> 3)] & (0x80 >> (i & 7))) this->set_pixel(x + i, y + j, color);
            }
        }
    }

    // ---- updates -------------------------------------------------------------------------

    /**
     * @brief Update only a window: write just its rows, then refresh.
     *
     * `PTIN`, `PTL` (window widened to whole 8-pixel columns), the window's
     * bytes of both planes, `DRF`, power off, `PTOUT`. Saves SPI time; the
     * waveform is the OTP full waveform, so the panel still flashes. Gates
     * outside the window are not scanned (`PT_SCAN = 0`).
     *
     * @return false if the driver is invalid, the window is empty, or a BUSY wait timed out.
     */
    bool show_region(int16_t x, int16_t y, int16_t w, int16_t h) {
        if (!this->_valid || w <= 0 || h <= 0) return false;
        if (x < 0) { w = static_cast<int16_t>(w + x); x = 0; }
        if (y < 0) { h = static_cast<int16_t>(h + y); y = 0; }
        if (x >= this->_width || y >= this->_height || w <= 0 || h <= 0) return false;
        int16_t x0 = static_cast<int16_t>(x & ~7);
        int16_t x1 = static_cast<int16_t>(((x + w - 1) | 7));
        if (x1 >= this->_width) x1 = static_cast<int16_t>(this->_width - 1);
        int16_t y0 = y;
        int16_t y1 = static_cast<int16_t>(y + h - 1);
        if (y1 >= this->_height) y1 = static_cast<int16_t>(this->_height - 1);
        if (this->_asleep && !this->_wake()) return false;
        if (!this->_ensure_power()) return false;

        this->_connection.sendCommand(Base::CMD_PTIN);
        uint8_t ptl[7] = {
            static_cast<uint8_t>(x0 & 0xF8), static_cast<uint8_t>((x1 & 0xF8) | 0x07),
            static_cast<uint8_t>((y0 >> 8) & 1), static_cast<uint8_t>(y0 & 0xFF),
            static_cast<uint8_t>((y1 >> 8) & 1), static_cast<uint8_t>(y1 & 0xFF),
            0x00,   // PT_SCAN = 0: scan only inside the window
        };
        this->_connection.sendCommand(Base::CMD_PTL, ptl, 7);

        size_t rowBytes = static_cast<size_t>((this->_width + 7) / 8);
        size_t winBytes = static_cast<size_t>((x1 - x0 + 1) / 8);
        for (uint8_t plane = 0; plane < 2; plane++) {
            this->_connection.sendCommand(plane == 0 ? Base::CMD_DTM1 : Base::CMD_DTM2);
            for (int16_t row = y0; row <= y1; row++) {
                this->_send_bytes(this->_fb + static_cast<size_t>(row) * rowBytes + (x0 >> 3),
                                  winBytes, plane == 0);
            }
        }
        bool ok = this->_refresh_power_off();
        this->_connection.sendCommand(Base::CMD_PTOUT);
        return ok;
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

    // ---- settings ------------------------------------------------------------------------

    /** @brief Mirror the picture: horizontal clears SHL, vertical clears UD (framebuffer unchanged). */
    void set_flip(bool horizontal, bool vertical) {
        if (horizontal) this->_psr &= static_cast<uint8_t>(~0x04); else this->_psr |= 0x04;
        if (vertical)   this->_psr &= static_cast<uint8_t>(~0x08); else this->_psr |= 0x08;
        _write_psr();
    }

    /**
     * @brief Source/gate power (`PWR`, internal DC-DC).
     *
     * Advanced: the values must match the panel vendor's, a wrong set can drive the
     * panel outside its rating. The driver does not write PWR by default.
     *
     * @param vdh Positive source voltage, 6.4 .. 15.0 V (0.2 V steps).
     * @param vdl Negative source voltage magnitude or value, 6.4 .. 15.0 V.
     * @param vgh Gate voltage magnitude: 20, 19, 18 or 17 V (VGL = -VGH).
     */
    void set_power(float vdh, float vdl, float vgh = 20.0f) {
        float l = vdl < 0 ? -vdl : vdl;
        int lv = static_cast<int>(20.0f - vgh + 0.5f);
        if (lv < 0) lv = 0;
        if (lv > 3) lv = 3;
        uint8_t p[5] = {
            0x03,                                         // VDS_EN | VDG_EN
            static_cast<uint8_t>(lv),                     // VCOM_HV = 0, VGHL_LV[1:0]
            _code(vdh, 6.4f, 0.2f, 0x2B),
            _code(l, 6.4f, 0.2f, 0x2B),
            0x03,                                         // VDHR (red): unused in KW mode
        };
        this->_connection.sendCommand(Base::CMD_PWR, p, 5);
    }

    /** @brief VCOM_DC (`VDCS`): -0.10 .. -3.00 V in 0.05 V steps. */
    void set_vcom_dc(float volts) {
        uint8_t code = _code(-0.10f - volts, 0.0f, 0.05f, 0x3A);
        this->_connection.sendCommand(Base::CMD_VDCS, &code, 1);
    }

    /** @brief Frame rate (`PLL`, M/N layout): the nearest rate of the table, 4 .. 200 Hz. */
    void set_frame_rate(uint16_t hz) {
        uint8_t best = 0x3C;   // M = 7, N = 4 (50 Hz)
        int bestDiff = 10000;
        for (uint8_t m = 1; m <= 7; m++) {
            for (uint8_t n = 1; n <= 7; n++) {
                int diff = static_cast<int>(_pll_hz(m, n)) - static_cast<int>(hz);
                if (diff < 0) diff = -diff;
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = static_cast<uint8_t>((m << 3) | n);
                }
            }
        }
        this->_connection.sendCommand(Base::CMD_PLL, &best, 1);
    }

    /** @brief Booster soft start (`BTST`): phase A, B, C bytes. */
    void set_soft_start(uint8_t a, uint8_t b, uint8_t c) {
        uint8_t p[3] = {a, b, c};
        this->_connection.sendCommand(Base::CMD_BTST, p, 3);
    }

    /**
     * @brief VCOM and data interval (`CDI`).
     *
     * @param cdi      Interval, 0 .. 15 (0b0111 = 10 hsync).
     * @param border   Border data selection VBD, 0 .. 3 (default 2, as at initialisation).
     * @param polarity Data polarity DDX, 0 .. 3 (default 1). The framebuffer mapping
     *                 assumes 1; changing it is the caller's responsibility.
     */
    void set_interval(uint8_t cdi, uint8_t border = 2, uint8_t polarity = 1) {
        uint8_t v = static_cast<uint8_t>(((border & 3) << 6) | ((polarity & 3) << 4) | (cdi & 0x0F));
        this->_connection.sendCommand(Base::CMD_CDI, &v, 1);
    }

    /** @brief Temperature sensor source (`TSE`): internal (default) or external LM75, offset -8 .. +7 C. */
    void set_temperature_sensor(bool external, int8_t offset = 0) {
        uint8_t v = static_cast<uint8_t>((external ? 0x80 : 0x00) | (offset & 0x0F));
        this->_connection.sendCommand(Base::CMD_TSE, &v, 1);
    }

    /** @brief Frame-transfer size in bytes for pixel data (0 = whole plane in one transfer, 1 = CS# per byte). */
    void set_data_chunk(size_t bytes) { this->_chunk = bytes; }

    /**
     * @brief Load one user waveform table and select register LUTs (`PSR.REG = 1`).
     *
     * @param table LUT_C (0, `0x20`), LUT_WW (1), LUT_BW (2), LUT_WB (3) or LUT_BB (4).
     * @param data  Table bytes; the chip datasheet gives 44 (VCOM) / 42 bytes, the panel datasheet
     *              60 / 36 / 60 / 36 / 36 — the bytes are sent as given.
     * @param len   Number of bytes.
     * @return false for an unknown table or a null buffer.
     */
    bool write_lut(uint8_t table, const uint8_t* data, size_t len) {
        if (table > 4 || data == nullptr || len == 0) return false;
        this->_connection.sendCommand(static_cast<uint8_t>(Base::CMD_LUTC + table), data, len);
        this->_psr |= 0x20;
        _write_psr();
        return true;
    }

    /** @brief Raw command and parameters (escape hatch). */
    void send_command(uint8_t command, const uint8_t* data = nullptr, size_t len = 0) {
        this->_connection.sendCommand(command, data, len);
    }

    // ---- reads (half-duplex wiring only) --------------------------------------------------

    /** @brief Read `FLG`: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY. */
    bool read_status(uint8_t* status) { return _read(Base::CMD_FLG, status, 1); }

    /** @brief Read the internal temperature sensor (`TSC`), signed degrees Celsius. */
    bool read_temperature(int8_t* celsius) {
        uint8_t raw[2] = {0, 0};
        if (!_read(Base::CMD_TSC, raw, 2)) return false;
        *celsius = static_cast<int8_t>(raw[0]);
        return true;
    }

    /** @brief Read `REV` (`LUT_REV`, one byte). */
    bool read_revision(uint8_t* revision) { return _read(Base::CMD_REV, revision, 1); }

    /** @brief Read the measured VCOM (`VV`): -0.10 V - 0.05 V x VV[5:0]. */
    bool read_vcom(float* volts) {
        uint8_t raw = 0;
        if (!_read(Base::CMD_VV, &raw, 1)) return false;
        *volts = -0.10f - 0.05f * static_cast<float>(raw & 0x3F);
        return true;
    }

    /** @brief Read low-power detection (`LPD`): @p low is true when the supply is low. */
    bool read_low_power(bool* low) {
        uint8_t raw = 0;
        if (!_read(Base::CMD_LPD, &raw, 1)) return false;
        *low = (raw & 1) == 0;
        return true;
    }

    /** @brief Read @p len OTP bytes from address 0 (`ROTP`; the dummy byte is dropped). */
    bool read_otp(uint8_t* buf, size_t len) {
        if (!this->_valid) return false;
        this->_connection.sendCommand(Base::CMD_ROTP);
        uint8_t dummy;
        if (!this->_connection.readData(&dummy, 1)) return false;
        return this->_connection.readData(buf, len);
    }

private:
    void _write_psr() {
        this->_connection.sendCommand(Base::CMD_PSR, &this->_psr, 1);
    }

    bool _read(uint8_t cmd, uint8_t* buf, size_t len) {
        if (!this->_valid) return false;
        this->_connection.sendCommand(cmd);
        return this->_connection.readData(buf, len);
    }

    static uint8_t _code(float value, float base, float step, uint8_t maxCode) {
        float c = (value - base) / step + 0.5f;
        if (c < 0) return 0;
        if (c > static_cast<float>(maxCode)) return maxCode;
        return static_cast<uint8_t>(c);
    }

    /** @brief PLL frame rate in Hz for M (1..7) and N (1..7), panel datasheet p. 24. */
    static uint8_t _pll_hz(uint8_t m, uint8_t n) {
        static const uint8_t table[7][7] = {
            {29, 14, 10, 7, 6, 5, 4},
            {57, 29, 19, 14, 11, 10, 8},
            {86, 43, 29, 21, 17, 14, 12},
            {114, 57, 38, 29, 23, 19, 16},
            {150, 72, 48, 36, 29, 24, 20},
            {171, 86, 57, 43, 34, 29, 24},
            {200, 100, 67, 50, 40, 33, 29},
        };
        return table[m - 1][n - 1];
    }
};
