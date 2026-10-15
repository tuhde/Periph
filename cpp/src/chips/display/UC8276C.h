#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @brief UC8276C black/white/red e-paper controller — minimal interface.
 *
 * Drives a 400 x 300 (up to 400 source x 300 gate) white/black/red
 * electrophoretic panel such as the Waveshare 4.2inch e-Paper (B). The driver
 * keeps the picture in caller-supplied framebuffers and sends it on show().
 * Every update is the datasheet sequence `DTM1 + DTM2 -> PON -> DRF -> POF`,
 * waiting on BUSY_N after each power/refresh step; show() blocks for the whole
 * refresh (about 17 s on the reference panel).
 *
 * The connection is a display connection (`DisplayConnectionBase` family, see
 * `specs/transport_display.md`) that owns CS#, D/C#, RES# and BUSY_N. The
 * driver is generic over its class (`CONN`): it needs `reset()`,
 * `waitBusy(busyActiveHigh, timeoutMs)`, `sendCommand(cmd, data, len)`,
 * `sendData(data, len)` and, for the Full read methods, `readData(buf, len)`.
 * BUSY_N is active low, so the driver passes `busyActiveHigh = false`.
 *
 * No heap allocation and no exceptions: the framebuffers are arrays you
 * provide (`width / 8 * height` bytes each, 15 000 bytes at 400 x 300), and
 * failures are reported through `valid()` and bool return values.
 *
 * Pixel polarity: the framebuffer mapping (B/W bit 1 = white, red bit 1 =
 * red) holds for CDI.DDX = 01, which the driver writes explicitly at
 * initialisation instead of relying on the OTP default.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class UC8276CMinimal {
public:
    // Colours
    static constexpr uint8_t BLACK = 0;
    static constexpr uint8_t WHITE = 1;
    static constexpr uint8_t RED   = 2;

    // Pixel modes (PSR.KW/R)
    static constexpr uint8_t MODE_KWR = 0;
    static constexpr uint8_t MODE_KW  = 1;

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
    static constexpr uint8_t CMD_AUTO  = 0x17;
    static constexpr uint8_t CMD_LUTC  = 0x20;
    static constexpr uint8_t CMD_LUTWW = 0x21;
    static constexpr uint8_t CMD_LUTKW = 0x22;
    static constexpr uint8_t CMD_LUTWK = 0x23;
    static constexpr uint8_t CMD_LUTKK = 0x24;
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

    static constexpr uint16_t MAX_WIDTH  = 400;
    static constexpr uint16_t MAX_HEIGHT = 300;

    /** @brief Bytes one framebuffer plane needs for a panel of @p width x @p height. */
    static constexpr size_t buffer_size(uint16_t width, uint16_t height) {
        return static_cast<size_t>((width + 7) / 8) * height;
    }

    /**
     * @brief Construct the driver and run the initialisation sequence.
     *
     * Hardware reset, wait for BUSY_N, PSR `0x0F` (KWR, LUT from OTP, scan up,
     * shift right, booster on), TRES when the panel is not 400 x 300, and CDI
     * `0xD7` (DDX = 01 so the framebuffer polarity holds). The picture on the
     * panel is left unchanged until show(). The framebuffers are cleared to
     * white.
     *
     * Check valid() afterwards: it is false for a null/invalid buffer or size,
     * an invalid connection, or when BUSY_N never went high.
     *
     * @param connection Display connection (CS#, D/C#, RES#, BUSY_N).
     * @param bw    Black/white plane buffer, `buffer_size(width, height)` bytes.
     * @param red   Red plane buffer of the same size, or nullptr for a
     *              black/white-only picture (RED is then drawn as BLACK and the
     *              red plane is sent as zeros).
     * @param width  Pixels per row, a multiple of 8, 8..400 (default 400).
     * @param height Rows, 1..300 (default 300).
     * @param busy_timeout_ms Limit for every BUSY_N wait, milliseconds
     *              (default 30 000; the full refresh was measured at 16.8 s).
     */
    UC8276CMinimal(CONN& connection, uint8_t* bw, uint8_t* red = nullptr,
                   uint16_t width = MAX_WIDTH, uint16_t height = MAX_HEIGHT,
                   uint32_t busy_timeout_ms = 30000)
        : _connection(connection), _bw(bw), _red(red), _width(width), _height(height),
          _timeout(busy_timeout_ms), _psr(0x0F), _chunk(0), _asleep(false), _valid(true) {
        if (_bw == nullptr || _width < 8 || _width > MAX_WIDTH || (_width & 7) != 0 ||
            _height < 1 || _height > MAX_HEIGHT || !_connection.valid()) {
            _valid = false;
            return;
        }
        clear(WHITE);
        _valid = _init_sequence();
    }

    /** @brief True when construction succeeded and no BUSY_N wait has timed out since. */
    bool valid() const { return _valid; }

    /** @brief Panel width in pixels. */
    uint16_t width() const { return _width; }
    /** @brief Panel height in pixels. */
    uint16_t height() const { return _height; }
    /** @brief True when a red-plane buffer was supplied. */
    bool has_red() const { return _red != nullptr; }

    /**
     * @brief Fill the framebuffers with one colour. The panel is unchanged until show().
     * @param color WHITE (default), BLACK or RED (BLACK when no red buffer was supplied).
     */
    void clear(uint8_t color = WHITE) {
        size_t n = buffer_size(_width, _height);
        uint8_t bw  = (color == WHITE) ? 0xFF : 0x00;
        uint8_t red = (color == RED && _red != nullptr) ? 0xFF : 0x00;
        for (size_t i = 0; i < n; i++) _bw[i] = bw;
        if (_red != nullptr) {
            for (size_t i = 0; i < n; i++) _red[i] = red;
        }
    }

    /**
     * @brief Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param x     Column, 0 <= x < width().
     * @param y     Row, 0 <= y < height().
     * @param color BLACK (default), WHITE or RED (BLACK when no red buffer was supplied).
     */
    void set_pixel(int16_t x, int16_t y, uint8_t color = BLACK) {
        if (x < 0 || y < 0 || x >= _width || y >= _height) return;
        size_t index = static_cast<size_t>(y) * ((_width + 7) / 8) + (x >> 3);
        uint8_t mask = static_cast<uint8_t>(0x80 >> (x & 7));
        bool white = (color == WHITE);
        bool red   = (color == RED) && _red != nullptr;
        if (white) _bw[index] |= mask; else _bw[index] &= static_cast<uint8_t>(~mask);
        if (_red != nullptr) {
            if (red) _red[index] |= mask; else _red[index] &= static_cast<uint8_t>(~mask);
        }
    }

    /**
     * @brief Write both planes to the controller and run a full display update.
     *
     * `DTM1`, `DTM2`, `PON`, wait BUSY_N, `DRF`, wait BUSY_N, `POF`, wait
     * BUSY_N. Blocks for the whole refresh. If the controller was put to sleep
     * it is woken (hardware reset + initialisation) first.
     *
     * @return false if the driver is invalid or a BUSY_N wait timed out.
     */
    bool show() {
        if (!_valid) return false;
        if (_asleep && !_wake()) return false;
        _write_planes();
        return _power_cycle_refresh();
    }

    /**
     * @brief Put the controller into deep sleep (`DSLP` with check code 0xA5).
     *
     * Only a hardware reset restores communication; show() and Full::wake() do
     * that. The panel keeps its image.
     *
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
    /** @brief Wait for BUSY_N to go high (= ready) within the configured timeout. */
    bool _wait_ready() {
        if (!_connection.waitBusy(false, _timeout)) {
            _valid = false;
            return false;
        }
        return true;
    }

    /** @brief Hardware reset and the initialisation commands. */
    bool _init_sequence() {
        _connection.reset();
        if (!_wait_ready()) return false;
        _connection.sendCommand(CMD_PSR, &_psr, 1);
        if (_width != MAX_WIDTH || _height != MAX_HEIGHT) {
            uint8_t tres[4] = {
                static_cast<uint8_t>((_width >> 8) & 1),
                static_cast<uint8_t>(_width & 0xF8),
                static_cast<uint8_t>((_height >> 8) & 1),
                static_cast<uint8_t>(_height & 0xFF),
            };
            _connection.sendCommand(CMD_TRES, tres, 4);
        }
        uint8_t cdi = 0xD7;   // VBD = 11, DDX = 01, CDI = 0111: framebuffer polarity holds
        _connection.sendCommand(CMD_CDI, &cdi, 1);
        return true;
    }

    /** @brief Exit deep sleep: hardware reset and initialisation. */
    bool _wake() {
        _asleep = false;
        return _init_sequence();
    }

    /** @brief Send one pixel plane (window rows) as DTM data; a null plane is sent as zeros. */
    void _send_plane(uint8_t cmd, const uint8_t* plane) {
        _connection.sendCommand(cmd);
        size_t total = buffer_size(_width, _height);
        if (plane == nullptr) {
            uint8_t zeros[64] = {0};
            for (size_t off = 0; off < total;) {
                size_t n = (total - off < sizeof(zeros)) ? (total - off) : sizeof(zeros);
                _connection.sendData(zeros, n);
                off += n;
            }
            return;
        }
        size_t chunk = (_chunk == 0) ? total : _chunk;
        for (size_t off = 0; off < total;) {
            size_t n = (total - off < chunk) ? (total - off) : chunk;
            _connection.sendData(plane + off, n);
            off += n;
        }
    }

    /** @brief DTM1 (B/W) and DTM2 (red) for the whole frame. */
    void _write_planes() {
        _send_plane(CMD_DTM1, _bw);
        _send_plane(CMD_DTM2, _red);
    }

    /** @brief PON, DRF, POF with a BUSY_N wait after each. */
    bool _power_cycle_refresh() {
        _connection.sendCommand(CMD_PON);
        if (!_wait_ready()) return false;
        _connection.sendCommand(CMD_DRF);
        if (!_wait_ready()) return false;
        _connection.sendCommand(CMD_POF);
        return _wait_ready();
    }

    CONN&    _connection;
    uint8_t* _bw;
    uint8_t* _red;
    uint16_t _width;
    uint16_t _height;
    uint32_t _timeout;
    uint8_t  _psr;
    size_t   _chunk;     // pixel-data transfer size in bytes; 0 = whole plane in one transfer
    bool     _asleep;
    bool     _valid;
};

