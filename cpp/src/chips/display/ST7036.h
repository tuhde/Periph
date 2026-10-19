#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @brief ST7036 character LCD controller — minimal interface.
 *
 * Drives a Sitronix ST7036 over 4-wire serial: the Electronic Assembly / Display Visions DOG
 * series (EA DOGM162 2 x 16 here; DOGM081 and DOGM163 in ST7036Full). The chip has an on-chip
 * booster, bias divider and contrast, so only the display module's two small capacitors are needed.
 *
 * The connection is a display connection (`DisplayConnectionBase` family, see
 * `specs/transport_display.md`) with no BUSY pin (pass `nullptr`): D/C# = RS (C/D), RES# = RESET,
 * CS# = CSB. The interface is write-only, so the driver waits fixed times: 30 us after every byte
 * (`delayMicros`), 2 ms after Clear / Return Home and 200 ms after the follower is switched on.
 * Every instruction is one byte sent with D/C# low and every data byte its own transfer with D/C#
 * high, because the chip needs a gap between bytes. The extension instructions (bias, contrast,
 * follower) need Function Set `IS = 01` and the driver switches the table and back.
 *
 * No heap allocation, no exceptions; failures are reported through valid().
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class ST7036Minimal {
public:
    static constexpr uint8_t COLUMNS = 16;
    static constexpr uint8_t ROWS    = 2;

    // Instructions (table 0 unless noted)
    static constexpr uint8_t CMD_CLEAR     = 0x01;
    static constexpr uint8_t CMD_HOME      = 0x02;
    static constexpr uint8_t CMD_ENTRY     = 0x04;
    static constexpr uint8_t CMD_DISPLAY   = 0x08;
    static constexpr uint8_t CMD_SHIFT     = 0x10;
    static constexpr uint8_t CMD_FUNCTION  = 0x20;
    static constexpr uint8_t CMD_CGRAM     = 0x40;
    static constexpr uint8_t CMD_DDRAM     = 0x80;
    static constexpr uint8_t CMD_BIAS      = 0x14;   // table 1: 0x14 | BS << 3 | FX
    static constexpr uint8_t CMD_ICONADDR  = 0x40;   // table 1
    static constexpr uint8_t CMD_POWER     = 0x50;   // table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
    static constexpr uint8_t CMD_FOLLOWER  = 0x60;   // table 1: | Fon << 3 | Rab2..0
    static constexpr uint8_t CMD_CONTRAST  = 0x70;   // table 1: | C3..C0
    static constexpr uint8_t CMD_DHPOS     = 0x10;   // table 2: | UD << 3

    /**
     * @brief Construct the driver and run the DOGM162 3.3 V initialisation sequence.
     *
     * Hardware reset, 40 ms, Function Set `0x39`, Bias `0x14`, Contrast `0x78`, Power `0x55`
     * (booster on), Follower `0x6D`, 200 ms, Function Set `0x38`, Display on (`0x0C`), Clear,
     * Entry Mode `0x06`. The display is cleared and the cursor is at the top left.
     *
     * @param connection Display connection (CS#, D/C# = RS, RES#; no BUSY).
     */
    explicit ST7036Minimal(CONN& connection) : ST7036Minimal(connection, true) {}

    /** @brief True when construction succeeded. */
    bool valid() const { return _valid; }

    /** @brief Characters per line. */
    uint8_t columns() const { return _columns; }
    /** @brief Number of lines. */
    uint8_t rows() const { return _lines; }

    /** @brief Clear the display (Clear Display, 2 ms); the cursor goes to the top left. */
    void clear() {
        _instr(CMD_CLEAR, 2000);
        _addr = 0;
    }

    /** @brief Return the cursor (and a shifted display) to the home position (Return Home, 2 ms). */
    void home() {
        _instr(CMD_HOME, 2000);
        _addr = 0;
    }

    /**
     * @brief Move the cursor to (@p col, @p row). Out-of-range positions are ignored.
     * @param col Column, 0 .. columns() - 1.
     * @param row Row, 0 .. rows() - 1.
     */
    void set_cursor(uint8_t col, uint8_t row) {
        if (col >= _columns || row >= _lines) return;
        _set_ddram(_row_start(row) + col);
    }

    /**
     * @brief Write text at the cursor, one data byte per transfer with a 30 us gap.
     *
     * ASCII `0x20 .. 0x7D` is written as is; any other character becomes `?`. The cursor does not
     * wrap to the next line.
     *
     * @param text Zero-terminated string.
     */
    void write(const char* text) {
        if (text == nullptr) return;
        for (; *text != '\0'; text++) {
            uint8_t c = static_cast<uint8_t>(*text);
            write_char((c >= 0x20 && c <= 0x7D) ? c : static_cast<uint8_t>('?'));
        }
    }

    /**
     * @brief Write one raw character code (including CGRAM codes 0..7) at the cursor.
     * @param code Character code.
     */
    void write_char(uint8_t code) {
        if (!_valid) return;
        _connection.sendData(&code, 1);
        _connection.delayMicros(30);
        _addr = static_cast<uint8_t>((_addr + 1) & 0x7F);
    }

    /**
     * @brief Move the cursor to (@p col, @p row) and write @p text.
     */
    void print_at(uint8_t col, uint8_t row, const char* text) {
        set_cursor(col, row);
        write(text);
    }

protected:
    /** @brief Construct without running the initialisation (ST7036Full runs its module preset instead). */
    ST7036Minimal(CONN& connection, bool run_init)
        : _connection(connection), _valid(true), _lines(2), _columns(COLUMNS), _n(1), _dh(0),
          _bon(1), _ion(0), _contrast(0x18), _fon(1), _rab(5), _quarter(false), _fx(0),
          _display(0x0C), _entry(0x06), _addr(0) {
        if (!_connection.valid()) {
            _valid = false;
            return;
        }
        if (run_init) _init_sequence(0x39, 0x14, 0x55, 0x6D, 0x78);
    }

    /** @brief DDRAM address of the start of line @p row (1 line: 0x00; 2 lines: 0x00, 0x40; 3 lines: 0x00, 0x10, 0x20). */
    uint8_t _row_start(uint8_t row) const {
        if (_lines == 3) return static_cast<uint8_t>(row * 0x10);
        return static_cast<uint8_t>(row * 0x40);
    }

    /** @brief Send one instruction byte (D/C# low) and wait @p wait_us microseconds. */
    void _instr(uint8_t b, uint32_t wait_us = 30) {
        if (!_valid) return;
        _connection.sendCommand(b);
        if (wait_us >= 1000) _connection.delay((wait_us + 999) / 1000);
        else                 _connection.delayMicros(wait_us);
    }

    void _set_ddram(uint8_t addr) {
        _addr = addr & 0x7F;
        _instr(static_cast<uint8_t>(CMD_DDRAM | _addr));
    }

    /** @brief Function Set byte for the cached N / DH and instruction table @p is (0..2). */
    uint8_t _function(uint8_t is) const {
        return static_cast<uint8_t>(CMD_FUNCTION | 0x10 | (_n << 3) | (_dh << 2) | (is & 3));
    }

    /** @brief Power/ICON/Contrast high byte from the cached Ion, Bon and contrast. */
    uint8_t _power_byte() const {
        return static_cast<uint8_t>(CMD_POWER | (_ion << 3) | (_bon << 2) | ((_contrast >> 4) & 3));
    }

    /** @brief Hardware reset, power-up wait and the instruction sequence of a preset. */
    void _init_sequence(uint8_t func, uint8_t bias, uint8_t power, uint8_t follower, uint8_t contrast) {
        _connection.reset();
        _connection.delay(40);
        _instr(func);
        _instr(bias);
        _instr(contrast);
        _instr(power);
        _instr(follower);
        _connection.delay(200);                 // booster / V0 settle
        _instr(static_cast<uint8_t>(func & ~0x01));   // back to instruction table 0
        _instr(_display);
        _instr(CMD_CLEAR, 2000);
        _instr(_entry);
        _addr = 0;
    }

    CONN&   _connection;
    bool    _valid;
    uint8_t _lines;
    uint8_t _columns;
    uint8_t _n;          // Function Set N
    uint8_t _dh;         // Function Set DH
    uint8_t _bon;        // booster on
    uint8_t _ion;        // ICON display on
    uint8_t _contrast;   // C5..C0
    uint8_t _fon;        // follower on
    uint8_t _rab;        // Rab2..0
    bool    _quarter;    // bias 1/4
    uint8_t _fx;         // bias FX bit
    uint8_t _display;    // Display ON/OFF byte
    uint8_t _entry;      // Entry Mode byte
    uint8_t _addr;       // cached DDRAM address
};

