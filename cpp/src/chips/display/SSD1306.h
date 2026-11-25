#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "MonoOledBase.h"

/** @brief SSD1306 128 x 64 OLED controller-driver — minimal interface (I²C).
 *
 *  Drives a monochrome SSD1306 OLED panel (0.96" 128 x 64 or 128 x 32 modules) over I²C at
 *  0x3C (SA0 = low) or 0x3D (SA0 = high). The chip has an exact 128-column RAM, so there is no
 *  column offset.
 *
 *  All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 = top
 *  pixel of the page; 1024 bytes at the maximum size) that lives inside the object; show() sends it
 *  page by page (page addressing, the reset default). The initialization follows the datasheet and
 *  the charge-pump application note: setup stream with the display off (charge pump, page
 *  addressing), RAM cleared, display on + 100 ms. With @p external_vcc = false (default) the
 *  internal charge pump makes the OLED supply VCC from VBAT.
 *
 *  No heap allocation, no exceptions; invalid arguments are reported through valid().
 *
 *  @param connection   Connection at 0x3C or 0x3D.
 *  @param width        Visible columns, 1..128 (default 128).
 *  @param height       Rows, a multiple of 8, 16..64 (default 64); up to 32 uses sequential COM wiring.
 *  @param external_vcc True when the board supplies VCC (charge pump stays off).
 */
class SSD1306Minimal {
public:
    static constexpr uint16_t WIDTH  = 128;
    static constexpr uint16_t HEIGHT = 64;
    /** @brief Largest write in bytes including the control byte. */
    static constexpr size_t I2C_CHUNK = MonoOledI2CBus::CHUNK;

    static constexpr uint8_t CMD_ADDR_MODE   = 0x20;
    static constexpr uint8_t CMD_COL_RANGE   = 0x21;
    static constexpr uint8_t CMD_PAGE_RANGE  = 0x22;
    static constexpr uint8_t CMD_START_LINE  = 0x40;   // | A[5:0]
    static constexpr uint8_t CMD_CONTRAST    = 0x81;
    static constexpr uint8_t CMD_CHARGE_PUMP = 0x8D;
    static constexpr uint8_t CMD_SEG_REMAP   = 0xA0;   // | ADC
    static constexpr uint8_t CMD_SCROLL_AREA = 0xA3;
    static constexpr uint8_t CMD_ALL_ON      = 0xA4;   // | 1
    static constexpr uint8_t CMD_INVERT      = 0xA6;   // | 1
    static constexpr uint8_t CMD_MULTIPLEX   = 0xA8;
    static constexpr uint8_t CMD_DISPLAY     = 0xAE;   // | 1
    static constexpr uint8_t CMD_PAGE        = 0xB0;   // | A[2:0]
    static constexpr uint8_t CMD_SCAN        = 0xC0;   // | (scan << 3)
    static constexpr uint8_t CMD_OFFSET      = 0xD3;
    static constexpr uint8_t CMD_CLOCK       = 0xD5;
    static constexpr uint8_t CMD_PRECHARGE   = 0xD9;
    static constexpr uint8_t CMD_COM         = 0xDA;
    static constexpr uint8_t CMD_VCOMH       = 0xDB;

    /** @brief Addressing modes for set_addressing_mode() / show(). */
    enum AddressingMode : uint8_t { ADDR_HORIZONTAL = 0, ADDR_VERTICAL = 1, ADDR_PAGE = 2 };

    /** @brief Construct the driver and run the initialization sequence (about 100 ms). */
    SSD1306Minimal(Connection& connection, uint16_t width = 128, uint16_t height = 64, bool external_vcc = false)
        : SSD1306Minimal(nullptr, &connection, width, height, external_vcc) {}

    /** @brief True when the arguments were valid. */
    bool valid() const { return _valid; }
    /** @brief Visible columns of the framebuffer. */
    uint16_t width() const { return _width; }
    /** @brief Rows of the framebuffer. */
    uint16_t height() const { return _height; }

    /** @brief Zero the framebuffer. Call show() to update the panel. */
    void clear() { _canvas.clear(); }

    /** @brief Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *  @param x  Column, 0 .. width - 1.
     *  @param y  Row, 0 .. height - 1.
     *  @param on True to set the pixel, false to clear it.
     */
    void set_pixel(int16_t x, int16_t y, bool on = true) { if (_valid) _canvas.set_pixel(x, y, on); }

