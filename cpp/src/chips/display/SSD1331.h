#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @brief SSD1331 96 x 64 RGB OLED controller — minimal interface.
 *
 * Drives the Waveshare 0.95inch RGB OLED over 4-wire SPI. The picture is kept
 * in a caller-supplied RGB565 framebuffer (96 x 64 x 2 = 12 288 bytes, high
 * byte first) and sent to the display RAM by show().
 *
 * The connection is a display connection (`DisplayConnectionBase` family, see
 * `specs/transport_display.md`) with no BUSY pin (pass `nullptr`). The SSD1331
 * takes command parameters with D/C# low, so the driver sends every byte of a
 * command as its own `sendCommand(byte)`; only pixel data uses `sendData()`.
 * The driver needs `valid()`, `reset()`, `delay(ms)`, `sendCommand()` and
 * `sendData()` from the connection.
 *
 * No heap allocation and no exceptions: failures are reported through valid().
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class SSD1331Minimal {
public:
    static constexpr uint16_t WIDTH  = 96;
    static constexpr uint16_t HEIGHT = 64;
    /** @brief Bytes the framebuffer needs. */
    static constexpr size_t BUFFER_SIZE = static_cast<size_t>(WIDTH) * HEIGHT * 2;

    // Commands
    static constexpr uint8_t CMD_COLUMN    = 0x15;
    static constexpr uint8_t CMD_ROW       = 0x75;
    static constexpr uint8_t CMD_CONTRAST_A = 0x81;
    static constexpr uint8_t CMD_CONTRAST_B = 0x82;
    static constexpr uint8_t CMD_CONTRAST_C = 0x83;
    static constexpr uint8_t CMD_MASTER_CURRENT = 0x87;
    static constexpr uint8_t CMD_PRECHARGE_A = 0x8A;
    static constexpr uint8_t CMD_PRECHARGE_B = 0x8B;
    static constexpr uint8_t CMD_PRECHARGE_C = 0x8C;
    static constexpr uint8_t CMD_REMAP     = 0xA0;
    static constexpr uint8_t CMD_START_LINE = 0xA1;
    static constexpr uint8_t CMD_OFFSET    = 0xA2;
    static constexpr uint8_t CMD_MODE_NORMAL = 0xA4;
    static constexpr uint8_t CMD_MODE_ALL_ON = 0xA5;
    static constexpr uint8_t CMD_MODE_ALL_OFF = 0xA6;
    static constexpr uint8_t CMD_MODE_INVERSE = 0xA7;
    static constexpr uint8_t CMD_MUX       = 0xA8;
    static constexpr uint8_t CMD_DIM_SETTING = 0xAB;
    static constexpr uint8_t CMD_MASTER_CONFIG = 0xAD;
    static constexpr uint8_t CMD_DISPLAY_DIM = 0xAC;
    static constexpr uint8_t CMD_DISPLAY_OFF = 0xAE;
    static constexpr uint8_t CMD_DISPLAY_ON = 0xAF;
    static constexpr uint8_t CMD_POWER_SAVE = 0xB0;
    static constexpr uint8_t CMD_PHASE     = 0xB1;
    static constexpr uint8_t CMD_CLOCK     = 0xB3;
    static constexpr uint8_t CMD_GRAY_TABLE = 0xB8;
    static constexpr uint8_t CMD_GRAY_LINEAR = 0xB9;
    static constexpr uint8_t CMD_PRECHARGE_LEVEL = 0xBB;
    static constexpr uint8_t CMD_VCOMH     = 0xBE;
    static constexpr uint8_t CMD_LOCK      = 0xFD;
    static constexpr uint8_t CMD_LINE      = 0x21;
    static constexpr uint8_t CMD_RECT      = 0x22;
    static constexpr uint8_t CMD_COPY      = 0x23;
    static constexpr uint8_t CMD_DIM       = 0x24;
    static constexpr uint8_t CMD_CLEAR     = 0x25;
    static constexpr uint8_t CMD_FILL      = 0x26;
    static constexpr uint8_t CMD_SCROLL_SETUP = 0x27;
    static constexpr uint8_t CMD_SCROLL_OFF = 0x2E;
    static constexpr uint8_t CMD_SCROLL_ON = 0x2F;

    // Common RGB565 colours
    static constexpr uint16_t BLACK   = 0x0000;
    static constexpr uint16_t WHITE   = 0xFFFF;
    static constexpr uint16_t RED     = 0xF800;
    static constexpr uint16_t GREEN   = 0x07E0;
    static constexpr uint16_t BLUE    = 0x001F;
    static constexpr uint16_t YELLOW  = 0xFFE0;
    static constexpr uint16_t CYAN    = 0x07FF;
    static constexpr uint16_t MAGENTA = 0xF81F;

    /** @brief Pack 8-bit R, G, B into RGB565. */
    static constexpr uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
        return static_cast<uint16_t>(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
    }

    /**
     * @brief Construct the driver and run the vendor initialisation sequence.
     *
     * Hardware reset (100 ms settle), display off, contrast `FF FF FF`, master
     * current 7/16, 65k colours with column remap and reversed COM scan, VCC
     * external, power save off, 100 ms VCC wait, scroll off, display on. The
     * display RAM is cleared and the framebuffer set to black.
     *
     * @param connection Display connection (CS#, D/C#, RES#; no BUSY).
     * @param buffer     Framebuffer, BUFFER_SIZE (12 288) bytes.
     */
    SSD1331Minimal(CONN& connection, uint8_t* buffer)
        : _connection(connection), _fb(buffer), _remap(0x72), _valid(true) {
        if (_fb == nullptr || !_connection.valid()) {
            _valid = false;
            return;
        }
        clear(BLACK);
        _init_sequence();
    }

    /** @brief True when construction succeeded. */
    bool valid() const { return _valid; }

    /** @brief Panel width in pixels (96). */
    uint16_t width() const { return WIDTH; }
    /** @brief Panel height in pixels (64). */
    uint16_t height() const { return HEIGHT; }

    /**
     * @brief Fill the framebuffer with one colour. The display is unchanged until show().
     * @param color RGB565 colour (default black).
     */
    void clear(uint16_t color = BLACK) {
        uint8_t hi = static_cast<uint8_t>(color >> 8);
        uint8_t lo = static_cast<uint8_t>(color & 0xFF);
        for (size_t i = 0; i < BUFFER_SIZE; i += 2) {
            _fb[i] = hi;
            _fb[i + 1] = lo;
        }
    }

    /**
     * @brief Set one framebuffer pixel. Out-of-range coordinates are ignored.
     * @param x     Column 0..95.
     * @param y     Row 0..63.
     * @param color RGB565 colour (default white).
     */
    void set_pixel(int16_t x, int16_t y, uint16_t color = WHITE) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return;
        size_t i = (static_cast<size_t>(y) * WIDTH + x) * 2;
        _fb[i] = static_cast<uint8_t>(color >> 8);
        _fb[i + 1] = static_cast<uint8_t>(color & 0xFF);
    }

    /**
     * @brief Send the whole framebuffer to the display RAM.
     *
     * Sets the column window 0..95 and the row window 0..63 and writes
     * 12 288 bytes in 192-byte (one row) transfers.
     */
    void show() {
        if (!_valid) return;
        _window(0, 0, WIDTH - 1, HEIGHT - 1);
        for (size_t off = 0; off < BUFFER_SIZE; off += 2 * WIDTH)
            _connection.sendData(_fb + off, 2 * WIDTH);
    }

    /** @brief Switch the display on (`0xAF`); the RAM content is shown. */
    void display_on() { _cmd(CMD_DISPLAY_ON); }
    /** @brief Switch the display off (`0xAE`); the RAM content is kept. */
    void display_off() { _cmd(CMD_DISPLAY_OFF); }