/**
 * @brief ST7036 character LCD controller — full interface.
 *
 * Extends ST7036Minimal with the DOGM081 / DOGM162 / DOGM163 modules at 3.3 V or 5 V, display
 * control (on/off, cursor, blink, entry mode, shift), contrast, booster, follower and bias,
 * custom characters (CGRAM), double height, ICON RAM and raw instructions.
 *
 * @tparam CONN Display connection class.
 */
template <typename CONN>
class ST7036Full : public ST7036Minimal<CONN> {
public:
    typedef ST7036Minimal<CONN> Base;

    /** @brief Supply voltage of the module (selects the booster preset). */
    enum Supply : uint8_t { SUPPLY_3V3 = 0, SUPPLY_5V = 1 };

    /**
     * @brief Construct the driver for a DOG module and run its initialisation sequence.
     *
     * @param connection Display connection (no BUSY).
     * @param lines      1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16).
     * @param supply     SUPPLY_3V3 (booster on) or SUPPLY_5V (booster off).
     * @param contrast   Contrast `C5..C0` 0..63, or -1 for the datasheet preset of the module.
     */
    ST7036Full(CONN& connection, uint8_t lines = 2, Supply supply = SUPPLY_3V3, int8_t contrast = -1)
        : Base(connection, false) {
        if (!this->_valid) return;
        if (lines < 1 || lines > 3) lines = 2;
        struct Preset { uint8_t func, bias, power, follower, contrast; };
        static const Preset p[3][2] = {
            {{0x31, 0x14, 0x55, 0x6D, 0x7C}, {0x31, 0x1C, 0x51, 0x6A, 0x74}},   // DOGM081
            {{0x39, 0x14, 0x55, 0x6D, 0x78}, {0x39, 0x1C, 0x52, 0x69, 0x74}},   // DOGM162
            {{0x39, 0x15, 0x55, 0x6E, 0x72}, {0x39, 0x1D, 0x50, 0x6C, 0x7C}},   // DOGM163
        };
        Preset s = p[lines - 1][supply];
        this->_lines    = lines;
        this->_columns  = (lines == 1) ? 8 : 16;
        this->_n        = (s.func >> 3) & 1;
        this->_dh       = 0;
        this->_bon      = (s.power >> 2) & 1;
        this->_ion      = 0;
        this->_contrast = static_cast<uint8_t>(((s.power & 3) << 4) | (s.contrast & 0x0F));
        if (contrast >= 0) {
            this->_contrast = static_cast<uint8_t>(contrast & 0x3F);
            s.power    = static_cast<uint8_t>((s.power & ~3) | ((this->_contrast >> 4) & 3));
            s.contrast = static_cast<uint8_t>(Base::CMD_CONTRAST | (this->_contrast & 0x0F));
        }
        this->_fon      = 1;
        this->_rab      = s.follower & 7;
        this->_quarter  = (s.bias >> 3) & 1;
        this->_fx       = s.bias & 1;
        this->_init_sequence(s.func, s.bias, s.power, s.follower, s.contrast);
    }