    /** @brief Write the whole framebuffer to the display RAM (per page in page addressing; one stream in horizontal / vertical addressing).
     *  @return False (nothing sent) while a hardware scroll is active: RAM access is prohibited then.
     */
    bool show();

protected:
    SSD1306Minimal(MonoOledBus* bus, Connection* connection, uint16_t width, uint16_t height, bool external_vcc);
    /** @brief Constructor for the SPI classes: the transport is already built. */
    SSD1306Minimal(MonoOledBus& bus, uint16_t width, uint16_t height, bool external_vcc)
        : SSD1306Minimal(&bus, nullptr, width, height, external_vcc) {}

    void _sleep_ms(uint32_t ms);
    void _command(const uint8_t* bytes, size_t len);
    void _command1(uint8_t a);
    void _command2(uint8_t a, uint8_t b);
    void _address(uint8_t page);
    void _show_page(uint8_t page);
    void _set_ranges(uint8_t page_first, uint8_t page_last);
    bool _show_all();

    MonoOledI2CBus _i2c;
    MonoOledBus&   _bus;
    uint8_t        _storage[WIDTH * (HEIGHT / 8)];
    MonoFramebuffer _canvas;
    bool           _valid;
    uint16_t       _width;
    uint16_t       _height;
    uint8_t        _pages;
    bool           _external_vcc;
    uint8_t        _mode;
    bool           _scrolling;
    uint8_t        _adc;
    uint8_t        _scan;

private:
    void _init_sequence();
};

/** @brief SSD1306 OLED controller-driver — full interface (I²C).
 *
 *  Extends SSD1306Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip, start
 *  line, display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH
 *  level, charge pump, addressing mode, hardware scrolling and raw commands. The SSD1306 cannot be
 *  read over its serial interfaces.
 */
class SSD1306Full : public SSD1306Minimal {
public:
    /** @brief Construct the driver and run the initialization sequence (see SSD1306Minimal). */
    SSD1306Full(Connection& connection, uint16_t width = 128, uint16_t height = 64, bool external_vcc = false)
        : SSD1306Minimal(nullptr, &connection, width, height, external_vcc) {}

