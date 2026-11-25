#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief PCF8578 row/column LCD dot-matrix driver — minimal interface.
 *
 *  Drives a stand-alone dot-matrix LCD (mixed mode: the chip drives both rows and
 *  columns) out of the box. With @c rows rows the panel is @c 40-rows columns wide
 *  (8x32, 16x24, 24x16 or 32x8). The chip has no register map; the driver streams
 *  command bytes and display data with Connection::write / write_read. The I2C
 *  address is 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD); the chip supports 100 kHz at most.
 *
 *  The constructor waits for the internal power-on reset, clears the whole display
 *  RAM, then switches the display on (normal video, start bank 0). A shadow copy of the
 *  4 x 40 byte RAM is kept so single-pixel updates need only one byte on the bus.
 *  One RAM byte is 8 vertical pixels of one column; bit 0 is the top row of the bank.
 *
 *  Methods that can reject their arguments return @c false instead of throwing
 *  (the library builds on embedded targets without exceptions).
 *
 *  @param connection Configured I2C connection pointing at the device (100 kHz maximum).
 *  @param rows       Display rows: 8, 16 (default), 24 or 32. Any other value falls back to 16.
 */
class PCF8578Minimal {
public:
    static constexpr uint8_t ADDR_SA0_LOW  = 0x3C;
    static constexpr uint8_t ADDR_SA0_HIGH = 0x3D;

    static constexpr uint8_t CMD_SET_MODE       = 0x40;
    static constexpr uint8_t CMD_DEVICE_SELECT  = 0x60;
    static constexpr uint8_t CMD_RAM_ACCESS     = 0x70;
    static constexpr uint8_t CMD_SET_START_BANK = 0x7C;
    static constexpr uint8_t CONT               = 0x80;

    static constexpr uint8_t STATUS_BLANK   = 0;
    static constexpr uint8_t STATUS_NORMAL  = 1;
    static constexpr uint8_t STATUS_ALL_ON  = 2;
    static constexpr uint8_t STATUS_INVERSE = 3;

    static constexpr uint8_t ACCESS_CHARACTER    = 0;
    static constexpr uint8_t ACCESS_HALF_GRAPHIC = 1;
    static constexpr uint8_t ACCESS_FULL_GRAPHIC = 2;

    static constexpr uint8_t RAM_COLUMNS = 40;
    static constexpr uint8_t RAM_BANKS   = 4;

    /** @brief Construct the driver and initialise the chip (clears RAM, display on). */
    explicit PCF8578Minimal(Connection& connection, uint8_t rows = 16);

    /** @brief Zero the whole display RAM (4 banks x 40 columns) and the shadow. */
    void clear();

    /** @brief Set or clear one pixel. Out-of-range coordinates are ignored; writes one RAM byte.
     *
     *  @param x  Display column, 0 .. (40 - rows - 1).
     *  @param y  Display row, 0 .. rows - 1.
     *  @param on true lights the pixel, false clears it.
     */
    void set_pixel(int x, int y, bool on = true);

    /** @brief Write consecutive column bytes in character access mode.
     *
     *  @param x    First display column, 0 .. (40 - rows - 1).
     *  @param bank Display bank (8-row band), 0 .. rows/8 - 1.
     *  @param data Column bytes; bit 0 is the top row of the bank.
     *  @param len  Number of bytes; the write is truncated at the last display column.
     *  @return false if @p bank is out of range for the configured rows.
     */
    bool write_columns(int x, int bank, const uint8_t* data, size_t len);

protected:
    Connection& _connection;
    uint8_t _rows;
    bool    _mixed;
    uint8_t _status;
    uint8_t _start_bank;
    uint8_t _shadow[RAM_BANKS][RAM_COLUMNS];

    static int8_t _mux_code(uint8_t rows);
    uint8_t _mode_byte(uint8_t status, bool cont) const;
    static uint8_t _ram_access_byte(uint8_t access, uint8_t bank);
    void _init();
    void _zero_shadow();
    void _ram_write(uint8_t x, uint8_t bank, const uint8_t* data, size_t len, uint8_t access);
    static void _delay_ms(unsigned long ms);
};

