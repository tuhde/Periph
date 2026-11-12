#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief Transport hooks of the SSD1305 drivers.
 *
 *  Implemented by SSD1305I2CBus (control-byte framing on one I²C connection) and SSD1305SpiBus
 *  (a display connection; D/C low for commands, high for RAM data).
 */
class SSD1305Bus {
public:
    virtual ~SSD1305Bus() = default;
    /** @brief Send bytes as one command stream. */
    virtual void write_commands(const uint8_t* bytes, size_t len) = 0;
    /** @brief Send RAM data bytes (nullptr = zeros) in chunks. */
    virtual void write_data(const uint8_t* bytes, size_t len) = 0;
    /** @brief True if the transport can read back (I²C); SPI is write-only. */
    virtual bool can_read() const = 0;
    /** @brief Read the status byte. @return False when the transport cannot read. */
    virtual bool read_status(uint8_t& status) { (void)status; return false; }
    /** @brief Read @p n RAM bytes after the dummy byte. @return False when the transport cannot read. */
    virtual bool read_ram(uint8_t* buf, size_t n) { (void)buf; (void)n; return false; }
    /** @brief Pulse the hardware reset line, if any (no-op for I²C). */
    virtual void hardware_reset() {}
};

/** @brief I²C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data). */
class SSD1305I2CBus : public SSD1305Bus {
public:
    /** @brief Largest write in bytes including the control byte (the 32-byte limit of some I²C layers). */
    static constexpr size_t CHUNK = 32;
    explicit SSD1305I2CBus(Connection* connection = nullptr) : _conn(connection) {}
    void write_commands(const uint8_t* bytes, size_t len) override;
    void write_data(const uint8_t* bytes, size_t len) override;
    bool can_read() const override { return true; }
    bool read_status(uint8_t& status) override;
    bool read_ram(uint8_t* buf, size_t n) override;
private:
    Connection* _conn;
    uint8_t     _buf[CHUNK];
};

/** @brief SSD1305 132 x 64 OLED controller-driver — minimal interface (I²C).
 *
 *  Drives a monochrome SSD1305 OLED panel (2.23" 128 x 32 or 1.3" 128 x 64 modules) over I²C at
 *  0x3C (SA0 = low) or 0x3D (SA0 = high). The chip has 132 RAM columns; 128-pixel-wide modules
 *  usually show RAM columns 4..131, so @p column_offset defaults to 4.
 *
 *  All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 = top
 *  pixel of the page; 1056 bytes at the maximum size) that lives inside the object; show() sends it
 *  page by page (page addressing, the reset default). The chip has no charge pump: VCC (7..15 V)
 *  must be supplied by the board. The initialization follows the datasheet power-on flow: setup
 *  stream with the display off (external-VCC master configuration, page addressing), RAM cleared,
 *  display on + 100 ms.
 *
 *  No heap allocation, no exceptions; invalid arguments are reported through valid().
 *
 *  @param connection     Connection at 0x3C or 0x3D.
 *  @param width          Visible columns, 1..132 (default 128).
 *  @param height         Rows, a multiple of 8, 16..64 (default 64); up to 32 uses sequential COM wiring.
 *  @param column_offset  First RAM column shown (default 4; 0 for a true 132-column panel).
 */
class SSD1305Minimal {
public:
    static constexpr uint16_t WIDTH  = 132;
    static constexpr uint16_t HEIGHT = 64;
    /** @brief Largest write in bytes including the control byte. */
    static constexpr size_t I2C_CHUNK = SSD1305I2CBus::CHUNK;

