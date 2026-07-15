#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"

/** @brief PCF8576 40x4 universal LCD segment driver — minimal interface.
 *
 *  Drives a single 7-segment LCD display (static or 1:4 multiplex) out of
 *  the box. The chip is write-only — the host never reads back. I2C address
 *  is 0x38 (SA0 = VSS) or 0x39 (SA0 = VDD).
 *
 *  Default configuration baked in at construction:
 *    - 1:4 multiplex drive mode (4 backplanes)
 *    - 1/3 bias
 *    - display enabled (E = 1)
 *    - display RAM cleared to all zeros
 *
 *  @param transport Configured I2C transport pointing at the device.
 */
class PCF8576Minimal {
public:
    static constexpr uint8_t ADDR_SA0_LOW  = 0x38;
    static constexpr uint8_t ADDR_SA0_HIGH = 0x39;

    static constexpr uint8_t CMD_MODE_SET      = 0x40;
    static constexpr uint8_t CMD_LOAD_PTR      = 0x00;
    static constexpr uint8_t CMD_DEVICE_SELECT = 0x60;
    static constexpr uint8_t CMD_BANK_SELECT   = 0x78;
    static constexpr uint8_t CMD_BLINK_SELECT  = 0x70;

    static constexpr uint8_t MODE_1_4    = 0x00;
    static constexpr uint8_t MODE_STATIC = 0x01;
    static constexpr uint8_t MODE_1_2    = 0x02;
    static constexpr uint8_t MODE_1_3    = 0x03;

    static constexpr uint8_t BIAS_1_3 = 0x00;
    static constexpr uint8_t BIAS_1_2 = 0x04;

    static constexpr uint8_t DISPLAY_OFF = 0x00;
    static constexpr uint8_t DISPLAY_ON  = 0x08;

    static constexpr uint8_t SEVEN_SEG[10] = {
        0xED, 0x60, 0xA7, 0xE3, 0x6A,
        0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
    };

    /** @brief Construct the driver and initialise the chip with defaults. */
    explicit PCF8576Minimal(Transport& transport);

    /** @brief Zero all 40 columns of display RAM; all segments off. */
    void clear();

    /** @brief Set the data pointer to @p address and write raw data bytes.
     *
     *  @param address RAM column address, 0-39.
     *  @param data    Bytes to write to display RAM; one byte covers two
     *                 adjacent columns in 1:4 multiplex mode.
     *  @param len     Number of bytes in @p data.
     */
    void write_raw(uint8_t address, const uint8_t* data, size_t len);

    /** @brief Write one 7-segment byte at column @p position * 2.
     *
     *  @param position Digit index, 0-19. Maps to RAM address @p position * 2.
     *  @param segments 7-segment byte (a/c/b/DP/f/e/g/d packed, MSB-first).
     *                  Add 0x10 to set the decimal point.
     */
    void set_digit_7seg(uint8_t position, uint8_t segments);

protected:
    Transport& _transport;
    uint8_t    _backplanes;

    uint8_t _cmd_mode(bool enable, uint8_t bias, uint8_t mode) const;
    void    _send_commands(const uint8_t* cmds, size_t n);
    void    _send_commands_with_data(const uint8_t* cmds, size_t n_cmds,
                                     const uint8_t* data, size_t n_data);
    void    _do_clear();
};

/** @brief PCF8576 full interface — extends PCF8576Minimal with drive mode, bias, and blink control.
 *
 *  Adds the ability to switch drive modes (static, 1:2, 1:3, 1:4 multiplex),
 *  change bias (1:2 or 1/3), configure blinking, select RAM banks for
 *  static and 1:2 multiplex use, and change the device subaddress counter
 *  for cascaded displays.
 *
 *  @param transport Configured I2C transport pointing at the device.
 */
class PCF8576Full : public PCF8576Minimal {
public:
    static constexpr uint8_t BLINK_OFF     = 0;
    static constexpr uint8_t BLINK_2_HZ    = 1;
    static constexpr uint8_t BLINK_1_HZ    = 2;
    static constexpr uint8_t BLINK_0_5_HZ  = 3;

    static constexpr uint8_t BIAS_1_3 = 0;
    static constexpr uint8_t BIAS_1_2 = 1;

    static constexpr uint8_t BACKPLANES_1 = 1;
    static constexpr uint8_t BACKPLANES_2 = 2;
    static constexpr uint8_t BACKPLANES_3 = 3;
    static constexpr uint8_t BACKPLANES_4 = 4;

    static constexpr uint8_t BANK_0 = 0;
    static constexpr uint8_t BANK_1 = 1;

    explicit PCF8576Full(Transport& transport);

    /** @brief Turn the display on (E = 1). RAM contents are preserved. */
    void enable();

    /** @brief Blank the display output (E = 0). RAM contents are preserved. */
    void disable();

    /** @brief Reconfigure drive mode and bias at runtime.
     *
     *  @param backplanes Number of backplanes — 1 (static), 2 (1:2), 3 (1:3),
     *                    4 (1:4 multiplex).
     *  @param bias       0 = 1/3 bias (recommended for 1:3 and 1:4 multiplex),
     *                    1 = 1/2 bias.
     */
    void set_mode(uint8_t backplanes, uint8_t bias);

    /** @brief Set the blink frequency.
     *
     *  @param frequency     0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz.
     *  @param alternate_bank true to enable alternate-RAM-bank blinking
     *                        (static and 1:2 multiplex only).
     */
    void set_blink(uint8_t frequency, bool alternate_bank = false);

    /** @brief Select the active RAM bank.
     *
     *  @param input_bank  0 (rows 0-1) or 1 (rows 2-3).
     *  @param output_bank 0 (rows 0-1) or 1 (rows 2-3).
     *  Only meaningful in static and 1:2 multiplex modes.
     */
    void set_bank(uint8_t input_bank, uint8_t output_bank);

    /** @brief Change the subaddress counter for cascaded displays.
     *
     *  @param subaddress 0-7; must match the A0/A1/A2 pin state of the
     *                    target device on the bus.
     */
    void device_select(uint8_t subaddress);

private:
    bool     _enabled;
    uint8_t  _bias;

    uint8_t _mode_code(uint8_t backplanes) const;
    void    _apply_mode();
};