/** @brief PCF8578 full interface — extends PCF8578Minimal.
 *
 *  Adds pixel read-back from the shadow, fill, line/rectangle/bitmap drawing, display
 *  status (blank / normal / all-on / inverse), multiplex rate and row mode, hardware
 *  vertical scrolling via the start bank, raw RAM write/read in all three access modes
 *  and the device-select command for PCF8579 cascades.
 *
 *  @param connection Configured I2C connection pointing at the device (100 kHz maximum).
 *  @param rows       Display rows: 8, 16 (default), 24 or 32.
 */
class PCF8578Full : public PCF8578Minimal {
public:
    static constexpr uint8_t DISPLAY_BLANK   = STATUS_BLANK;
    static constexpr uint8_t DISPLAY_NORMAL  = STATUS_NORMAL;
    static constexpr uint8_t DISPLAY_ALL_ON  = STATUS_ALL_ON;
    static constexpr uint8_t DISPLAY_INVERSE = STATUS_INVERSE;

    explicit PCF8578Full(Connection& connection, uint8_t rows = 16);

    /** @brief Return the state of one pixel from the shadow (no bus traffic). */
    bool get_pixel(int x, int y) const;

    /** @brief Set every displayed pixel on or off. */
    void fill(bool on = true);

    /** @brief Draw a Bresenham line, written as one batch of changed bytes. */
    void draw_line(int x0, int y0, int x1, int y1, bool on = true);

    /** @brief Draw a rectangle outline, or a filled rectangle when @p fill is true. */
    void draw_rect(int x, int y, int w, int h, bool on = true, bool fill = false);

    /** @brief Blit a column-major bitmap aligned to a bank boundary.
     *
     *  @param x     Left display column.
     *  @param y     Top display row; must be a multiple of 8.
     *  @param data  Band-major column bytes: @p bands bands of @p width bytes each
     *               (bit 0 = top row). Content outside the display is clipped.
     *  @param width Columns per band.
     *  @param bands Number of 8-pixel-high bands.
     *  @return false if @p y is not a multiple of 8.
     */
    bool show_bitmap(int x, int y, const uint8_t* data, size_t width, size_t bands);

    /** @brief Set the display status (DISPLAY_BLANK … DISPLAY_INVERSE); RAM is preserved.
     *  @return false if @p status is out of range. */
    bool set_display(uint8_t status);

    /** @brief Change the multiplex rate (8/16/24/32) and the row/mixed mode.
     *
     *  In row mode (@p mixed = false) the RAM cannot be accessed; pixel and RAM calls
     *  are rejected until mixed mode is restored.
     *  @return false if @p rows is invalid. */
    bool set_mode(uint8_t rows, bool mixed = true);

    /** @brief Select the RAM bank (0 … 3) shown at the top of the display (vertical scroll).
     *  @return false if @p bank is out of range. */
    bool set_start_bank(uint8_t bank);

    /** @brief Add @p banks (may be negative) to the start bank, modulo the banks in use. */
    void scroll(int banks);

    /** @brief Raw RAM write in character, half-graphic or full-graphic order.
     *
     *  @param x    RAM column, 0 .. 39.
     *  @param bank RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic).
     *  @param data Bytes to write.
     *  @param len  Number of bytes (at most 160).
     *  @param mode ACCESS_CHARACTER, ACCESS_HALF_GRAPHIC or ACCESS_FULL_GRAPHIC.
     *  @return false if the arguments are invalid, the write would pass column 39, or the
     *          chip is in row mode. */
    bool write_ram(int x, int bank, const uint8_t* data, size_t len, uint8_t mode = ACCESS_CHARACTER);

    /** @brief Raw RAM read: address-set commands, repeated start, then @p n bytes.
     *  @return false if the arguments are invalid or the chip is in row mode. */
    bool read_ram(int x, int bank, uint8_t* buf, size_t n, uint8_t mode = ACCESS_CHARACTER);

    /** @brief Load the subaddress counter (0 … 15) for PCF8579 cascades.
     *
     *  Subaddress 0 addresses the PCF8578 itself; pixel and RAM calls restore it first.
     *  @return false if @p subaddress is out of range. */
    bool device_select(uint8_t subaddress);

    /** @brief Re-run initialisation at the current row count (clears RAM, mixed mode, normal video). */
    void reset();

private:
    uint8_t _dirty[RAM_BANKS][RAM_COLUMNS / 8];

    static bool _check_ram_args(int x, int bank, size_t count, uint8_t access);
    void _plot(int x, int y, bool on);
    void _flush();
};