    static constexpr uint8_t CMD_COL_LO     = 0x00;   // | A[3:0]
    static constexpr uint8_t CMD_COL_HI     = 0x10;   // | A[7:4]
    static constexpr uint8_t CMD_ADDR_MODE  = 0x20;
    static constexpr uint8_t CMD_COL_RANGE  = 0x21;
    static constexpr uint8_t CMD_PAGE_RANGE = 0x22;
    static constexpr uint8_t CMD_START_LINE = 0x40;   // | A[5:0]
    static constexpr uint8_t CMD_CONTRAST   = 0x81;
    static constexpr uint8_t CMD_BRIGHTNESS = 0x82;
    static constexpr uint8_t CMD_LUT        = 0x91;
    static constexpr uint8_t CMD_BANK_1_16  = 0x92;
    static constexpr uint8_t CMD_BANK_17_32 = 0x93;
    static constexpr uint8_t CMD_SEG_REMAP  = 0xA0;   // | ADC
    static constexpr uint8_t CMD_SCROLL_AREA = 0xA3;
    static constexpr uint8_t CMD_ALL_ON     = 0xA4;   // | 1
    static constexpr uint8_t CMD_INVERT     = 0xA6;   // | 1
    static constexpr uint8_t CMD_MULTIPLEX  = 0xA8;
    static constexpr uint8_t CMD_DIM_MODE   = 0xAB;
    static constexpr uint8_t CMD_DIM_ON     = 0xAC;
    static constexpr uint8_t CMD_MASTER     = 0xAD;
    static constexpr uint8_t CMD_DISPLAY    = 0xAE;   // | 1
    static constexpr uint8_t CMD_PAGE       = 0xB0;   // | A[2:0]
    static constexpr uint8_t CMD_SCAN       = 0xC0;   // | (scan << 3)
    static constexpr uint8_t CMD_OFFSET     = 0xD3;
    static constexpr uint8_t CMD_CLOCK      = 0xD5;
    static constexpr uint8_t CMD_AREA_COLOR = 0xD8;
    static constexpr uint8_t CMD_PRECHARGE  = 0xD9;
    static constexpr uint8_t CMD_COM        = 0xDA;
    static constexpr uint8_t CMD_VCOMH      = 0xDB;

    /** @brief Addressing modes for set_addressing_mode() / show(). */
    enum AddressingMode : uint8_t { ADDR_HORIZONTAL = 0, ADDR_VERTICAL = 1, ADDR_PAGE = 2 };

    /** @brief Construct the driver and run the initialization sequence (about 100 ms). */
    SSD1305Minimal(Connection& connection, uint16_t width = 128, uint16_t height = 64,
                  uint8_t column_offset = 4)
        : SSD1305Minimal(nullptr, &connection, width, height, column_offset) {}

    /** @brief True when the arguments were valid. */
    bool valid() const { return _valid; }
    /** @brief Visible columns of the framebuffer. */
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

    /** @brief Write the whole framebuffer to the display RAM (per page in page addressing; one stream in horizontal / vertical addressing). */
    void show();

protected:
    SSD1305Minimal(SSD1305Bus* bus, Connection* connection, uint16_t width, uint16_t height,
                  uint8_t column_offset);
    /** @brief Constructor for the SPI classes: the transport is already built. */
    SSD1305Minimal(SSD1305Bus& bus, uint16_t width, uint16_t height, uint8_t column_offset)
        : SSD1305Minimal(&bus, nullptr, width, height, column_offset) {}

    void _sleep_ms(uint32_t ms);
    void _command(const uint8_t* bytes, size_t len);
    void _command1(uint8_t a);
    void _command2(uint8_t a, uint8_t b);
    void _address(uint8_t page, uint8_t column);
    void _show_page(uint8_t page);
    void _show_all();
    void _set_ranges(uint8_t page_first, uint8_t page_last);

    SSD1305I2CBus _i2c;
    SSD1305Bus&   _bus;
    bool         _valid;
    uint16_t     _width;
    uint16_t     _height;
    uint8_t      _pages;
    uint8_t      _offset;
    uint8_t      _mode;
    uint8_t      _adc;
    uint8_t      _scan;
    uint8_t      _fb[WIDTH * (HEIGHT / 8)];

private:
    void _init_sequence();
};

/** @brief SSD1305 OLED controller-driver — full interface (I²C).
 *
 *  Extends SSD1305Minimal with drawing primitives, contrast, brightness, dim mode, sleep, invert,
 *  all-on test, flip, start line, display offset, multiplex ratio, clock / oscillator, pre-charge,
 *  COM configuration, VCOMH level, addressing mode, hardware scrolling, area-colour mode with
 *  LUT / bank colours, status and RAM reads (I²C only) and raw commands.
 */
