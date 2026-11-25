import time


class PCF8579Minimal:
    """PCF8579 LCD column driver — minimal interface.

    The PCF8579 is the column-driver companion of the PCF8578 and cannot run
    alone: the clock, frame sync and row waveforms come from a PCF8578 (or an
    equivalent external source). This driver writes the display RAM of one
    PCF8579 (4 banks x 40 columns, X = n drives output Cn). The chip has no
    register map; command bytes and display data are streamed with the plain
    ``Connection`` ``write`` / ``write_read`` calls. The I2C address is 0x3C
    (SA0 = VSS) or 0x3D (SA0 = VDD); the chip supports 100 kHz at most.

    The constructor waits for the internal reset, clears this device's RAM,
    then switches the display on (normal video, start bank 0). If the
    hardware subaddress pins A3:A0 do not match ``subaddress`` the chip does
    not acknowledge the data bytes and ``init`` raises ``OSError`` from the
    connection. A shadow copy of the 4 x 40 byte RAM is kept so single-pixel
    updates need only one byte on the bus. One RAM byte is 8 vertical pixels
    of one column; bit 0 is the top row of the bank.

    Note: ``device-select``, ``set-mode`` and ``set-start-bank`` reach every
    device on the I2C address, so all drivers on one bus must use the same
    ``rows`` / ``mixed`` settings.

    Args:
        connection: Configured I2C connection pointing at the device
            (100 kHz maximum).
        subaddress: hardware subaddress set on pins A3:A0, 0 .. 15.
        rows: number of display rows — 8, 16 (default), 24 or 32.
        mixed: ``True`` if the PCF8578 in the system runs in mixed mode
            (sets the ``T`` bit); default ``False`` (PCF8578 as row driver).
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

    def __init__(self, connection, subaddress=0, rows=16, mixed=False):
        if rows not in self._MUX_CODE:
            raise ValueError('rows must be 8, 16, 24 or 32')
        if subaddress < 0 or subaddress > 15:
            raise ValueError('subaddress must be in 0..15')
        self._connection = connection
        self._subaddress = subaddress
        self._rows = rows
        self._mixed = bool(mixed)
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

    def _select_byte(self):
        return self._CONT | self._CMD_DEVICE_SELECT | self._subaddress

    def _clear_shadow(self):
        for bank in self._shadow:
            for i in range(self._RAM_COLUMNS):
                bank[i] = 0

    def _clear_ram(self):
        out = bytearray()
        out.append(self._select_byte())
        out.append(self._ram_access_byte(self._ACCESS_FULL_GRAPHIC, 0))
        out.append(0x00)  # load-X 0 (C = 0)
        out.extend(bytes(self._RAM_COLUMNS * self._RAM_BANKS))
        self._connection.write(bytes(out))
        self._clear_shadow()

    def _init(self):
        time.sleep(0.002)  # >= 1 ms for the internal power-on reset
        self._start_bank = 0
        out = bytearray()
        out.append(self._select_byte())                                   # device-select
        out.append(self._mode_byte(self._STATUS_BLANK, True))             # blank
        out.append(self._CONT | self._CMD_SET_START_BANK)                 # start bank 0
        out.append(self._ram_access_byte(self._ACCESS_FULL_GRAPHIC, 0))   # full-graphic, bank 0
        out.append(0x00)                                                  # load-X 0 (C = 0)
        out.extend(bytes(self._RAM_COLUMNS * self._RAM_BANKS))
        self._connection.write(bytes(out))
        self._clear_shadow()
        self._status = self._STATUS_NORMAL
        self._connection.write(bytes([self._mode_byte(self._STATUS_NORMAL, False)]))

    def _ram_write(self, x, bank, data, access=0):
        """Write ``data`` at RAM column ``x`` / bank ``bank`` and mirror it in the shadow."""
        out = bytearray()
        out.append(self._select_byte())
        out.append(self._ram_access_byte(access, bank))
        out.append(x & 0x3F)
        out.extend(data)
        self._connection.write(bytes(out))
        per_col = (1, 2, 4)[access]
        for i, b in enumerate(data):
            col = x + (i // per_col)
            if col < self._RAM_COLUMNS:
                self._shadow[bank + (i % per_col)][col] = b

    # -- public API ---------------------------------------------------------

    def clear(self):
        """Zero this device's whole display RAM (4 banks x 40 columns) and the shadow."""
        self._clear_ram()

    def set_pixel(self, x, y, on=True):
        """Set or clear one pixel of this device.

        Out-of-range coordinates are ignored. Writes exactly one RAM byte.

        Args:
            x: column, 0 .. 39.
            y: row, 0 .. rows - 1.
            on: ``True`` lights the pixel, ``False`` clears it.
        """
        if x < 0 or x >= self._RAM_COLUMNS or y < 0 or y >= self._rows:
            return
        bank = y // 8
        bit = 1 << (y % 8)
        value = self._shadow[bank][x]
        value = (value | bit) if on else (value & ~bit & 0xFF)
        self._ram_write(x, bank, bytes([value]))

    def write_columns(self, x, bank, data):
        """Write consecutive column bytes in character access mode.

        Args:
            x: first column, 0 .. 39.
            bank: bank (8-row band), 0 .. rows/8 - 1.
            data: column bytes; bit 0 is the top row of the bank. The write
                is truncated at column 39.
        """
        if bank < 0 or bank >= self._rows // 8:
            raise ValueError('bank out of range for the configured rows')
        if x < 0 or x >= self._RAM_COLUMNS:
            return
        data = bytes(data)[:self._RAM_COLUMNS - x]
        if not data:
            return
        self._ram_write(x, bank, data)


