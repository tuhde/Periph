#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief Transport hooks of the SH1106 drivers.
 *
 *  Implemented by SH1106I2CBus (control-byte framing on one I²C connection) and SH1106SpiBus
 *  (a display connection; D/C low for commands, high for RAM data).
 */
class SH1106Bus {
public:
    virtual ~SH1106Bus() = default;
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
class SH1106I2CBus : public SH1106Bus {
public:
    /** @brief Largest write in bytes including the control byte (the 32-byte limit of some I²C layers). */
    static constexpr size_t CHUNK = 32;
    explicit SH1106I2CBus(Connection* connection = nullptr) : _conn(connection) {}
    void write_commands(const uint8_t* bytes, size_t len) override;
    void write_data(const uint8_t* bytes, size_t len) override;
    bool can_read() const override { return true; }
    bool read_status(uint8_t& status) override;
    bool read_ram(uint8_t* buf, size_t n) override;
private:
    Connection* _conn;
    uint8_t     _buf[CHUNK];
};

/** @brief SH1106 132 x 64 OLED controller-driver — minimal interface (I²C).
 *
 *  Drives a monochrome SH1106 OLED panel (typically a 1.3" 128 x 64 module) over I²C at
 *  0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). The chip has 132 RAM columns; 128-pixel-wide modules
 *  show RAM columns 2..129, so @p column_offset defaults to 2.
 *
 *  All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 = top
 *  pixel of the page; 1056 bytes at the maximum size) that lives inside the object; show() sends it
 *  page by page (the chip has page addressing only). The initialization follows the datasheet
 *  power-on flow with the internal DC-DC: setup stream with the display off, DC-DC on + 100 ms,
 *  RAM cleared, display on + 150 ms.
 *
 *  No heap allocation, no exceptions; invalid arguments are reported through valid().
 *
 *  @param connection     Connection at 0x3C or 0x3D.
 *  @param width          Visible columns, 1..132 (default 128).
 *  @param height         Rows, a multiple of 8, 8..64 (default 64).
 *  @param column_offset  First RAM column shown (default 2; 0 for a true 132-column panel).
 *  @param internal_dcdc  True for the internal charge pump (default), false for an external VPP.
 */
class SH1106Minimal {
public:
    static constexpr uint16_t WIDTH  = 132;
    static constexpr uint16_t HEIGHT = 64;
    /** @brief Largest write in bytes including the control byte. */
    static constexpr size_t I2C_CHUNK = SH1106I2CBus::CHUNK;

    static constexpr uint8_t CMD_COL_LO     = 0x00;   // | A[3:0]
    static constexpr uint8_t CMD_COL_HI     = 0x10;   // | A[7:4]
    static constexpr uint8_t CMD_PUMP       = 0x30;   // | A[1:0]
    static constexpr uint8_t CMD_START_LINE = 0x40;   // | A[5:0]
    static constexpr uint8_t CMD_CONTRAST   = 0x81;
    static constexpr uint8_t CMD_SEG_REMAP  = 0xA0;   // | ADC
    static constexpr uint8_t CMD_ALL_ON     = 0xA4;   // | 1
    static constexpr uint8_t CMD_INVERT     = 0xA6;   // | 1
    static constexpr uint8_t CMD_MULTIPLEX  = 0xA8;
    static constexpr uint8_t CMD_DCDC       = 0xAD;
    static constexpr uint8_t CMD_DISPLAY    = 0xAE;   // | 1
    static constexpr uint8_t CMD_PAGE       = 0xB0;   // | A[2:0]
    static constexpr uint8_t CMD_SCAN       = 0xC0;   // | (scan << 3)
    static constexpr uint8_t CMD_OFFSET     = 0xD3;
    static constexpr uint8_t CMD_CLOCK      = 0xD5;
    static constexpr uint8_t CMD_PRECHARGE  = 0xD9;
    static constexpr uint8_t CMD_COM        = 0xDA;
    static constexpr uint8_t CMD_VCOM       = 0xDB;

    /** @brief Construct the driver and run the initialization sequence (about 250 ms). */
    SH1106Minimal(Connection& connection, uint16_t width = 128, uint16_t height = 64,
                  uint8_t column_offset = 2, bool internal_dcdc = true)
        : SH1106Minimal(nullptr, &connection, width, height, column_offset, internal_dcdc) {}

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

    /** @brief Write the whole framebuffer to the display RAM (page + column command, then width data bytes per page). */
    void show();

protected:
    SH1106Minimal(SH1106Bus* bus, Connection* connection, uint16_t width, uint16_t height,
                  uint8_t column_offset, bool internal_dcdc);
    /** @brief Constructor for the SPI classes: the transport is already built. */
    SH1106Minimal(SH1106Bus& bus, uint16_t width, uint16_t height, uint8_t column_offset, bool internal_dcdc)
        : SH1106Minimal(&bus, nullptr, width, height, column_offset, internal_dcdc) {}

    void _sleep_ms(uint32_t ms);
    void _command(const uint8_t* bytes, size_t len);
    void _command1(uint8_t a);
    void _command2(uint8_t a, uint8_t b);
    void _address(uint8_t page, uint8_t column);
    void _show_page(uint8_t page);