protected:
    /** @brief One command byte or one parameter (all with D/C# low). */
    void _cmd(uint8_t b) { _connection.sendCommand(b); }
    void _cmd(uint8_t c, uint8_t a) { _cmd(c); _cmd(a); }
    void _cmd(uint8_t c, uint8_t a, uint8_t b) { _cmd(c); _cmd(a); _cmd(b); }

    /** @brief Set the column and row window for following pixel data. */
    void _window(uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1) {
        _cmd(CMD_COLUMN, x0, x1);
        _cmd(CMD_ROW, y0, y1);
    }

    /** @brief Vendor initialisation. */
    void _init_sequence() {
        _connection.reset();
        _connection.delay(100);
        _cmd(CMD_DISPLAY_OFF);
        _cmd(CMD_CONTRAST_A, 0xFF);
        _cmd(CMD_CONTRAST_B, 0xFF);
        _cmd(CMD_CONTRAST_C, 0xFF);
        _cmd(CMD_MASTER_CURRENT, 0x06);
        _cmd(CMD_PRECHARGE_A, 0x64);
        _cmd(CMD_PRECHARGE_B, 0x78);
        _cmd(CMD_PRECHARGE_C, 0x64);
        _cmd(CMD_REMAP, _remap);
        _cmd(CMD_START_LINE, 0x00);
        _cmd(CMD_OFFSET, 0x00);
        _cmd(CMD_MODE_NORMAL);
        _cmd(CMD_MUX, 0x3F);
        _cmd(CMD_MASTER_CONFIG, 0x8E);
        _cmd(CMD_POWER_SAVE, 0x0B);
        _cmd(CMD_PHASE, 0x31);
        _cmd(CMD_CLOCK, 0xF0);
        _cmd(CMD_PRECHARGE_LEVEL, 0x3A);
        _cmd(CMD_VCOMH, 0x3E);
        _cmd(CMD_SCROLL_OFF);
        _cmd(CMD_CLEAR, 0x00);          // clear window 0,0 .. 95,63
        _cmd(0x00); _cmd(0x5F); _cmd(0x3F);
        _connection.delay(100);
        _cmd(CMD_DISPLAY_ON);
    }

    CONN&    _connection;
    uint8_t* _fb;
    uint8_t  _remap;
    bool     _valid;
};