    // ---- display control -----------------------------------------------------------------

    /** @brief Turn the display on or off (the DDRAM content is kept). */
    void display(bool on) { _set_display_bit(0x04, on); }

    /**
     * @brief Show or hide the cursor and its blinking block.
     * @param on    Underline cursor.
     * @param blink Blinking character block.
     */
    void cursor(bool on, bool blink = false) {
        _set_display_bit(0x02, on);
        _set_display_bit(0x01, blink);
    }

    /**
     * @brief Entry mode: cursor direction and display shift on write.
     * @param increment true = cursor moves right (address +1), false = left.
     * @param shift     true = shift the display on every write.
     */
    void set_entry_mode(bool increment, bool shift = false) {
        this->_entry = static_cast<uint8_t>(Base::CMD_ENTRY | (increment ? 2 : 0) | (shift ? 1 : 0));
        this->_instr(this->_entry);
    }

    /** @brief Shift the cursor one position. */
    void shift_cursor(bool right) { this->_instr(static_cast<uint8_t>(Base::CMD_SHIFT | (right ? 0x04 : 0))); }
    /** @brief Shift the whole display one position (the cursor follows). */
    void shift_display(bool right) { this->_instr(static_cast<uint8_t>(Base::CMD_SHIFT | 0x08 | (right ? 0x04 : 0))); }

    /** @brief Return the cursor and a shifted display to the home position (Return Home, 2 ms). */
    void return_home() { this->home(); }

    /** @brief Set a raw DDRAM address (for use with display shift). */
    void set_ddram_address(uint8_t address) { this->_set_ddram(address); }

