#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief UC1611s graphic LCD controller-driver — minimal interface.
 *
 *  Drives an EA DOGXL240-7 (240 x 128 dots, 3.3 V) in 1 bit per pixel black/white mode
 *  over I²C. The chip carries the command/data bit in its I²C address, so it answers on
 *  two consecutive 7-bit addresses: `0x38 + 2n` for commands and `0x39 + 2n` for display
 *  RAM data (n = A3 << 1 | A2, the two address pins). The driver therefore takes two
 *  connections on the same bus.
 *
 *  All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 =
 *  top pixel of the page, 3840 bytes at 240 x 128) that lives inside the object; `show()`
 *  sends it to the display RAM. The initialization follows the DOG module's bottom-view
 *  example: System Reset, temperature compensation -0.10 %/degC, COM end 127,
 *  potentiometer (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.
 *
 *  No heap allocation, no exceptions; invalid arguments are reported through valid().
 *
 *  @param command Connection at the command address (0x38, 0x3A, 0x3C or 0x3E).
 *  @param data    Connection at the data address (command address + 1).
 *  @param width   Columns, 1..240 (default 240).
 *  @param height  Rows, a multiple of 8, 8..128 (default 128).
 */
class UC1611sMinimal {
public:
    static constexpr uint16_t WIDTH  = 240;
    static constexpr uint16_t HEIGHT = 128;
    /** @brief Largest data write in bytes (the 32-byte limit of some I²C layers). */
    static constexpr size_t I2C_CHUNK = 32;

    static constexpr uint8_t CMD_TEMP          = 0x24;   // | TC[1:0]
    static constexpr uint8_t CMD_PANEL         = 0x28;   // | PC[1:0]
    static constexpr uint8_t CMD_PUMP          = 0x2C;   // | PC[3:2]
    static constexpr uint8_t CMD_SCROLL_LO     = 0x40;   // | SL[3:0]
    static constexpr uint8_t CMD_SCROLL_HI     = 0x50;   // | SL[7:4]
    static constexpr uint8_t CMD_PAGE_LO       = 0x60;   // | PA[3:0]
    static constexpr uint8_t CMD_PAGE_HI       = 0x70;   // | PA[6:4]
    static constexpr uint8_t CMD_POTENTIOMETER = 0x81;
    static constexpr uint8_t CMD_ISOLATION     = 0x82;
    static constexpr uint8_t CMD_PARTIAL       = 0x84;   // | LC[9:8]
    static constexpr uint8_t CMD_RAM_ADDR      = 0x88;   // | AC[2:0]
    static constexpr uint8_t CMD_FIXED         = 0x90;   // | FL[3:0]
    static constexpr uint8_t CMD_LINE_RATE     = 0xA0;   // | LC[5:4]
    static constexpr uint8_t CMD_ALL_ON        = 0xA4;   // | DC1
    static constexpr uint8_t CMD_INVERSE       = 0xA6;   // | DC0
    static constexpr uint8_t CMD_DISPLAY       = 0xA8;   // | DC[4:2]
    static constexpr uint8_t CMD_MAPPING       = 0xC0;
    static constexpr uint8_t CMD_NLINE         = 0xC8;
    static constexpr uint8_t CMD_PATTERN       = 0xD0;   // | DC[7:5]
    static constexpr uint8_t CMD_RESET         = 0xE2;
    static constexpr uint8_t CMD_BIAS          = 0xE8;   // | BR[1:0]
    static constexpr uint8_t CMD_COM_END       = 0xF1;
    static constexpr uint8_t CMD_PART_START    = 0xF2;
    static constexpr uint8_t CMD_PART_END      = 0xF3;

    /** @brief Construct the driver and run the initialization sequence (about 400 ms). */
    UC1611sMinimal(Connection& command, Connection& data, uint16_t width = WIDTH, uint16_t height = HEIGHT)
        : UC1611sMinimal(command, data, width, height, false, 0x8F) {}

    /** @brief True when the arguments were valid and the chip was initialised. */
    bool valid() const { return _valid; }

    /** @brief Columns of the framebuffer. */
    uint16_t width() const { return _width; }
    /** @brief Rows of the framebuffer. */
    uint16_t height() const { return _height; }

    /** @brief Zero the framebuffer. Call show() to update the panel. */
    void clear();

    /** @brief Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *  @param x  Column, 0 .. width - 1.
     *  @param y  Row, 0 .. height - 1.
     *  @param on True to set the pixel, false to clear it.
     */
    void set_pixel(int16_t x, int16_t y, bool on = true);

    /** @brief Write the whole framebuffer to the display RAM (page + column, then width data bytes per page). */
    void show();

protected:
    /** @brief Construct with explicit orientation and contrast (UC1611sFull passes its options). */
    UC1611sMinimal(Connection& command, Connection& data, uint16_t width, uint16_t height,
                   bool topView, uint8_t contrast);

    static void _sleep_ms(uint32_t ms);
    void _command(const uint8_t* bytes, size_t len);
    void _command1(uint8_t a);
    void _command2(uint8_t a, uint8_t b);
    void _write_data(const uint8_t* data, size_t len);
    void _set_address(uint8_t page, uint8_t column = 0);
    uint8_t _mapping() const;
    void _reset_chip();
    void _configure();
    void _enable();
    void _show_page(uint8_t page);

    static constexpr size_t   FB_SIZE   = (size_t)WIDTH * (HEIGHT / 8);
    static constexpr uint8_t  PAGES_RAM = 20;   // RAM pages cleared at init (160 rows / 8)

    Connection& _cmd_conn;
    Connection& _data_conn;
    bool     _valid;
    uint16_t _width;
    uint16_t _height;
    uint8_t  _pages;
    uint8_t  _mx;
    uint8_t  _my;
    uint8_t  _pattern;
    uint8_t  _contrast;
    uint8_t  _rx[I2C_CHUNK + 1];
    uint8_t  _fb[FB_SIZE];
};

/** @brief UC1611s graphic LCD controller-driver — full interface.
 *
 *  Extends UC1611sMinimal with drawing primitives, contrast, sleep, invert, flip / top view,
 *  hardware scroll with fixed lines, bias ratio, temperature compensation, line rate, charge
 *  pump / external VLCD, panel loading, partial display, N-line inversion, isolation clock,
 *  the four RAM patterns, status and RAM reads, and raw commands.
 *
 *  @param command  Connection at the command address.
 *  @param data     Connection at the data address (command address + 1).
 *  @param width    Columns, 1..240 (default 240).
 *  @param height   Rows, a multiple of 8, 8..128 (default 128).
 *  @param topView  True for the 12:00 viewing direction (MY = 1, MX = 0).
 *  @param contrast Potentiometer PM 0..255 (default 0x8F, the DOG example).
 */
class UC1611sFull : public UC1611sMinimal {
public:
    UC1611sFull(Connection& command, Connection& data, uint16_t width = WIDTH, uint16_t height = HEIGHT,
                bool topView = false, uint8_t contrast = 0x8F)
        : UC1611sMinimal(command, data, width, height, topView, contrast) {}

    /** @brief Return a framebuffer pixel; out-of-range coordinates read as false. */
    bool get_pixel(int16_t x, int16_t y) const;

    /** @brief Set (or clear) the whole framebuffer. */
    void fill(bool on = true);

    /** @brief Draw a Bresenham line into the framebuffer. */
    void draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on = true);

    /** @brief Draw a rectangle outline or a filled rectangle into the framebuffer.
     *  @param x,y  Top-left corner.
     *  @param w,h  Size in pixels.
     *  @param on   True to set pixels, false to clear them.
     *  @param fill True for a filled rectangle.
     */
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on = true, bool fill = false);

    /** @brief Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer.
     *
     *  @p columns holds @p height_pages groups of columns, page by page: byte `p * n + c` is
     *  column `c` of page row `p` (n = len / height_pages). Clipped to the framebuffer.
     *
     *  @param x            Left column.
     *  @param y            Top row, a multiple of 8 (otherwise nothing is drawn).
     *  @param columns      Bitmap bytes.
     *  @param len          Number of bytes in @p columns.
     *  @param height_pages Number of 8-pixel-high rows of the bitmap.
     */
    void blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages = 1);

    /** @brief Write one framebuffer page to the display RAM (cheaper partial update). */
    void show_page(uint8_t page);

    /** @brief Set the potentiometer PM that adjusts VLCD (contrast), 0..255. */
    void set_contrast(uint8_t value);

    /** @brief Switch the display on, or into sleep (registers and RAM kept, below 1 uA). */
    void set_display(bool on);

    /** @brief Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active. */
    void sleep();

    /** @brief Leave sleep mode; the chip restores VLCD itself (the driver waits 1 ms). */
    void wake();

    /** @brief Invert the displayed image without changing RAM. */
    void set_invert(bool on);

    /** @brief Force all pixels on (test mode, RAM unchanged). */
    void set_all_on(bool on);

    /** @brief Mirror columns (MX) and/or rows (MY). MX is applied to RAM writes: call show() afterwards. */
    void set_flip(bool mirror_x, bool mirror_y);

    /** @brief Scroll the image up by @p line rows (hardware scroll, wraps at 160 rows); 0 .. 159 - 2 * fixed lines. */
    void set_scroll_line(uint8_t line);

    /** @brief Keep the first `2 * lines` rows out of the scroll (FL, 0..15). */
    void set_fixed_lines(uint8_t lines);

    /** @brief Bias ratio: 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12. */
    void set_bias_ratio(uint8_t ratio);

    /** @brief Temperature compensation: 0 = -0.05, 1 = -0.10, 2 = -0.15, 3 = 0.00 %/degC. */
    void set_temperature_compensation(uint8_t curve);

    /** @brief Line rate (On/Off mode): 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second. */
    void set_line_rate(uint8_t rate);

    /** @brief Internal 11x charge pump (true, reset) or external VLCD supply (false). */
    void set_pump(bool internal);

    /** @brief Panel capacitance: true = 33..55 nF (reset), false = up to 33 nF. */
    void set_panel_loading(bool high);

    /** @brief Scan only rows @p start .. @p end (the rest sleeps), or return to full scan.
     *  @param on    True to enable partial display.
     *  @param start First scanned row (DST).
     *  @param end   Last scanned row (DEN); 0xFFFF selects height - 1.
     */
    void set_partial_display(bool on, uint8_t start = 0, uint16_t end = 0xFFFF);

    /** @brief N-line inversion (cross-talk tuning): @p lines 0 (off) .. 63, optional XOR mode. */
    void set_nline_inversion(uint8_t lines, bool xor_mode = false);

    /** @brief Isolation clock before (ISOF) and after (ISOB) the COM pulse, 0..15 each. */
    void set_isolation_clock(uint8_t front, uint8_t back);

    /** @brief Select which of the four RAM screens (patterns, 0..3) is displayed. */
    void set_display_pattern(uint8_t pattern);

    /** @brief Select the RAM screen (0..3) that show() / show_page() write to (PA[6:5]). */
    void set_write_pattern(uint8_t pattern);

    /** @brief Read the three status bytes from the command address.
     *  @param status Output: Status1 (Ver, MX, MY, WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]),
     *                Status3 (product code 1 in bits 7..4, ESD flag in bit 0).
     */
    void read_status(uint8_t status[3]);

    /** @brief Read @p n bytes (at most I2C_CHUNK = 32, larger values are clipped) of display RAM; the dummy byte after the address change is discarded. */
    void read_ram(uint8_t page, uint8_t column, uint8_t* buf, uint8_t n);

    /** @brief System Reset, then reconfigure with the cached contrast and mapping (RAM is kept). */
    void reset();

    /** @brief Send raw bytes as one command transaction. */
    void send_command(const uint8_t* bytes, size_t len);

    /** @brief Send raw bytes to the display RAM (chunked) at the current RAM address. */
    void send_data(const uint8_t* bytes, size_t len);
};
