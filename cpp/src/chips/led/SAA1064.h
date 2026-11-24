#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief SAA1064 4-digit LED driver — minimal interface.
 *
 *  Drives four 7-segment digits (multiplexed in pairs, dynamic mode) with
 *  decimal points. I2C address 0x38-0x3B, selected by the voltage on the ADR pin.
 *
 *  Defaults baked in at construction: control byte 0x47 (dynamic mode, all
 *  digits unblanked, 12 mA segment current), digits blank, default segment
 *  mapping a = bit 0 ... g = bit 6, dp = bit 7.
 *
 *  @param connection Configured I2C connection pointing at the device.
 */
class SAA1064Minimal {
public:
    static constexpr uint8_t DIGITS = 4;
    /** @brief Value that blanks a digit in show_digits() and show_digit(). */
    static constexpr int8_t  BLANK  = -1;

    static constexpr uint8_t SUB_CONTROL  = 0x00;
    static constexpr uint8_t C_DYNAMIC    = 0x01;
    static constexpr uint8_t C_UNBLANK_13 = 0x02;
    static constexpr uint8_t C_UNBLANK_24 = 0x04;
    static constexpr uint8_t C_TEST       = 0x08;
    static constexpr uint8_t C_CURRENT    = 0x70;

    /** @brief Construct the driver, write control 0x47 and blank the digits. */
    explicit SAA1064Minimal(Connection& connection);

    /** @brief Show four digits using the segment mapping.
     *  @param digits Four values, each 0-9 or BLANK.
     *  @return false (nothing written) if a value is out of range.
     */
    bool show_digits(const int8_t digits[4]);

    /** @brief Show a right-aligned decimal number with leading zeros blanked.
     *  @param value 0-9999.
     *  @return false (nothing written) if value is above 9999.
     */
    bool show_number(uint16_t value);

    /** @brief Blank all four digits. */
    void clear();

    /** @brief Write raw segment bytes starting at a digit.
     *  @param patterns    1-4 segment bytes (bit 0 = P1/P9 ... bit 7 = P8/P16).
     *  @param len         Number of bytes.
     *  @param first_digit First digit to write, 1-4.
     *  @return false (nothing written) if the range does not fit digits 1-4.
     */
    bool write_raw(const uint8_t* patterns, size_t len, uint8_t first_digit = 1);

protected:
    Connection& _connection;
    uint8_t _control;
    uint8_t _patterns[4];
    uint8_t _map[8];
    uint8_t _glyphs[10];

    void _rebuild_glyphs();
    bool _pattern(int8_t value, uint8_t& out) const;
    void _write_digits(uint8_t first);
};

/** @brief SAA1064 full interface — mode, blanking, current, test, segment map, status.
 *
 *  @param connection Configured I2C connection pointing at the device.
 */
class SAA1064Full : public SAA1064Minimal {
public:
    explicit SAA1064Full(Connection& connection);

    /** @brief Select dynamic (all four digits) or static (digits 1 and 2) mode. */
    void set_mode(bool dynamic);

    /** @brief Blank digit pairs (true blanks digits 1+3 / digits 2+4). */
    void blank(bool digits_13, bool digits_24);

    /** @brief Set the segment output current.
     *  @param ma 0, 3, 6, 9, 12, 15, 18 or 21 (mA).
     *  @return false (nothing written) for any other value.
     */
    bool set_current(uint8_t ma);

    /** @brief Switch all segment outputs on (segment test). */
    void segment_test(bool on);

    /** @brief Remap the helper's segment bits to the module wiring.
     *  @param map Bit numbers (0-7) for a, b, c, d, e, f, g, dp.
     *  @return false (mapping unchanged) if a bit number is above 7.
     */
    bool set_segment_map(const uint8_t map[8]);

    /** @brief Show one digit with an optional decimal point.
     *  @param position Digit 1-4.
     *  @param value    0-9 or BLANK.
     *  @param dp       true to light the decimal point.
     *  @return false (nothing written) for a bad position or value.
     */
    bool show_digit(uint8_t position, int8_t value, bool dp = false);

    /** @brief Switch a decimal point, keeping the cached segments.
     *  @param position Digit 1-4.
     *  @param on       true to light the decimal point.
     *  @return false (nothing written) for a bad position.
     */
    bool set_decimal_point(uint8_t position, bool on);

    /** @brief Read the status byte (bit 7 = PR power-reset flag, cleared by the read). */
    uint8_t read_status();

    /** @brief Read the status byte and return its PR flag. */
    bool power_reset_flag();

    /** @brief Write a raw control byte (bit 7 forced to 0). */
    void write_control(uint8_t value);

private:
    void _write_control();
    void _set_bit(uint8_t mask, bool on);
};
