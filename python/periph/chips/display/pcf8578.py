import time


class PCF8578Minimal:
    """PCF8578 row/column LCD dot-matrix driver — minimal interface.

    Drives a stand-alone dot-matrix LCD (mixed mode: the chip drives both
    rows and columns) out of the box. With ``rows`` rows the panel is
    ``40 - rows`` columns wide (8x32, 16x24, 24x16 or 32x8). The chip has no
    register map; this driver streams command bytes and display data with
    the plain ``Connection`` ``write`` / ``write_read`` calls. The I2C
    address is 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD); the chip supports
    100 kHz at most.

    The constructor waits for the internal reset, clears the whole display
    RAM, then switches the display on (normal video, start bank 0). A shadow
    copy of the 4 x 40 byte RAM is kept so single-pixel updates need only one
    byte on the bus. One RAM byte is 8 vertical pixels of one column; bit 0
    is the top row of the bank.

    Args:
        connection: Configured I2C connection pointing at the device
            (100 kHz maximum).
        rows: number of display rows — 8, 16 (default), 24 or 32.
    """

    _ADDR_SA0_LOW = 0x3C
    _ADDR_SA0_HIGH = 0x3D

    _CMD_SET_MODE = 0x40
    _CMD_DEVICE_SELECT = 0x60
    _CMD_RAM_ACCESS = 0x70
    _CMD_SET_START_BANK = 0x7C
    _CONT = 0x80

    _STATUS_BLANK = 0
    _STATUS_NORMAL = 1
    _STATUS_ALL_ON = 2
    _STATUS_INVERSE = 3

    _ACCESS_CHARACTER = 0
    _ACCESS_HALF_GRAPHIC = 1
    _ACCESS_FULL_GRAPHIC = 2

    _RAM_COLUMNS = 40
    _RAM_BANKS = 4

    # multiplex rate (rows) -> M[1:0]
    _MUX_CODE = {8: 1, 16: 2, 24: 3, 32: 0}

    def __init__(self, connection, rows=16):
        if rows not in self._MUX_CODE:
            raise ValueError('rows must be 8, 16, 24 or 32')
        self._connection = connection
        self._rows = rows
        self._mixed = True
        self._status = self._STATUS_NORMAL
        self._start_bank = 0
        self._shadow = [bytearray(self._RAM_COLUMNS) for _ in range(self._RAM_BANKS)]
        self._init()

    # -- command helpers ----------------------------------------------------

    def _mode_byte(self, status, cont):
        return ((self._CONT if cont else 0) | self._CMD_SET_MODE
                | ((1 if self._mixed else 0) << 4)
                | (status << 2) | self._MUX_CODE[self._rows])

    def _ram_access_byte(self, access, bank, cont=True):
        return (self._CONT if cont else 0) | self._CMD_RAM_ACCESS | (access << 2) | bank

    def _init(self):
        time.sleep(0.002)  # >= 1 ms for the internal power-on reset
        self._mixed = True
        self._start_bank = 0
        out = bytearray()
        out.append(self._CONT | self._CMD_DEVICE_SELECT)                  # device-select 0
        out.append(self._mode_byte(self._STATUS_BLANK, True))             # mixed, blank
        out.append(self._CONT | self._CMD_SET_START_BANK)                 # start bank 0
        out.append(self._ram_access_byte(self._ACCESS_FULL_GRAPHIC, 0))   # full-graphic, bank 0
        out.append(0x00)                                                  # load-X 0 (C = 0)
        out.extend(bytes(self._RAM_COLUMNS * self._RAM_BANKS))
        self._connection.write(bytes(out))
        for bank in self._shadow:
            for i in range(self._RAM_COLUMNS):
                bank[i] = 0
        self._status = self._STATUS_NORMAL
        self._connection.write(bytes([self._mode_byte(self._STATUS_NORMAL, False)]))

    def _require_mixed(self):
        if not self._mixed:
            raise RuntimeError('display RAM is only accessible in mixed mode')

    def _ram_write(self, x, bank, data, access=0):
        """Write ``data`` at RAM column ``x`` / bank ``bank`` and mirror it in the shadow."""
        self._require_mixed()
        out = bytearray()
        out.append(self._CONT | self._CMD_DEVICE_SELECT)
        out.append(self._ram_access_byte(access, bank))
        out.append(x & 0x3F)
        out.extend(data)
        self._connection.write(bytes(out))
        per_col = (1, 2, 4)[access]
        for i, b in enumerate(data):
            self._shadow[bank + (i % per_col)][x + (i // per_col)] = b

    # -- public API ---------------------------------------------------------

    def clear(self):
        """Zero the whole display RAM (4 banks x 40 columns) and the shadow."""
        self._require_mixed()
        out = bytearray()
        out.append(self._CONT | self._CMD_DEVICE_SELECT)
        out.append(self._ram_access_byte(self._ACCESS_FULL_GRAPHIC, 0))
        out.append(0x00)
        out.extend(bytes(self._RAM_COLUMNS * self._RAM_BANKS))
        self._connection.write(bytes(out))
        for bank in self._shadow:
            for i in range(self._RAM_COLUMNS):
                bank[i] = 0

    def set_pixel(self, x, y, on=True):
        """Set or clear one pixel.

        Out-of-range coordinates are ignored. Writes exactly one RAM byte.

        Args:
            x: display column, 0 .. (40 - rows - 1).
            y: display row, 0 .. rows - 1.
            on: ``True`` lights the pixel, ``False`` clears it.
        """
        self._require_mixed()
        if x < 0 or x >= self._RAM_COLUMNS - self._rows or y < 0 or y >= self._rows:
            return
        ram_x = self._rows + x
        bank = y // 8
        bit = 1 << (y % 8)
        value = self._shadow[bank][ram_x]
        value = (value | bit) if on else (value & ~bit & 0xFF)
        self._ram_write(ram_x, bank, bytes([value]))

    def write_columns(self, x, bank, data):
        """Write consecutive column bytes in character access mode.

        Args:
            x: first display column, 0 .. (40 - rows - 1).
            bank: display bank (8-row band), 0 .. rows/8 - 1.
            data: column bytes; bit 0 is the top row of the bank. The write
                is truncated at the last display column.
        """
        if bank < 0 or bank >= self._rows // 8:
            raise ValueError('bank out of range for the configured rows')
        columns = self._RAM_COLUMNS - self._rows
        if x < 0 or x >= columns:
            return
        data = bytes(data)[:columns - x]
        if not data:
            return
        self._ram_write(self._rows + x, bank, data)


class PCF8578Full(PCF8578Minimal):
    """PCF8578 full interface — extends PCF8578Minimal.

    Adds pixel read-back from the shadow, fill, line/rectangle/bitmap
    drawing, display status (blank / normal / all-on / inverse), multiplex
    rate and row mode, hardware vertical scrolling via the start bank, raw
    RAM write/read in all three access modes, and the device-select command
    for PCF8579 cascades.

    Args:
        connection: Configured I2C connection pointing at the device
            (100 kHz maximum).
        rows: number of display rows — 8, 16 (default), 24 or 32.
    """

    DISPLAY_BLANK = 0
    DISPLAY_NORMAL = 1
    DISPLAY_ALL_ON = 2
    DISPLAY_INVERSE = 3

    ACCESS_CHARACTER = 0
    ACCESS_HALF_GRAPHIC = 1
    ACCESS_FULL_GRAPHIC = 2

    def _plot(self, x, y, on, dirty):
        columns = self._RAM_COLUMNS - self._rows
        if x < 0 or x >= columns or y < 0 or y >= self._rows:
            return
        ram_x = self._rows + x
        bank = y // 8
        bit = 1 << (y % 8)
        value = self._shadow[bank][ram_x]
        self._shadow[bank][ram_x] = (value | bit) if on else (value & ~bit & 0xFF)
        dirty.add((bank, ram_x))

    def _flush(self, dirty):
        # one transaction per run of consecutive changed columns in a bank
        items = sorted(dirty)
        i = 0
        while i < len(items):
            bank, start = items[i]
            end = start
            while i + 1 < len(items) and items[i + 1] == (bank, end + 1):
                i += 1
                end += 1
            self._ram_write(start, bank, bytes(self._shadow[bank][start:end + 1]))
            i += 1

    def get_pixel(self, x, y):
        """Return the state of one pixel from the shadow (no bus traffic).

        Args:
            x: display column.
            y: display row.

        Returns:
            ``True`` if the pixel is set; ``False`` if clear or out of range.
        """
        if x < 0 or x >= self._RAM_COLUMNS - self._rows or y < 0 or y >= self._rows:
            return False
        return bool(self._shadow[y // 8][self._rows + x] & (1 << (y % 8)))

    def fill(self, on=True):
        """Set every displayed pixel on or off.

        Args:
            on: ``True`` lights all pixels, ``False`` clears them.
        """
        value = 0xFF if on else 0x00
        columns = self._RAM_COLUMNS - self._rows
        for bank in range(self._rows // 8):
            self._ram_write(self._rows, bank, bytes([value] * columns))

    def draw_line(self, x0, y0, x1, y1, on=True):
        """Draw a Bresenham line, written as one batch of changed bytes.

        Args:
            x0: start column.
            y0: start row.
            x1: end column.
            y1: end row.
            on: ``True`` draws, ``False`` erases.
        """
        self._require_mixed()
        dirty = set()
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            self._plot(x0, y0, on, dirty)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy
        self._flush(dirty)

    def draw_rect(self, x, y, w, h, on=True, fill=False):
        """Draw a rectangle outline or a filled rectangle.

        Args:
            x: left column.
            y: top row.
            w: width in pixels.
            h: height in pixels.
            on: ``True`` draws, ``False`` erases.
            fill: ``True`` fills the interior.
        """
        self._require_mixed()
        if w <= 0 or h <= 0:
            return
        dirty = set()
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                if fill or yy == y or yy == y + h - 1 or xx == x or xx == x + w - 1:
                    self._plot(xx, yy, on, dirty)
        self._flush(dirty)

    def show_bitmap(self, x, y, bands):
        """Blit a column-major bitmap aligned to a bank boundary.

        Args:
            x: left display column.
            y: top display row; must be a multiple of 8.
            bands: a list of byte strings, one per 8-pixel-high band from the
                top, each holding one byte per column (bit 0 = top row). A
                single ``bytes`` object is treated as one band. Content that
                falls outside the display is clipped.
        """
        if y % 8 != 0 or y < 0:
            raise ValueError('y must be a non-negative multiple of 8')
        if isinstance(bands, (bytes, bytearray)):
            bands = [bands]
        for i, band in enumerate(bands):
            bank = y // 8 + i
            if bank >= self._rows // 8:
                break
            self.write_columns(x, bank, band)

    def set_display(self, status):
        """Set the display status; RAM contents are preserved.

        Args:
            status: ``DISPLAY_BLANK``, ``DISPLAY_NORMAL``, ``DISPLAY_ALL_ON``
                or ``DISPLAY_INVERSE``.
        """
        if status < 0 or status > 3:
            raise ValueError('status must be in 0..3')
        self._status = status
        self._connection.write(bytes([self._mode_byte(status, False)]))

    def set_mode(self, rows, mixed=True):
        """Change the multiplex rate and the row/mixed mode.

        In row mode (``mixed=False``) the RAM cannot be accessed; pixel and
        RAM calls raise ``RuntimeError`` until mixed mode is restored.

        Args:
            rows: 8, 16, 24 or 32.
            mixed: ``True`` for mixed mode (default), ``False`` for row mode.
        """
        if rows not in self._MUX_CODE:
            raise ValueError('rows must be 8, 16, 24 or 32')
        self._rows = rows
        self._mixed = bool(mixed)
        self._connection.write(bytes([self._mode_byte(self._status, False)]))

    def set_start_bank(self, bank):
        """Select the RAM bank shown at the top of the display (vertical scroll).

        Args:
            bank: 0 .. 3.
        """
        if bank < 0 or bank > 3:
            raise ValueError('bank must be in 0..3')
        self._start_bank = bank
        self._connection.write(bytes([self._CMD_SET_START_BANK | bank]))

    def scroll(self, banks):
        """Add ``banks`` (may be negative) to the start bank, modulo the banks in use.

        Args:
            banks: number of 8-row steps.
        """
        self.set_start_bank((self._start_bank + banks) % (self._rows // 8))

    @staticmethod
    def _check_ram_args(x, bank, count, access):
        if access < 0 or access > 2:
            raise ValueError('access must be in 0..2')
        if x < 0 or x > 39 or bank < 0 or bank > 3:
            raise ValueError('x must be in 0..39 and bank in 0..3')
        if access == 1 and bank % 2:
            raise ValueError('half-graphic access must start at an even bank')
        if access == 2 and bank != 0:
            raise ValueError('full-graphic access must start at bank 0')
        per_col = (1, 2, 4)[access]
        if x + (count + per_col - 1) // per_col > 40:
            raise ValueError('write would pass column 39')

    def write_ram(self, x, bank, data, mode=0):
        """Raw RAM write in character, half-graphic or full-graphic order.

        Args:
            x: RAM column, 0 .. 39.
            bank: RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic).
            data: bytes to write.
            mode: ``ACCESS_CHARACTER`` (default), ``ACCESS_HALF_GRAPHIC`` or
                ``ACCESS_FULL_GRAPHIC``.
        """
        data = bytes(data)
        self._check_ram_args(x, bank, len(data), mode)
        if not data:
            return
        self._ram_write(x, bank, data, mode)

    def read_ram(self, x, bank, n, mode=0):
        """Raw RAM read: address-set commands, repeated start, then ``n`` bytes.

        Args:
            x: RAM column, 0 .. 39.
            bank: RAM bank, 0 .. 3.
            n: number of bytes to read.
            mode: access mode as for :meth:`write_ram`.

        Returns:
            ``bytes`` of length ``n``.
        """
        self._require_mixed()
        self._check_ram_args(x, bank, n, mode)
        cmds = bytes([
            self._CONT | self._CMD_DEVICE_SELECT,
            self._ram_access_byte(mode, bank),
            x & 0x3F,
        ])
        return self._connection.write_read(cmds, n)

    def device_select(self, subaddress):
        """Load the subaddress counter (for PCF8579 cascades).

        Subaddress 0 addresses the PCF8578 itself. Pixel and RAM calls
        restore subaddress 0 before their own accesses.

        Args:
            subaddress: 0 .. 15.
        """
        if subaddress < 0 or subaddress > 15:
            raise ValueError('subaddress must be in 0..15')
        self._connection.write(bytes([self._CMD_DEVICE_SELECT | subaddress]))

    def reset(self):
        """Re-run initialisation at the current row count (clears RAM, mixed mode, normal video)."""
        self._init()
