class ST7036Minimal:
    """ST7036 character LCD controller — minimal interface.

    Drives an EA DOGM162W-A (2 x 16, 3.3 V) over 4-wire serial: SI, SCL, CSB, RS (C/D)
    and RESET. The HD44780-compatible instruction set has an extension table (selected
    by Function Set IS[2:1]) for bias, booster, follower and contrast; the DOG
    presets from the data sheet (page 7) are sent at initialisation.

    The connection is a display connection (DisplayConnection, see
    specs/transport_display.md) with ``busy=None``: the serial mode is write-only and
    has no busy flag, so every instruction is followed by a fixed wait (30 us, 2 ms
    after Clear / Return Home). Every instruction is a single byte sent with RS
    (D/C#) low; every data byte gets its own CS window so the chip has time to
    process it, followed by the 30 us gap.

    Class attributes:
        COLUMNS, ROWS: Display size of the DOGM162, 16 x 2.

    Args:
        connection: A DisplayConnection (SIMPLEX wiring, no BUSY pin), SPI mode 0 at
            about 1 MHz or less (SCL cycle >= 200 ns at <= 4.5 V).
    """

    COLUMNS = 16
    ROWS = 2

    _CMD_CLEAR = 0x01
    _CMD_HOME = 0x02
    _CMD_ENTRY = 0x04
    _CMD_DISPLAY = 0x08
    _CMD_SHIFT = 0x10
    _CMD_FUNCTION = 0x20
    _CMD_CGRAM = 0x40
    _CMD_DDRAM = 0x80
    _CMD_BIAS = 0x14        # table 1: 0x14 | BS << 3 | FX
    _CMD_ICONADDR = 0x40    # table 1
    _CMD_POWER = 0x50       # table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
    _CMD_FOLLOWER = 0x60    # table 1: | Fon << 3 | Rab2..0
    _CMD_CONTRAST = 0x70    # table 1: | C3..C0
    _CMD_DHPOS = 0x10       # table 2: | UD << 3

    def __init__(self, connection):
        self._connection = connection
        self._lines = self.ROWS
        self.columns = self.COLUMNS
        self.rows = self.ROWS
        self._n = 1             # Function Set N
        self._dh = 0            # Function Set DH
        self._bon = 1           # booster on
        self._ion = 0           # ICON display on
        self._contrast = 0x18   # C5..C0
        self._fon = 1           # follower on
        self._rab = 5           # Rab2..0
        self._quarter = False   # bias 1/4
        self._fx = 0            # bias FX bit
        self._display = 0x0C    # Display ON/OFF byte
        self._entry = 0x06      # Entry Mode byte
        self._addr = 0          # cached DDRAM address
        self._init_sequence(0x39, 0x14, 0x55, 0x6D, 0x78)

    # ---- internals -----------------------------------------------------------------------

    def _instr(self, byte, wait_us=30):
        """Send one instruction byte (RS low) and wait `wait_us` microseconds."""
        self._connection.send_command(byte)
        if wait_us >= 1000:
            self._connection.delay_ms((wait_us + 999) // 1000)
        else:
            self._connection.delay_us(wait_us)

    def _data(self, byte):
        """Send one data byte (RS high) in its own CS window, then wait 30 us."""
        self._connection.send_data(bytes((byte,)))
        self._connection.delay_us(30)

    def _row_start(self, row):
        """DDRAM address of the start of line `row`."""
        if self._lines == 3:
            return row * 0x10
        return row * 0x40

    def _set_ddram(self, addr):
        self._addr = addr & 0x7F
        self._instr(self._CMD_DDRAM | self._addr)

    def _function(self, table):
        """Function Set byte for the cached N / DH and instruction table `table` (0..2)."""
        return self._CMD_FUNCTION | 0x10 | (self._n << 3) | (self._dh << 2) | (table & 3)

    def _power_byte(self):
        """Power/ICON/Contrast high byte from the cached Ion, Bon and contrast."""
        return self._CMD_POWER | (self._ion << 3) | (self._bon << 2) | ((self._contrast >> 4) & 3)

    def _init_sequence(self, func, bias, power, follower, contrast):
        """Hardware reset, power-up wait and the instruction sequence of a preset."""
        self._connection.reset()
        self._connection.delay_ms(40)
        self._instr(func)
        self._instr(bias)
        self._instr(contrast)
        self._instr(power)
        self._instr(follower)
        self._connection.delay_ms(200)          # booster / V0 settle
        self._instr(func & ~0x01)               # back to instruction table 0
        self._instr(self._display)
        self._instr(self._CMD_CLEAR, 2000)
        self._instr(self._entry)
        self._addr = 0

    # ---- public API ----------------------------------------------------------------------

    def clear(self):
        """Clear the display (Clear Display, 2 ms); the cursor goes to the top left."""
        self._instr(self._CMD_CLEAR, 2000)
        self._addr = 0

    def home(self):
        """Return the cursor (and a shifted display) to the home position (Return Home, 2 ms)."""
        self._instr(self._CMD_HOME, 2000)
        self._addr = 0

    def set_cursor(self, col, row):
        """Move the cursor to (`col`, `row`). Out-of-range positions are ignored.

        Args:
            col: Column, 0 .. columns - 1.
            row: Row, 0 .. rows - 1.
        """
        if col < 0 or row < 0 or col >= self.columns or row >= self._lines:
            return
        self._set_ddram(self._row_start(row) + col)

    def write(self, text):
        """Write text at the cursor, one data byte per transfer with a 30 us gap.

        ASCII 0x20 .. 0x7D is written as is; any other character becomes '?'. The
        cursor does not wrap to the next line.

        Args:
            text: A str (or bytes) to write.
        """
        for c in text:
            if isinstance(c, str):
                c = ord(c)
            self.write_char(c if 0x20 <= c <= 0x7D else 0x3F)

    def write_char(self, code):
        """Write one raw character code (including the CGRAM codes 0..7) at the cursor.

        Args:
            code: Character code 0..255.
        """
        self._data(code & 0xFF)
        self._addr = (self._addr + 1) & 0x7F

    def print_at(self, col, row, text):
        """Move the cursor to (`col`, `row`) and write `text`.

        Args:
            col: Column.
            row: Row.
            text: Text to write.
        """
        self.set_cursor(col, row)
        self.write(text)


class ST7036Full(ST7036Minimal):
    """ST7036 character LCD controller — full interface.

    Extends ST7036Minimal with the DOGM081 / DOGM162 / DOGM163 modules at 3.3 V or 5 V,
    display control (on/off, cursor, blink, entry mode, shift), contrast, booster,
    follower and bias, custom characters (CGRAM), double height, ICON RAM and raw
    instructions.

    Class attributes:
        SUPPLY_3V3, SUPPLY_5V: Supply voltage of the module (3.3 V uses the booster).

    Args:
        connection: A DisplayConnection (SIMPLEX wiring, no BUSY pin).
        lines: 1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16).
        columns: Characters per line, or None for the module default (8 or 16).
        supply: SUPPLY_3V3 (booster on) or SUPPLY_5V (booster off).
        contrast: Contrast C5..C0 0..63, or None for the data sheet preset of the module.
    """

    SUPPLY_3V3 = 0
    SUPPLY_5V = 1

    # (function, bias, power, follower, contrast low) per module and supply, data sheet page 7
    _PRESETS = (
        ((0x31, 0x14, 0x55, 0x6D, 0x7C), (0x31, 0x1C, 0x51, 0x6A, 0x74)),   # DOGM081
        ((0x39, 0x14, 0x55, 0x6D, 0x78), (0x39, 0x1C, 0x52, 0x69, 0x74)),   # DOGM162
        ((0x39, 0x15, 0x55, 0x6E, 0x72), (0x39, 0x1D, 0x50, 0x6C, 0x7C)),   # DOGM163
    )

    def __init__(self, connection, lines=2, columns=None, supply=SUPPLY_3V3, contrast=None):
        if lines not in (1, 2, 3):
            raise ValueError('lines must be 1, 2 or 3')
        self._connection = connection
        func, bias, power, follower, low = self._PRESETS[lines - 1][supply]
        self._lines = lines
        self.rows = lines
        self.columns = columns if columns is not None else (8 if lines == 1 else 16)
        self._n = (func >> 3) & 1
        self._dh = 0
        self._bon = (power >> 2) & 1
        self._ion = 0
        self._contrast = ((power & 3) << 4) | (low & 0x0F)
        if contrast is not None:
            self._contrast = contrast & 0x3F
            power = (power & ~3) | ((self._contrast >> 4) & 3)
            low = self._CMD_CONTRAST | (self._contrast & 0x0F)
        self._fon = 1
        self._rab = follower & 7
        self._quarter = bool((bias >> 3) & 1)
        self._fx = bias & 1
        self._display = 0x0C
        self._entry = 0x06
        self._addr = 0
        self._init_sequence(func, bias, power, follower, low)

    # ---- display control -----------------------------------------------------------------

    def _set_display_bit(self, mask, on):
        self._display = (self._display | mask) if on else (self._display & ~mask)
        self._display = (self._display & 0x07) | self._CMD_DISPLAY
        self._instr(self._display)

    def display(self, on):
        """Turn the display on or off (the DDRAM content is kept).

        Args:
            on: True = display on.
        """
        self._set_display_bit(0x04, on)

    def cursor(self, on, blink=False):
        """Show or hide the cursor and its blinking block.

        Args:
            on: Underline cursor.
            blink: Blinking character block.
        """
        self._set_display_bit(0x02, on)
        self._set_display_bit(0x01, blink)

    def set_entry_mode(self, increment, shift=False):
        """Set the entry mode: cursor direction and display shift on write.

        Args:
            increment: True = cursor moves right (address + 1), False = left.
            shift: True = shift the display on every write.
        """
        self._entry = self._CMD_ENTRY | (2 if increment else 0) | (1 if shift else 0)
        self._instr(self._entry)

    def shift_cursor(self, right):
        """Shift the cursor one position.

        Args:
            right: True = right, False = left.
        """
        self._instr(self._CMD_SHIFT | (0x04 if right else 0))

    def shift_display(self, right):
        """Shift the whole display one position (the cursor follows).

        Args:
            right: True = right, False = left.
        """
        self._instr(self._CMD_SHIFT | 0x08 | (0x04 if right else 0))

    def return_home(self):
        """Return the cursor and a shifted display to the home position (2 ms)."""
        self.home()

    def set_ddram_address(self, address):
        """Set a raw DDRAM address (for use with display shift).

        Args:
            address: DDRAM address 0..0x7F.
        """
        self._set_ddram(address)

    # ---- contrast and supply -------------------------------------------------------------

    def _ext_begin(self):
        self._instr(self._function(1))

    def _ext_end(self):
        self._instr(self._function(0))

    def set_contrast(self, value):
        """Set the contrast C5..C0.

        Args:
            value: Contrast 0..63.
        """
        self._contrast = value & 0x3F
        self._ext_begin()
        self._instr(self._power_byte())
        self._instr(self._CMD_CONTRAST | (self._contrast & 0x0F))
        self._ext_end()

    def set_booster(self, on):
        """Switch the voltage booster on or off (needed at 3.3 V).

        Args:
            on: True = booster on.
        """
        self._bon = 1 if on else 0
        self._ext_begin()
        self._instr(self._power_byte())
        self._ext_end()

    def set_follower(self, on, ratio):
        """Set the voltage follower and the V0 amplification.

        Args:
            on: True = follower on.
            ratio: Rab2..Rab0 0..7: 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75.
        """
        self._fon = 1 if on else 0
        self._rab = ratio & 7
        self._ext_begin()
        self._instr(self._CMD_FOLLOWER | (self._fon << 3) | self._rab)
        self._ext_end()

    def set_bias(self, quarter):
        """Set the LCD bias: 1/5 (default) or 1/4. FX follows the line count.

        Args:
            quarter: True = 1/4 bias, False = 1/5 bias.
        """
        self._quarter = bool(quarter)
        self._ext_begin()
        self._instr(self._CMD_BIAS | (8 if quarter else 0) | self._fx)
        self._ext_end()

    # ---- custom characters and special modes ---------------------------------------------

    def create_char(self, index, rows):
        """Define a user character (CGRAM) and restore the DDRAM address.

        Args:
            index: Character code 0..7.
            rows: 8 row patterns, 5 bits each (bit 4 = leftmost pixel).
        """
        saved = self._addr
        self._instr(self._CMD_CGRAM | ((index & 7) << 3))
        for i in range(8):
            self._data(rows[i] & 0x1F)
        self._set_ddram(saved)

    def set_double_height(self, on, upper=True):
        """Double-height font (1-line module: 5 x 16 font; 3-line module: top or bottom pair).

        Ignored on the 2-line module (the combination is forbidden by the data sheet).

        Args:
            on: Double height on.
            upper: 3-line module only: True = COM1..16 (UD = 1), False = COM9..24.
        """
        if self._lines == 2:
            return
        self._dh = 1 if on else 0
        self._instr(self._function(0 if self._lines == 1 else 2))
        if self._lines == 3:
            self._instr(self._CMD_DHPOS | (8 if upper else 0))
        self._instr(self._function(0))

    def icon(self, on):
        """Turn the ICON display on or off (no icons are wired on the DOG modules).

        Args:
            on: True = ICON display on.
        """
        self._ion = 1 if on else 0
        self._ext_begin()
        self._instr(self._power_byte())
        self._ext_end()

    def write_icon(self, address, value):
        """Write one ICON RAM byte.

        Args:
            address: ICON RAM address 0..15.
            value: Byte to store.
        """
        saved = self._addr
        self._ext_begin()
        self._instr(self._CMD_ICONADDR | (address & 0x0F))
        self._data(value & 0xFF)
        self._ext_end()
        self._set_ddram(saved)

    def send_command(self, instruction):
        """Send any instruction byte (RS low) and wait 30 us.

        Args:
            instruction: Instruction byte.
        """
        self._instr(instruction & 0xFF)

    def send_data(self, value):
        """Send any data byte (RS high) and wait 30 us.

        Args:
            value: Data byte.
        """
        self.write_char(value)