class SSD1305Full : public SSD1305Minimal {
public:
    /** @brief Construct the driver and run the initialization sequence (see SSD1305Minimal). */
    SSD1305Full(Connection& connection, uint16_t width = 128, uint16_t height = 64,
               uint8_t column_offset = 4)
        : SSD1305Minimal(nullptr, &connection, width, height, column_offset) {}

    /** @brief Return a framebuffer pixel; out-of-range coordinates read as false. */
    bool get_pixel(int16_t x, int16_t y) const;
    /** @brief Set (or clear) the whole framebuffer. */
    void fill(bool on = true);
    /** @brief Draw a Bresenham line into the framebuffer. */
    void draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on = true);
    /** @brief Draw a rectangle outline or a filled rectangle into the framebuffer. */
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on = true, bool fill = false);
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
    bool blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages = 1);
    /** @brief Write one framebuffer page to the display RAM (cheaper partial update). */
    void show_page(uint8_t page);

    /** @brief Set the segment output current (contrast), 0..255 (reset 0x80). */
    void set_contrast(uint8_t value);
    /** @brief Switch the display on or off (RAM and settings are kept). */
    void set_display(bool on);
    /** @brief Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards. */
    void sleep();
    /** @brief Display on, then wait 100 ms (tAF) for SEG / COM to turn on. */
    void wake();
    /** @brief Display on in dim mode (true, 0xAC) or normal display on (false, 0xAF); see set_dim_mode(). */
    void set_dim(bool on);
    /** @brief Configure the BANK0 contrast and colour-bank brightness used in dim mode (0xAB). */
    void set_dim_mode(uint8_t contrast, uint8_t brightness);
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
    /** @brief Set the clock divide ratio (1..16) and oscillator setting (0..15, 7 = reset). */
    void set_clock(uint8_t divide, uint8_t osc);
    /** @brief Set the pre-charge phase 1 and phase 2 periods (1..15 display clocks each). */
    void set_precharge(uint8_t phase1, uint8_t phase2);
    /** @brief Select the COM pin configuration: alternative (true, reset) or sequential; @p remap enables COM left / right remap. */
    void set_com_config(bool alternative, bool remap = false);
    /** @brief Set the VCOMH deselect level (only bits 5:2 used; 0x34 reset). */
    void set_vcomh(uint8_t value);
    /** @brief Select the RAM addressing mode used by show(): ADDR_PAGE (reset), ADDR_HORIZONTAL or ADDR_VERTICAL. */
    void set_addressing_mode(AddressingMode mode);

    /** @brief Set up a horizontal scroll (start with scroll_start()); @p interval is a table index 0..6 = 6, 32, 64, 128, 3, 4, 2 frames; @p columns 1..4. */
    void set_scroll_horizontal(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval, uint8_t columns = 1);
    /** @brief Set up a vertical + horizontal scroll (@p columns 0..4, @p rows 1..63 per step). */
    void set_scroll_vertical(bool right, uint8_t start_page, uint8_t end_page, uint8_t interval, uint8_t columns, uint8_t rows);
    /** @brief Set the vertical scroll area: @p top_fixed rows (0..63) fixed, @p scroll_rows (1..64) scrolling. */
    void set_scroll_area(uint8_t top_fixed, uint8_t scroll_rows);
    /** @brief Activate the configured scroll (0x2F). */
    void scroll_start();
    /** @brief Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires. */
    void scroll_stop();

    /** @brief Enable area-colour mode and/or low-power display mode (0xD8). */
    void set_area_color(bool enable, bool low_power = false);
    /** @brief Set the colour-bank brightness, 0..255 (reset 0x80). */
    void set_brightness(uint8_t value);
    /** @brief Set the drive pulse width codes (31..63) of BANK0 and colours A, B, C. */
    void set_lut(uint8_t bank0, uint8_t a, uint8_t b, uint8_t c);
    /** @brief Assign colours to banks 1..16 (4 bytes) and optionally banks 17..32 (4 bytes, nullptr to skip). */
    void set_bank_colors(const uint8_t* banks_1_16, const uint8_t* banks_17_32 = nullptr);

    /** @brief Read the status byte (I²C only): bit 6 = 1 when the display is off; other bits reserved.
     *  @return True if the read succeeded; false on SPI (write-only).
     */
    bool read_status(uint8_t& status);
    /** @brief Read @p n bytes (at most I2C_CHUNK - 1) of display RAM (I²C only); the dummy byte is discarded.
     *  @return True if the read succeeded; false on SPI.
     */
    bool read_page(uint8_t page, uint8_t column, uint8_t* buf, uint8_t n);
    /** @brief Send raw bytes as one command stream. */
    void send_command(const uint8_t* bytes, size_t len);