    // ---- contrast and supply ---------------------------------------------------------------

    /** @brief Set the contrast `C5..C0` (0..63). */
    void set_contrast(uint8_t value) {
        this->_contrast = value & 0x3F;
        _ext_begin();
        this->_instr(this->_power_byte());
        this->_instr(static_cast<uint8_t>(Base::CMD_CONTRAST | (this->_contrast & 0x0F)));
        _ext_end();
    }

    /** @brief Switch the voltage booster on or off (needed at 3.3 V). */
    void set_booster(bool on) {
        this->_bon = on ? 1 : 0;
        _ext_begin();
        this->_instr(this->_power_byte());
        _ext_end();
    }

    /**
     * @brief Voltage follower on/off and V0 amplification.
     * @param on    Follower on.
     * @param ratio `Rab2..Rab0` 0..7: 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75.
     */
    void set_follower(bool on, uint8_t ratio) {
        this->_fon = on ? 1 : 0;
        this->_rab = ratio & 7;
        _ext_begin();
        this->_instr(static_cast<uint8_t>(Base::CMD_FOLLOWER | (this->_fon << 3) | this->_rab));
        _ext_end();
    }

    /** @brief LCD bias: 1/5 (default) or 1/4 (`quarter`); `FX` follows the line count. */
    void set_bias(bool quarter) {
        this->_quarter = quarter;
        _ext_begin();
        this->_instr(static_cast<uint8_t>(Base::CMD_BIAS | (quarter ? 8 : 0) | this->_fx));
        _ext_end();
    }

    // ---- custom characters and special modes -----------------------------------------------

    /**
     * @brief Define a user character (CGRAM) and restore the DDRAM address.
     * @param index Character code 0..7.
     * @param rows  8 row patterns, 5 bits each (bit 4 = leftmost pixel).
     */
    void create_char(uint8_t index, const uint8_t rows[8]) {
        uint8_t saved = this->_addr;
        this->_instr(static_cast<uint8_t>(Base::CMD_CGRAM | ((index & 7) << 3)));
        for (uint8_t i = 0; i < 8; i++) {
            uint8_t b = rows[i] & 0x1F;
            this->_connection.sendData(&b, 1);
            this->_connection.delayMicros(30);
        }
        this->_set_ddram(saved);
    }

    /**
     * @brief Double height font (1-line module: 5 x 16 font; 3-line module: top or bottom pair of lines).
     *
     * Ignored on the 2-line module (the combination is forbidden by the datasheet).
     *
     * @param on    Double height on.
     * @param upper 3-line module only: true = COM1..16 (UD = 1), false = COM9..24.
     */
    void set_double_height(bool on, bool upper = true) {
        if (this->_lines == 2) return;
        this->_dh = on ? 1 : 0;
        this->_instr(this->_function(this->_lines == 1 ? 0 : 2));
        if (this->_lines == 3) this->_instr(static_cast<uint8_t>(Base::CMD_DHPOS | (upper ? 8 : 0)));
        this->_instr(this->_function(0));
    }

    /** @brief ICON display on/off (no icons are wired on the DOG modules). */
    void icon(bool on) {
        this->_ion = on ? 1 : 0;
        _ext_begin();
        this->_instr(this->_power_byte());
        _ext_end();
    }

    /** @brief Write one ICON RAM byte (address 0..15). */
    void write_icon(uint8_t address, uint8_t value) {
        uint8_t saved = this->_addr;
        _ext_begin();
        this->_instr(static_cast<uint8_t>(Base::CMD_ICONADDR | (address & 0x0F)));
        this->_connection.sendData(&value, 1);
        this->_connection.delayMicros(30);
        _ext_end();
        this->_set_ddram(saved);
    }

    /** @brief Send any instruction byte (D/C# low) and wait 30 us. */
    void send_command(uint8_t instruction) { this->_instr(instruction); }
    /** @brief Send any data byte (D/C# high) and wait 30 us. */
    void send_data(uint8_t value) { this->write_char(value); }

private:
    void _ext_begin() { this->_instr(this->_function(1)); }
    void _ext_end()   { this->_instr(this->_function(0)); }

    void _set_display_bit(uint8_t mask, bool on) {
        this->_display = static_cast<uint8_t>(on ? (this->_display | mask) : (this->_display & ~mask));
        this->_display = static_cast<uint8_t>((this->_display & 0x07) | Base::CMD_DISPLAY);
        this->_instr(this->_display);
    }
};