/**
 * @brief SSD1331 96 x 64 RGB OLED controller — full interface.
 *
 * Extends SSD1331Minimal with software drawing into the framebuffer, partial
 * transfers, the controller's graphic acceleration commands (line, rectangle
 * with fill, copy, dim and clear window, scrolling — these act on the display
 * RAM only, not on the host framebuffer), and the electrical settings
 * (contrast, master current, flip, display mode, power save, dim mode, gray
 * table).
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class SSD1331Full : public SSD1331Minimal<CONN> {
public:
    typedef SSD1331Minimal<CONN> Base;

    /** @brief Display mode values for set_display_mode(). */
    enum Mode : uint8_t {
        MODE_NORMAL = 0xA4, MODE_ALL_ON = 0xA5, MODE_ALL_OFF = 0xA6, MODE_INVERSE = 0xA7
    };

    SSD1331Full(CONN& connection, uint8_t* buffer) : Base(connection, buffer) {}

    // ---- framebuffer ---------------------------------------------------------------------

    /** @brief RGB565 colour of one framebuffer pixel (0 when out of range). */
    uint16_t get_pixel(int16_t x, int16_t y) const {
        if (x < 0 || y < 0 || x >= Base::WIDTH || y >= Base::HEIGHT) return 0;
        size_t i = (static_cast<size_t>(y) * Base::WIDTH + x) * 2;
        return static_cast<uint16_t>((this->_fb[i] << 8) | this->_fb[i + 1]);
    }

    /** @brief Software line into the framebuffer (Bresenham). */
    void draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, uint16_t color) {
        int16_t dx = x1 > x0 ? x1 - x0 : x0 - x1;
        int16_t dy = y1 > y0 ? y0 - y1 : y1 - y0;   // negative |dy|
        int16_t sx = x0 < x1 ? 1 : -1;
        int16_t sy = y0 < y1 ? 1 : -1;
        int16_t err = dx + dy;
        for (;;) {
            this->set_pixel(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int16_t e2 = static_cast<int16_t>(2 * err);
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** @brief Software rectangle (outline, or filled when @p fill) into the framebuffer. */
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t color, bool fill = false) {
        if (w <= 0 || h <= 0) return;
        for (int16_t j = 0; j < h; j++)
            for (int16_t i = 0; i < w; i++)
                if (fill || i == 0 || j == 0 || i == w - 1 || j == h - 1)
                    this->set_pixel(x + i, y + j, color);
    }

    /** @brief Copy a w x h RGB565 bitmap (row-major, host order) to (x, y) in the framebuffer. */
    void blit(int16_t x, int16_t y, const uint16_t* bitmap, int16_t w, int16_t h) {
        for (int16_t j = 0; j < h; j++)
            for (int16_t i = 0; i < w; i++)
                this->set_pixel(x + i, y + j, bitmap[j * w + i]);
    }

    /**
     * @brief Send only a rectangle of the framebuffer (one window, row by row).
     * @param x,y,w,h Region, clipped to the panel.
     */
    void show_region(int16_t x, int16_t y, int16_t w, int16_t h) {
        if (!this->_valid) return;
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        if (x + w > Base::WIDTH)  w = Base::WIDTH - x;
        if (y + h > Base::HEIGHT) h = Base::HEIGHT - y;
        if (w <= 0 || h <= 0) return;
        this->_window(x, y, x + w - 1, y + h - 1);
        for (int16_t j = 0; j < h; j++)
            this->_connection.sendData(this->_fb + (static_cast<size_t>(y + j) * Base::WIDTH + x) * 2,
                                       static_cast<size_t>(w) * 2);
    }

    // ---- graphic acceleration (display RAM only) -----------------------------------------

    /** @brief Hardware line (`0x21`) on the display RAM; coordinates 0..95 / 0..63. */
    void hw_line(uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1, uint16_t color) {
        this->_cmd(Base::CMD_LINE);
        this->_cmd(x0); this->_cmd(y0); this->_cmd(x1); this->_cmd(y1);
        _color(color);
    }

    /**
     * @brief Hardware rectangle (`0x26` fill enable + `0x22`) on the display RAM.
     * @param x,y,w,h    Position and size (w, h >= 1).
     * @param line_color Outline RGB565.
     * @param fill_color Fill RGB565 (used when @p fill).
     * @param fill       Fill the interior.
     */
    void hw_rect(uint8_t x, uint8_t y, uint8_t w, uint8_t h, uint16_t line_color,
                 uint16_t fill_color = 0, bool fill = false) {
        if (w == 0 || h == 0) return;
        this->_cmd(Base::CMD_FILL, fill ? 0x01 : 0x00);
        this->_cmd(Base::CMD_RECT);
        this->_cmd(x); this->_cmd(y);
        this->_cmd(static_cast<uint8_t>(x + w - 1)); this->_cmd(static_cast<uint8_t>(y + h - 1));
        _color(line_color);
        _color(fill_color);
    }

    /** @brief Clear a window of the display RAM (`0x25`). */
    void hw_clear(uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1) { _window4(Base::CMD_CLEAR, x0, y0, x1, y1); }
    /** @brief Dim a window of the display RAM (`0x24`). */
    void hw_dim(uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1) { _window4(Base::CMD_DIM, x0, y0, x1, y1); }

    /** @brief Copy a window of the display RAM to (x, y) (`0x23`). */
    void hw_copy(uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1, uint8_t x, uint8_t y) {
        this->_cmd(Base::CMD_COPY);
        this->_cmd(x0); this->_cmd(y0); this->_cmd(x1); this->_cmd(y1);
        this->_cmd(x); this->_cmd(y);
    }

    /**
     * @brief Configure and start continuous scrolling (`0x27`, `0x2F`).
     * @param horizontal Columns per step, 0..95 (0 = none).
     * @param start_row  First scrolled row.
     * @param rows       Number of scrolled rows.
     * @param vertical   Rows per step, 0..63 (0 = none).
     * @param interval   0 = 6, 1 = 10, 2 = 100, 3 = 200 frames per step.
     */
    void scroll(uint8_t horizontal, uint8_t start_row, uint8_t rows, uint8_t vertical, uint8_t interval = 0) {
        this->_cmd(Base::CMD_SCROLL_SETUP);
        this->_cmd(horizontal); this->_cmd(start_row); this->_cmd(rows);
        this->_cmd(vertical); this->_cmd(interval & 3);
        this->_cmd(Base::CMD_SCROLL_ON);
    }
    /** @brief Stop scrolling (`0x2E`). The display RAM must be rewritten afterwards. */
    void scroll_stop() { this->_cmd(Base::CMD_SCROLL_OFF); }

    // ---- settings ------------------------------------------------------------------------

    /** @brief Contrast of colour channels A, B, C (`0x81..0x83`), 0..255. */
    void set_contrast(uint8_t a, uint8_t b, uint8_t c) {
        this->_cmd(Base::CMD_CONTRAST_A, a);
        this->_cmd(Base::CMD_CONTRAST_B, b);
        this->_cmd(Base::CMD_CONTRAST_C, c);
    }
    /** @brief Master current (`0x87`), n/16 for n = 1..16. */
    void set_master_current(uint8_t n) {
        if (n < 1) n = 1;
        if (n > 16) n = 16;
        this->_cmd(Base::CMD_MASTER_CURRENT, static_cast<uint8_t>(n - 1));
    }
    /** @brief Display mode: MODE_NORMAL, MODE_ALL_ON, MODE_ALL_OFF, MODE_INVERSE. */
    void set_display_mode(Mode mode) { this->_cmd(static_cast<uint8_t>(mode)); }
    /** @brief Raw remap and colour depth byte (`0xA0`). */
    void set_remap(uint8_t flags) {
        this->_remap = flags;
        this->_cmd(Base::CMD_REMAP, flags);
    }
    /** @brief Mirror horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4). */
    void set_flip(bool horizontal, bool vertical) {
        uint8_t r = static_cast<uint8_t>(this->_remap & ~0x12);
        if (horizontal) r |= 0x02;
        if (vertical)   r |= 0x10;
        set_remap(r);
    }
    /** @brief Power save mode (`0xB0`): true = `0x1A` enable, false = `0x0B` disable. */
    void set_power_save(bool on) { this->_cmd(Base::CMD_POWER_SAVE, on ? 0x1A : 0x0B); }
    /** @brief Dim mode (`0xAB` + `0xAC`): contrasts A/B/C and pre-charge voltage 0..31. */
    void dim_mode(uint8_t a, uint8_t b, uint8_t c, uint8_t precharge) {
        this->_cmd(Base::CMD_DIM_SETTING);
        this->_cmd(0x00); this->_cmd(a); this->_cmd(b); this->_cmd(c); this->_cmd(precharge & 0x1F);
        this->_cmd(Base::CMD_DISPLAY_DIM);
    }
    /** @brief Restore the linear gray scale table (`0xB9`). */
    void gray_linear() { this->_cmd(Base::CMD_GRAY_LINEAR); }
    /** @brief Send any command with parameters (all with D/C# low). */
    void send_command(uint8_t command, const uint8_t* params = nullptr, size_t len = 0) {
        this->_cmd(command);
        for (size_t i = 0; i < len; i++) this->_cmd(params[i]);
    }

private:
    /** @brief RGB565 -> three 5/6/5-bit operands (C = red, B = green, A = blue). */
    void _color(uint16_t c) {
        this->_cmd(static_cast<uint8_t>(((c >> 11) & 0x1F) << 1));
        this->_cmd(static_cast<uint8_t>((c >> 5) & 0x3F));
        this->_cmd(static_cast<uint8_t>((c & 0x1F) << 1));
    }
    void _window4(uint8_t cmd, uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1) {
        this->_cmd(cmd);
        this->_cmd(x0); this->_cmd(y0); this->_cmd(x1); this->_cmd(y1);
    }
};