class PCF8579Full(PCF8579Minimal):
    """PCF8579 full interface — extends PCF8579Minimal.

    Adds pixel read-back from the shadow, fill, line/rectangle/bitmap
    drawing, display status (blank / normal / all-on / inverse), multiplex
    rate and PCF8578 mode bit, hardware vertical scrolling via the start
    bank, and raw RAM write/read in all three access modes including
    transfers that run on into the following cascaded devices.

    Args:
        connection: Configured I2C connection pointing at the device
            (100 kHz maximum).
        subaddress: hardware subaddress set on pins A3:A0, 0 .. 15.
        rows: number of display rows — 8, 16 (default), 24 or 32.
        mixed: ``True`` if the PCF8578 in the system runs in mixed mode.
    """

    DISPLAY_BLANK = 0
    DISPLAY_NORMAL = 1
    DISPLAY_ALL_ON = 2
    DISPLAY_INVERSE = 3

    ACCESS_CHARACTER = 0
    ACCESS_HALF_GRAPHIC = 1
    ACCESS_FULL_GRAPHIC = 2

    def _plot(self, x, y, on, dirty):
        if x < 0 or x >= self._RAM_COLUMNS or y < 0 or y >= self._rows:
            return
        bank = y // 8
        bit = 1 << (y % 8)
        value = self._shadow[bank][x]
        self._shadow[bank][x] = (value | bit) if on else (value & ~bit & 0xFF)
        dirty.add((bank, x))

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
            x: column, 0 .. 39.
            y: row.

        Returns:
            ``True`` if the pixel is set; ``False`` if clear or out of range.
        """
        if x < 0 or x >= self._RAM_COLUMNS or y < 0 or y >= self._rows:
            return False
        return bool(self._shadow[y // 8][x] & (1 << (y % 8)))

    def fill(self, on=True):
        """Set every displayed pixel (rows x 40) on or off.

        Args:
            on: ``True`` lights all pixels, ``False`` clears them.
        """
        value = 0xFF if on else 0x00
        for bank in range(self._rows // 8):
            self._ram_write(0, bank, bytes([value] * self._RAM_COLUMNS))

    def draw_line(self, x0, y0, x1, y1, on=True):
        """Draw a Bresenham line, written as one batch of changed bytes.

        Args:
            x0: start column.
            y0: start row.
            x1: end column.
            y1: end row.
            on: ``True`` draws, ``False`` erases.
        """
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
        if w <= 0 or h <= 0:
            return
        dirty = set()
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                if fill or yy == y or yy == y + h - 1 or xx == x or xx == x + w - 1:
                    self._plot(xx, yy, on, dirty)
        self._flush(dirty)

    def show_bitmap(self, x, y, columns):
        """Blit 8-pixel-high column bytes aligned to a bank boundary.

        Args:
            x: left column.
            y: top row; must be a non-negative multiple of 8.
            columns: one byte per column (bit 0 = top row). Content past
                column 39 is clipped.
        """
        if y % 8 != 0 or y < 0:
            raise ValueError('y must be a non-negative multiple of 8')
        self.write_columns(x, y // 8, columns)

    def set_display(self, status):
        """Set the display status; RAM contents are preserved. Affects all devices on the address.

        Args:
            status: ``DISPLAY_BLANK``, ``DISPLAY_NORMAL``, ``DISPLAY_ALL_ON``
                or ``DISPLAY_INVERSE``.
        """
        if status < 0 or status > 3:
            raise ValueError('status must be in 0..3')
        self._status = status
        self._connection.write(bytes([self._mode_byte(status, False)]))

    def set_mode(self, rows, mixed=False):
        """Change the multiplex rate and the PCF8578 mode bit (all devices).

        Args:
            rows: 8, 16, 24 or 32.
            mixed: ``True`` if the PCF8578 runs in mixed mode (``T`` = 1).
        """
        if rows not in self._MUX_CODE:
            raise ValueError('rows must be 8, 16, 24 or 32')
        self._rows = rows
        self._mixed = bool(mixed)
        self._connection.write(bytes([self._mode_byte(self._status, False)]))

    def set_start_bank(self, bank):
        """Select the RAM bank shown at the top of the display (vertical scroll, all devices).

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
    def _check_ram_args(x, bank, count, access, cascade=False):
        if access < 0 or access > 2:
            raise ValueError('access must be in 0..2')
        if x < 0 or x > 39 or bank < 0 or bank > 3:
            raise ValueError('x must be in 0..39 and bank in 0..3')
        if access == 1 and bank % 2:
            raise ValueError('half-graphic access must start at an even bank')
        if access == 2 and bank != 0:
            raise ValueError('full-graphic access must start at bank 0')
        per_col = (1, 2, 4)[access]
        if not cascade and x + (count + per_col - 1) // per_col > 40:
            raise ValueError('write would pass column 39')

    def write_ram(self, x, bank, data, mode=0):
        """Raw RAM write of this device in character, half-graphic or full-graphic order.

        Args:
            x: RAM column, 0 .. 39.
            bank: RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic).
            data: bytes to write; must not pass column 39.
            mode: ``ACCESS_CHARACTER`` (default), ``ACCESS_HALF_GRAPHIC`` or
                ``ACCESS_FULL_GRAPHIC``.
        """
        data = bytes(data)
        self._check_ram_args(x, bank, len(data), mode)
        if not data:
            return
        self._ram_write(x, bank, data, mode)

    def write_ram_cascade(self, data, mode=2):
        """Write from column 0, bank 0 of this device and run on into the next subaddresses.

        The chip advances the subaddress counter after column 39, so one
        transfer fills several cascaded PCF8579s. The shadow only tracks this
        device's first 40 columns.

        Args:
            data: bytes to write.
            mode: access mode, default ``ACCESS_FULL_GRAPHIC``.
        """
        data = bytes(data)
        self._check_ram_args(0, 0, len(data), mode, cascade=True)
        if not data:
            return
        self._ram_write(0, 0, data, mode)

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
        self._check_ram_args(x, bank, n, mode)
        cmds = bytes([
            self._select_byte(),
            self._ram_access_byte(mode, bank),
            x & 0x3F,
        ])
        return self._connection.write_read(cmds, n)

    def reset(self):
        """Re-run initialisation (clears this device's RAM, normal video, start bank 0)."""
        self._init()
