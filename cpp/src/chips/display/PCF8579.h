#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief PCF8579 LCD column driver — minimal interface.
 *
 *  The PCF8579 is the column-driver companion of the PCF8578 and cannot run alone: the
 *  clock, frame sync and row waveforms come from a PCF8578 (or an equivalent source).
 *  This driver writes the display RAM of one PCF8579 (4 banks x 40 columns, X = n drives
 *  output Cn). The chip has no register map; the driver streams command bytes and display
 *  data with Connection::write / write_read. The I2C address is 0x3C (SA0 = VSS) or 0x3D
 *  (SA0 = VDD); the chip supports 100 kHz at most.
 *
 *  The constructor waits for the internal power-on reset, clears this device's RAM, then
 *  switches the display on (normal video, start bank 0). A shadow copy of the 4 x 40 byte
 *  RAM is kept so single-pixel updates need only one byte on the bus. One RAM byte is 8
 *  vertical pixels of one column; bit 0 is the top row of the bank.
 *
 *  device-select, set-mode and set-start-bank reach every device on the I2C address, so all
 *  drivers on one bus must use the same rows / mixed settings.
 *
 *  Methods that can reject their arguments return @c false instead of throwing
 *  (the library builds on embedded targets without exceptions).
 *
 *  @param connection Configured I2C connection pointing at the device (100 kHz maximum).
 *  @param subaddress Hardware subaddress set on pins A3:A0, 0 .. 15 (other values fall back to 0).
 *  @param rows       Display rows: 8, 16 (default), 24 or 32. Any other value falls back to 16.
 *  @param mixed      true if the PCF8578 in the system runs in mixed mode (T bit); default false.
 */
class PCF8579Minimal {
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
    explicit PCF8579Minimal(Connection& connection, uint8_t subaddress = 0, uint8_t rows = 16,
                            bool mixed = false);

    /** @brief Zero this device's whole display RAM (4 banks x 40 columns) and the shadow. */
    void clear();

    /** @brief Set or clear one pixel. Out-of-range coordinates are ignored; writes one RAM byte.
     *
     *  @param x  Column, 0 .. 39.
     *  @param y  Row, 0 .. rows - 1.
     *  @param on true lights the pixel, false clears it.
     */
    void set_pixel(int x, int y, bool on = true);

    /** @brief Write consecutive column bytes in character access mode.
     *
     *  @param x    First column, 0 .. 39.
     *  @param bank Bank (8-row band), 0 .. rows/8 - 1.
     *  @param data Column bytes; bit 0 is the top row of the bank.
     *  @param len  Number of bytes; the write is truncated at column 39.
     *  @return false if @p bank is out of range for the configured rows.
     */
    bool write_columns(int x, int bank, const uint8_t* data, size_t len);

protected:
    Connection& _connection;
    uint8_t _subaddress;
    uint8_t _rows;
    bool    _mixed;
    uint8_t _status;
    uint8_t _start_bank;
    uint8_t _shadow[RAM_BANKS][RAM_COLUMNS];

    static int8_t _mux_code(uint8_t rows);
    uint8_t _mode_byte(uint8_t status, bool cont) const;
    uint8_t _select_byte() const;
    static uint8_t _ram_access_byte(uint8_t access, uint8_t bank);
    void _init();
    void _zero_shadow();
    void _ram_write(uint8_t x, uint8_t bank, const uint8_t* data, size_t len, uint8_t access);
    static void _delay_ms(unsigned long ms);
};

/** @brief PCF8579 full interface — extends PCF8579Minimal.
 *
 *  Adds pixel read-back from the shadow, fill, line/rectangle/bitmap drawing, display
 *  status (blank / normal / all-on / inverse), multiplex rate and PCF8578 mode bit,
 *  hardware vertical scrolling via the start bank, and raw RAM write/read in all three
 *  access modes including transfers that run on into the following cascaded devices.
 *
 *  @param connection Configured I2C connection pointing at the device (100 kHz maximum).
 *  @param subaddress Hardware subaddress set on pins A3:A0, 0 .. 15.
 *  @param rows       Display rows: 8, 16 (default), 24 or 32.
 *  @param mixed      true if the PCF8578 in the system runs in mixed mode.
 */