/**
 * @brief UC8276C black/white/red e-paper controller — full interface.
 *
 * Extends UC8276CMinimal with drawing helpers, partial-window updates, the
 * auto sequence, pixel mode / scan direction, power and VCOM settings, frame
 * rate, user LUTs and the read commands (status, temperature, revision, VCOM,
 * low power, OTP).
 *
 * **Register layouts follow the panel datasheet** where it disagrees with the
 * chip datasheet (PWR, PLL, VCOM_DC); see `specs/display/uc8276c.md`. The
 * `read_*` methods need a half-duplex connection wire mode (the controller
 * drives the shared DIN/DOUT line); with a simplex connection they return
 * false.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class UC8276CFull : public UC8276CMinimal<CONN> {
public:
    typedef UC8276CMinimal<CONN> Base;

    UC8276CFull(CONN& connection, uint8_t* bw, uint8_t* red = nullptr,
                uint16_t width = Base::MAX_WIDTH, uint16_t height = Base::MAX_HEIGHT,
                uint32_t busy_timeout_ms = 30000)
        : Base(connection, bw, red, width, height, busy_timeout_ms) {}

    // ---- framebuffer ---------------------------------------------------------------------

    /** @brief Colour of one framebuffer pixel: BLACK, WHITE or RED (BLACK when out of range). */
    uint8_t get_pixel(int16_t x, int16_t y) const {
        if (x < 0 || y < 0 || x >= this->_width || y >= this->_height) return Base::BLACK;
        size_t index = static_cast<size_t>(y) * ((this->_width + 7) / 8) + (x >> 3);
        uint8_t mask = static_cast<uint8_t>(0x80 >> (x & 7));
        if (this->_red != nullptr && (this->_red[index] & mask)) return Base::RED;
        return (this->_bw[index] & mask) ? Base::WHITE : Base::BLACK;
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
     * bytes of both planes, `PON`, `DRF`, `POF`, `PTOUT`. Saves SPI time; the
     * waveform is the OTP full waveform, so the panel still flashes. Gates
     * outside the window are not scanned (`PT_SCAN = 0`).
     *
     * @return false if the driver is invalid, the window is empty, or a BUSY_N wait timed out.
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

        this->_connection.sendCommand(Base::CMD_PTIN);
        uint8_t ptl[9] = {
            static_cast<uint8_t>((x0 >> 8) & 1), static_cast<uint8_t>(x0 & 0xF8),
            static_cast<uint8_t>((x1 >> 8) & 1), static_cast<uint8_t>((x1 & 0xF8) | 0x07),
            static_cast<uint8_t>((y0 >> 8) & 1), static_cast<uint8_t>(y0 & 0xFF),
            static_cast<uint8_t>((y1 >> 8) & 1), static_cast<uint8_t>(y1 & 0xFF),
            0x00,   // PT_SCAN = 0: scan only inside the window
        };
        this->_connection.sendCommand(Base::CMD_PTL, ptl, 9);

        size_t rowBytes = static_cast<size_t>((this->_width + 7) / 8);
        size_t winBytes = static_cast<size_t>((x1 - x0 + 1) / 8);
        for (uint8_t plane = 0; plane < 2; plane++) {
            // if/else rather than ?: on the constexpr members: C++11 (AVR) would ODR-use them.
            const uint8_t* buf;
            if (plane == 0) {
                this->_connection.sendCommand(Base::CMD_DTM1);
                buf = this->_bw;
            } else {
                this->_connection.sendCommand(Base::CMD_DTM2);
                buf = this->_red;
            }
            uint8_t zeros[64] = {0};
            for (int16_t row = y0; row <= y1; row++) {
                if (buf != nullptr) {
                    this->_connection.sendData(buf + static_cast<size_t>(row) * rowBytes + (x0 >> 3), winBytes);
                } else {
                    for (size_t off = 0; off < winBytes;) {
                        size_t n = (winBytes - off < sizeof(zeros)) ? (winBytes - off) : sizeof(zeros);
                        this->_connection.sendData(zeros, n);
                        off += n;
                    }
                }
            }
        }
        bool ok = this->_power_cycle_refresh();
        this->_connection.sendCommand(Base::CMD_PTOUT);
        return ok;
    }

    /**
     * @brief Full-frame update using the `AUTO` sequence instead of PON/DRF/POF.
     *
     * `AUTO` is listed only in the chip datasheet (not in the panel's command
     * table). On the reference panel (Waveshare 4.2inch e-Paper (B)) it starts
     * no refresh: the call returns after the plane transfer and the panel
     * keeps its picture. Use show() unless your panel is known to accept it.
     *
     * @param deep_sleep true runs `AUTO 0xA7` (also enters deep sleep afterwards).
     * @return false if the driver is invalid or the BUSY_N wait timed out.
     */
    bool refresh_auto(bool deep_sleep = false) {
        if (!this->_valid) return false;
        if (this->_asleep && !this->_wake()) return false;
        this->_write_planes();
        uint8_t code = deep_sleep ? 0xA7 : 0xA5;
        this->_connection.sendCommand(Base::CMD_AUTO, &code, 1);
        if (!this->_wait_ready()) return false;
        if (deep_sleep) this->_asleep = true;
        return true;
    }

    /** @brief Hardware reset and initialisation (exit from deep sleep). The framebuffers are kept. */
    bool wake() {
        if (!this->_valid) return false;
        return this->_wake();
    }

    /** @brief Wait for BUSY_N to go high; @p timeout_ms 0xFFFFFFFF waits without limit. */
    bool wait_busy(uint32_t timeout_ms = 0xFFFFFFFFu) {
        return this->_connection.waitBusy(false, timeout_ms);
    }

    // ---- settings ------------------------------------------------------------------------

    /** @brief Pixel mode: MODE_KWR (black/white/red, default) or MODE_KW (black/white with NEW/OLD). */
    void set_mode(uint8_t mode) {
        if (mode == Base::MODE_KW) this->_psr |= 0x10; else this->_psr &= static_cast<uint8_t>(~0x10);
        _write_psr();
    }

    /** @brief Mirror the picture: horizontal clears SHL, vertical clears UD (framebuffer unchanged). */
    void set_flip(bool horizontal, bool vertical) {
        if (horizontal) this->_psr &= static_cast<uint8_t>(~0x04); else this->_psr |= 0x04;
        if (vertical)   this->_psr &= static_cast<uint8_t>(~0x08); else this->_psr |= 0x08;
        _write_psr();
    }

    /**
     * @brief Source/gate power (`PWR`, panel-datasheet layout, internal DC-DC).
     *
     * Advanced: the values must match the panel vendor's, a wrong set can drive the
     * panel outside its rating. The driver does not write PWR by default.
     *
     * @param vdh  Positive source voltage, 2.4 .. 11.0 V (0.2 V steps).
     * @param vdl  Negative source voltage magnitude or value, 2.4 .. 11.0 V.
     * @param vdhr Red source voltage, 2.4 .. 11.0 V.
     * @param vgh  Gate voltage magnitude: 16, 15, 14 or 13 V (VGL = -VGH).
     */
    void set_power(float vdh, float vdl, float vdhr, float vgh = 16.0f) {
        float l = vdl < 0 ? -vdl : vdl;
        int lv = static_cast<int>(16.0f - vgh + 0.5f);
        if (lv < 0) lv = 0;
        if (lv > 3) lv = 3;
        uint8_t p[5] = {
            0x03,                                         // VDS_EN | VDG_EN
            static_cast<uint8_t>(lv),                     // VCOM_HV = 0, VGHL_LV[1:0]
            _code(vdh, 2.4f, 0.2f, 0x2B),
            _code(l, 2.4f, 0.2f, 0x2B),
            _code(vdhr, 2.4f, 0.2f, 0x2B),
        };
        this->_connection.sendCommand(Base::CMD_PWR, p, 5);
    }

    /** @brief VCOM_DC (`VDCS`): -0.10 .. -3.25 V in 0.05 V steps (panel layout). */
    void set_vcom_dc(float volts) {
        uint8_t code = _code(-0.10f - volts, 0.0f, 0.05f, 0x3F);
        this->_connection.sendCommand(Base::CMD_VDCS, &code, 1);
    }

    /** @brief Frame rate (`PLL`, M/N layout): the nearest rate of the panel's table, 4 .. 200 Hz. */
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
     * @param cdi      Interval, 0 .. 15 (0b0111 = 10 hsync, the default).
     * @param border   Border data selection VBD, 0 .. 3 (default 3 = floating in KWR).
     * @param polarity Data polarity DDX, 0 .. 3 (default 1). The framebuffer mapping
     *                 assumes 1; changing it is the caller's responsibility.
     */
    void set_interval(uint8_t cdi, uint8_t border = 3, uint8_t polarity = 1) {
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
     * @brief Load user waveform tables and select them (`PSR.REG = 1`).
     *
     * The LUT commands exist only in the chip datasheet; verify the panel accepts them.
     * Lengths: @p lut_c 56, @p lut_ww 42, @p lut_kw 56, @p lut_wk 56, @p lut_kk 56 bytes.
     * Any pointer may be nullptr to leave that table alone.
     */
    void write_lut(const uint8_t* lut_c, const uint8_t* lut_ww, const uint8_t* lut_kw,
                   const uint8_t* lut_wk, const uint8_t* lut_kk) {
        if (lut_c  != nullptr) this->_connection.sendCommand(Base::CMD_LUTC,  lut_c,  56);
        if (lut_ww != nullptr) this->_connection.sendCommand(Base::CMD_LUTWW, lut_ww, 42);
        if (lut_kw != nullptr) this->_connection.sendCommand(Base::CMD_LUTKW, lut_kw, 56);
        if (lut_wk != nullptr) this->_connection.sendCommand(Base::CMD_LUTWK, lut_wk, 56);
        if (lut_kk != nullptr) this->_connection.sendCommand(Base::CMD_LUTKK, lut_kk, 56);
        this->_psr |= 0x20;
        _write_psr();
    }

    /** @brief Raw command and parameters (escape hatch). */
    void send_command(uint8_t command, const uint8_t* data = nullptr, size_t len = 0) {
        this->_connection.sendCommand(command, data, len);
    }

    // ---- reads (half-duplex wiring only) --------------------------------------------------

    /** @brief Read `FLG`: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N. */
    bool read_status(uint8_t* status) { return _read(Base::CMD_FLG, status, 1); }

    /** @brief Read the internal temperature sensor (`TSC`), signed degrees Celsius. */
    bool read_temperature(int8_t* celsius) {
        uint8_t raw[2] = {0, 0};
        if (!_read(Base::CMD_TSC, raw, 2)) return false;
        *celsius = static_cast<int8_t>(raw[0]);
        return true;
    }

    /**
     * @brief Read `REV` raw: 1 byte on the panel datasheet (LUT_REV), 5 bytes on the chip
     * datasheet (reserved, CHIP_REV, LUT_REV[7:0], [15:8], [23:16]).
     */
    bool read_revision(uint8_t* buf, size_t len) { return _read(Base::CMD_REV, buf, len); }

    /** @brief Read the measured VCOM (`VV`, panel layout): -0.10 V - 0.05 V x VV[5:0]. */
    bool read_vcom(float* volts) {
        uint8_t raw = 0;
        if (!_read(Base::CMD_VV, &raw, 1)) return false;
        *volts = -0.10f - 0.05f * static_cast<float>(raw & 0x3F);
        return true;
    }

    /** @brief Read low-power detection (`LPD`, with LPMD = 0): @p low is true when the supply is low. */
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