    SH1106I2CBus _i2c;
    SH1106Bus&   _bus;
    bool         _valid;
    uint16_t     _width;
    uint16_t     _height;
    uint8_t      _pages;
    uint8_t      _offset;
    bool         _dcdc;
    uint8_t      _adc;
    uint8_t      _scan;
    uint8_t      _fb[WIDTH * (HEIGHT / 8)];

private:
    void _init_sequence();
};

/** @brief SH1106 OLED controller-driver — full interface (I²C).
 *
 *  Extends SH1106Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip,
 *  hardware scroll (display start line), display offset, multiplex ratio, clock / oscillator,
 *  pre-charge, COM configuration, VCOM level, pump voltage, DC-DC control, status and RAM reads
 *  (I²C only) and raw commands.
 */
class SH1106Full : public SH1106Minimal {
public:
    /** @brief Construct the driver and run the initialization sequence (see SH1106Minimal). */
    SH1106Full(Connection& connection, uint16_t width = 128, uint16_t height = 64,
               uint8_t column_offset = 2, bool internal_dcdc = true)
        : SH1106Minimal(nullptr, &connection, width, height, column_offset, internal_dcdc) {}

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
    /** @brief Display off; the DC-DC stops, RAM and settings are kept and stay writable. */
    void sleep();
    /** @brief Display on, then wait 100 ms for VPP when the internal DC-DC is used. */
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
    /** @brief Set the multiplex ratio, 1..64. */
    void set_multiplex(uint8_t ratio);
    /** @brief Set the clock divide ratio (1..16) and oscillator setting (0..15, 5 = nominal). */
    void set_clock(uint8_t divide, uint8_t osc);
    /** @brief Set the pre-charge (1..15) and discharge (1..15) periods in display clocks. */
    void set_precharge(uint8_t precharge, uint8_t discharge);
    /** @brief Select the COM pin configuration: true = alternative (0x12, reset), false = sequential (0x02). */
    void set_com_config(bool alternative);
    /** @brief Set the VCOM deselect level, 0..255 (reset 0x35). */
    void set_vcom_deselect(uint8_t value);
    /** @brief Set the charge pump voltage: 0 = 7.4 V (reset), 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V. */
    void set_pump_voltage(uint8_t level);
    /** @brief Switch the internal DC-DC on (0x8B) or off (0x8A, external VPP); call with the display off. */
    void set_dcdc(bool on);

    /** @brief Read the status byte (I²C only): bit 7 BUSY, bit 6 = 1 when the display is off.
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
    SH1106Full(SH1106Bus& bus, uint16_t width, uint16_t height, uint8_t column_offset, bool internal_dcdc)
        : SH1106Minimal(bus, width, height, column_offset, internal_dcdc) {}
};

/** @brief SPI transport on a display connection (4-wire): D/C low for commands, high for RAM data.
 *
 *  Every command byte (including arguments) is sent with D/C low; RAM data goes out in chunks of
 *  SPI_CHUNK bytes with D/C high. SPI is write-only: nothing is read back or detected.
 *
 *  @tparam CONN Display connection class (CS, D/C = A0, RES optional; no BUSY).
 */
template <typename CONN>
class SH1106SpiBus : public SH1106Bus {
public:
    static constexpr size_t SPI_CHUNK = 132;
    explicit SH1106SpiBus(CONN& connection) : _conn(connection) {}
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

namespace sh1106_detail {
/** Base-from-member: the transport must exist before the driver base class runs its initialisation. */
template <typename CONN>
struct SpiBusHolder {
    explicit SpiBusHolder(CONN& connection) : spi_bus(connection) {}
    SH1106SpiBus<CONN> spi_bus;
};
}  // namespace sh1106_detail

/** @brief SH1106 over 4-wire SPI — minimal interface.
 *
 *  Same framebuffer, drawing and initialization as SH1106Minimal, but driven through a display
 *  connection (`DisplayConnectionBase` family): CS, D/C = A0, RES optional. The chip must be
 *  strapped for 4-wire SPI (IM0 = IM1 = IM2 = 0). SPI mode 0, up to 2 MHz (4 MHz at VDD1 >= 2.4 V).
 *
 *  @tparam CONN Display connection class.
 */
template <typename CONN>
class SH1106SpiMinimal : private sh1106_detail::SpiBusHolder<CONN>, public SH1106Minimal {
public:
    explicit SH1106SpiMinimal(CONN& connection, uint16_t width = 128, uint16_t height = 64,
                              uint8_t column_offset = 2, bool internal_dcdc = true)
        : sh1106_detail::SpiBusHolder<CONN>(connection),
          SH1106Minimal(this->spi_bus, width, height, column_offset, internal_dcdc) {}
};

/** @brief SH1106 over 4-wire SPI — full interface (see SH1106Full; read_status() and read_page() return false).
 *
 *  @tparam CONN Display connection class.
 */
template <typename CONN>
class SH1106SpiFull : private sh1106_detail::SpiBusHolder<CONN>, public SH1106Full {
public:
    explicit SH1106SpiFull(CONN& connection, uint16_t width = 128, uint16_t height = 64,
                           uint8_t column_offset = 2, bool internal_dcdc = true)
        : sh1106_detail::SpiBusHolder<CONN>(connection),
          SH1106Full(this->spi_bus, width, height, column_offset, internal_dcdc) {}
};