class PCF8579Full : public PCF8579Minimal {
public:
    static constexpr uint8_t DISPLAY_BLANK   = STATUS_BLANK;
    static constexpr uint8_t DISPLAY_NORMAL  = STATUS_NORMAL;
    static constexpr uint8_t DISPLAY_ALL_ON  = STATUS_ALL_ON;
    static constexpr uint8_t DISPLAY_INVERSE = STATUS_INVERSE;

    /** @brief Largest transfer accepted by write_ram_cascade() (two devices of 160 bytes). */
    static constexpr size_t MAX_CASCADE_BYTES = 320;

    explicit PCF8579Full(Connection& connection, uint8_t subaddress = 0, uint8_t rows = 16,
                         bool mixed = false);

    /** @brief Return the state of one pixel from the shadow (no bus traffic). */
    bool get_pixel(int x, int y) const;

    /** @brief Set every displayed pixel (rows x 40) on or off. */
    void fill(bool on = true);

    /** @brief Draw a Bresenham line, written as one batch of changed bytes. */
    void draw_line(int x0, int y0, int x1, int y1, bool on = true);

    /** @brief Draw a rectangle outline, or a filled rectangle when @p fill is true. */
    void draw_rect(int x, int y, int w, int h, bool on = true, bool fill = false);

    /** @brief Blit 8-pixel-high column bytes aligned to a bank boundary.
     *
     *  @param x       Left column.
     *  @param y       Top row; must be a multiple of 8.
     *  @param columns One byte per column (bit 0 = top row); clipped at column 39.
     *  @param width   Number of columns.
     *  @return false if @p y is not a non-negative multiple of 8 or the bank is out of range.
     */
    bool show_bitmap(int x, int y, const uint8_t* columns, size_t width);

    /** @brief Set the display status (DISPLAY_BLANK … DISPLAY_INVERSE); RAM is preserved.
     *  @return false if @p status is out of range. */
    bool set_display(uint8_t status);

    /** @brief Change the multiplex rate (8/16/24/32) and the PCF8578 mode bit (all devices).
     *  @return false if @p rows is invalid. */
    bool set_mode(uint8_t rows, bool mixed = false);

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
     *  @return false if the arguments are invalid or the write would pass column 39. */
    bool write_ram(int x, int bank, const uint8_t* data, size_t len, uint8_t mode = ACCESS_CHARACTER);

    /** @brief Write from column 0, bank 0 of this device and run on into the next subaddresses.
     *
     *  The chip advances its subaddress counter after column 39, so one transfer fills several
     *  cascaded PCF8579s. The shadow only tracks this device's first 40 columns.
     *  @param data Bytes to write.
     *  @param len  Number of bytes (at most MAX_CASCADE_BYTES).
     *  @param mode ACCESS_CHARACTER, ACCESS_HALF_GRAPHIC or ACCESS_FULL_GRAPHIC (default).
     *  @return false if the arguments are invalid. */
    bool write_ram_cascade(const uint8_t* data, size_t len, uint8_t mode = ACCESS_FULL_GRAPHIC);

    /** @brief Raw RAM read: address-set commands, repeated start, then @p n bytes.
     *  @return false if the arguments are invalid. */
    bool read_ram(int x, int bank, uint8_t* buf, size_t n, uint8_t mode = ACCESS_CHARACTER);

    /** @brief Re-run initialisation (clears this device's RAM, normal video, start bank 0). */
    void reset();

private:
    uint8_t _dirty[RAM_BANKS][RAM_COLUMNS / 8];

    static bool _check_ram_args(int x, int bank, size_t count, uint8_t access, bool cascade = false);
    void _plot(int x, int y, bool on);
    void _flush();
};