    /** @brief Return a framebuffer pixel; out-of-range coordinates read as false. */
    bool get_pixel(int16_t x, int16_t y) const { return _canvas.get_pixel(x, y); }
    /** @brief Set (or clear) the whole framebuffer. */
    void fill(bool on = true) { _canvas.fill(on); }
    /** @brief Draw a Bresenham line into the framebuffer. */
    void draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on = true) { _canvas.draw_line(x0, y0, x1, y1, on); }
    /** @brief Draw a rectangle outline or a filled rectangle into the framebuffer. */
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on = true, bool fill = false) {
        _canvas.draw_rect(x, y, w, h, on, fill);
    }
    /** @brief Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer.
     *
     *  @p columns holds @p height_pages groups of columns, page by page: byte `p * n + c` is
     *  column `c` of page row `p` (n = len / height_pages). Clipped to the framebuffer.
     *  @param x            Left column of the bitmap.
     *  @param y            Top row; must be a multiple of 8.
     *  @param columns      Bitmap bytes.
     *  @param len          Number of bytes in @p columns.
     *  @param height_pages Number of 8-pixel-high rows of the bitmap.
     *  @return False (and nothing drawn) if @p y is not a multiple of 8.
     */
    bool blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages = 1) {
        return _canvas.blit(x, y, columns, len, height_pages);
    }
    /** @brief Write one framebuffer page to the display RAM (cheaper partial update).
     *  @return False while a hardware scroll is active (RAM access prohibited); out-of-range pages are ignored.
     */
    bool show_page(uint8_t page);

    /** @brief Set the segment output current (contrast), 0..255 (reset 0x7F). */
    void set_contrast(uint8_t value);
    /** @brief Switch the display on or off (RAM and settings are kept). */
    void set_display(bool on);
    /** @brief Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards. */
    void sleep();
    /** @brief Display on, then wait 100 ms (tAF) for SEG / COM to turn on. */
    void wake();
    /** @brief Invert the displayed image without changing RAM. */
    void set_invert(bool on);
    /** @brief Force all pixels on (test mode, RAM unchanged). */
    void set_all_on(bool on);
    /** @brief Mirror columns (segment re-map) and/or rows (COM scan direction); the framebuffer is unchanged. */
    void set_flip(bool horizontal, bool vertical);
    /** @brief Set the RAM line shown at COM0 (hardware vertical scroll), 0..63. */
    void set_start_line(uint8_t line);
    /** @brief Shift the COM mapping, 0..63. */
    void set_display_offset(uint8_t offset);
    /** @brief Set the multiplex ratio, 16..64. */
    void set_multiplex(uint8_t ratio);
    /** @brief Set the clock divide ratio (1..16) and oscillator setting (0..15, 8 = reset). */
    void set_clock(uint8_t divide, uint8_t osc);
    /** @brief Set the pre-charge phase 1 and phase 2 periods (1..15 display clocks each). */
    void set_precharge(uint8_t phase1, uint8_t phase2);
    /** @brief Select the COM pin configuration: alternative (true, reset) or sequential; @p remap enables COM left / right remap. */
    void set_com_config(bool alternative, bool remap = false);
    /** @brief Set the VCOMH deselect level (only bits 6:4 used; 0x20 reset). */
    void set_vcomh(uint8_t value);
    /** @brief Enable (0x14) or disable (0x10) the internal charge pump; takes effect with the display on. */
    void set_charge_pump(bool enabled);
    /** @brief Select the RAM addressing mode used by show(): ADDR_PAGE (reset), ADDR_HORIZONTAL or ADDR_VERTICAL. */
    void set_addressing_mode(AddressingMode mode);

    /** @brief Set up a horizontal scroll, one column per step (start with scroll_start());
     *  @p interval is a table index 0..7 = 5, 64, 128, 256, 3, 4, 25, 2 frames. Do not call while scrolling. */
    void set_scroll_horizontal(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval);
    /** @brief Set up a vertical + horizontal (diagonal) scroll (@p rows 1..63 per step). */
    void set_scroll_vertical(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval, uint8_t rows);
    /** @brief Set the vertical scroll area: @p top_fixed rows (0..63) fixed, @p scroll_rows (1..64) scrolling. */
    void set_scroll_area(uint8_t top_fixed, uint8_t scroll_rows);
    /** @brief Activate the configured scroll (0x2F); show() / show_page() are refused until scroll_stop(). */
    void scroll_start();
    /** @brief Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires. */
    void scroll_stop();

    /** @brief Send raw bytes as one command stream. */
    void send_command(const uint8_t* bytes, size_t len);

protected:
    SSD1306Full(MonoOledBus& bus, uint16_t width, uint16_t height, bool external_vcc)
        : SSD1306Minimal(bus, width, height, external_vcc) {}
};

namespace ssd1306_detail {
/** Base-from-member: the transport must exist before the driver base class runs its initialisation. */
template <typename CONN>
struct SpiBusHolder {
    explicit SpiBusHolder(CONN& connection) : spi_bus(connection) {}
    MonoOledSpiBus<CONN> spi_bus;
};
}  // namespace ssd1306_detail

/** @brief SSD1306 over 4-wire SPI — minimal interface.
 *
 *  Same framebuffer, drawing and initialization as SSD1306Minimal, but driven through a display
 *  connection (`DisplayConnectionBase` family): CS, D/C, RES optional. The chip must be strapped for
 *  4-wire SPI (BS0 = BS1 = BS2 = 0). SPI mode 0, up to 10 MHz (4 MHz recommended).
 *
 *  @tparam CONN Display connection class.
 */
template <typename CONN>
class SSD1306SpiMinimal : private ssd1306_detail::SpiBusHolder<CONN>, public SSD1306Minimal {
public:
    explicit SSD1306SpiMinimal(CONN& connection, uint16_t width = 128, uint16_t height = 64, bool external_vcc = false)
        : ssd1306_detail::SpiBusHolder<CONN>(connection),
          SSD1306Minimal(this->spi_bus, width, height, external_vcc) {}
};

/** @brief SSD1306 over 4-wire SPI — full interface (see SSD1306Full).
 *
 *  @tparam CONN Display connection class.
 */
template <typename CONN>
class SSD1306SpiFull : private ssd1306_detail::SpiBusHolder<CONN>, public SSD1306Full {
public:
    explicit SSD1306SpiFull(CONN& connection, uint16_t width = 128, uint16_t height = 64, bool external_vcc = false)
        : ssd1306_detail::SpiBusHolder<CONN>(connection),
          SSD1306Full(this->spi_bus, width, height, external_vcc) {}
};