protected:
    SSD1305Full(SSD1305Bus& bus, uint16_t width, uint16_t height, uint8_t column_offset)
        : SSD1305Minimal(bus, width, height, column_offset) {}
};

/** @brief SPI transport on a display connection (4-wire): D/C low for commands, high for RAM data.
 *
 *  Every command byte (including arguments) is sent with D/C low; RAM data goes out in chunks of
 *  SPI_CHUNK bytes with D/C high. SPI is write-only: nothing is read back or detected.
 *
 *  @tparam CONN Display connection class (CS, D/C, RES optional; no BUSY).
 */
template <typename CONN>
class SSD1305SpiBus : public SSD1305Bus {
public:
    static constexpr size_t SPI_CHUNK = 132;
    explicit SSD1305SpiBus(CONN& connection) : _conn(connection) {}
    void write_commands(const uint8_t* bytes, size_t len) override {
        for (size_t i = 0; i < len; i++) _conn.sendCommand(bytes[i]);
    }
    void write_data(const uint8_t* bytes, size_t len) override {
        static const uint8_t ZEROS[SPI_CHUNK] = {0};
        while (len > 0) {
            size_t n = len > SPI_CHUNK ? SPI_CHUNK : len;
            _conn.sendData(bytes ? bytes : ZEROS, n);
            if (bytes) bytes += n;
            len -= n;
        }
    }
    bool can_read() const override { return false; }
    void hardware_reset() override { _conn.reset(); }
private:
    CONN& _conn;
};

namespace ssd1305_detail {
/** Base-from-member: the transport must exist before the driver base class runs its initialisation. */
template <typename CONN>
struct SpiBusHolder {
    explicit SpiBusHolder(CONN& connection) : spi_bus(connection) {}
    SSD1305SpiBus<CONN> spi_bus;
};
}  // namespace ssd1305_detail

/** @brief SSD1305 over 4-wire SPI — minimal interface.
 *
 *  Same framebuffer, drawing and initialization as SSD1305Minimal, but driven through a display
 *  connection (`DisplayConnectionBase` family): CS, D/C = A0, RES optional. The chip must be
 *  strapped for 4-wire SPI (BS0 = BS1 = BS2 = 0). SPI mode 0, up to 4 MHz (2 MHz recommended).
 *
 *  @tparam CONN Display connection class.
 */
template <typename CONN>
class SSD1305SpiMinimal : private ssd1305_detail::SpiBusHolder<CONN>, public SSD1305Minimal {
public:
    explicit SSD1305SpiMinimal(CONN& connection, uint16_t width = 128, uint16_t height = 64,
                              uint8_t column_offset = 4)
        : ssd1305_detail::SpiBusHolder<CONN>(connection),
          SSD1305Minimal(this->spi_bus, width, height, column_offset) {}
};

/** @brief SSD1305 over 4-wire SPI — full interface (see SSD1305Full; read_status() and read_page() return false).
 *
 *  @tparam CONN Display connection class.
 */
template <typename CONN>
class SSD1305SpiFull : private ssd1305_detail::SpiBusHolder<CONN>, public SSD1305Full {
public:
    explicit SSD1305SpiFull(CONN& connection, uint16_t width = 128, uint16_t height = 64,
                           uint8_t column_offset = 4)
        : ssd1305_detail::SpiBusHolder<CONN>(connection),
          SSD1305Full(this->spi_bus, width, height, column_offset) {}
};
