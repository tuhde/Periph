import time


class _I2CBus:
    """I²C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data)."""

    can_read = True

    def __init__(self, connection, chunk):
        self._conn = connection
        self._chunk = chunk

    def write_commands(self, data):
        self._conn.write(b'\x00' + bytes(data))

    def write_data(self, data):
        view = memoryview(data)
        step = self._chunk - 1
        for i in range(0, len(view), step):
            self._conn.write(b'\x40' + bytes(view[i:i + step]))

    def read_status(self):
        self._conn.write(b'\x00')
        return bytes(self._conn.read(1))[0]

    def read_ram(self, n):
        self._conn.write(b'\x40')
        return bytes(self._conn.read(n + 1))[1:]

    def hardware_reset(self):
        pass


class _SpiBus:
    """4-wire SPI transport on a DisplayConnection: D/C low for every command byte, high for RAM data."""

    can_read = False
    SPI_CHUNK = 240

    def __init__(self, connection):
        self._conn = connection

    def write_commands(self, data):
        for b in data:
            self._conn.send_command(b)

    def write_data(self, data):
        view = memoryview(data)
        for i in range(0, len(view), self.SPI_CHUNK):
            self._conn.send_data(bytes(view[i:i + self.SPI_CHUNK]))

    def hardware_reset(self):
        self._conn.reset()


class SH1106Minimal:
    """SH1106 132 x 64 OLED controller-driver — minimal interface (I²C).

    Drives a monochrome SH1106 OLED panel (typically a 1.3" 128 x 64 module) over I²C at
    ``0x3C`` (SA0 = VSS) or ``0x3D`` (SA0 = VDD1). The chip has 132 RAM columns; 128-pixel-wide
    modules show RAM columns 2..129, so ``column_offset`` defaults to 2.

    All drawing goes into a framebuffer of ``width * height / 8`` bytes (page-major, bit 0 =
    top pixel of the page); ``show`` sends it page by page to the display RAM. The chip has page
    addressing only, so every page needs its own page + column command.

    The initialization follows the datasheet power-on flow with the internal DC-DC: display off,
    setup stream (clock, multiplex, offset, start line, mapping, COM configuration, contrast,
    pre-charge, VCOM, pump 7.4 V), DC-DC on + 100 ms, RAM cleared, display on + 150 ms.

    Class attributes:
        WIDTH, HEIGHT: Maximum size, 132 x 64.
        I2C_CHUNK: Largest write in bytes including the control byte (32-byte limit of some I²C layers).

    Args:
        connection: I2CConnection at 0x3C or 0x3D (or, for the SPI classes, a DisplayConnection).
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8 and at most 64 (default 64).
        column_offset: First RAM column shown (default 2; 0 for a true 132-column panel).
        internal_dcdc: True for the internal charge pump (default), False for an external VPP.

    Raises:
        ValueError: If width, height or column_offset is out of range.
    """

    WIDTH = 132
    HEIGHT = 64
    I2C_CHUNK = 32

    _CMD_COL_LO = 0x00        # | A[3:0]
    _CMD_COL_HI = 0x10        # | A[7:4]
    _CMD_PUMP = 0x30          # | A[1:0]
    _CMD_START_LINE = 0x40    # | A[5:0]
    _CMD_CONTRAST = 0x81
    _CMD_SEG_REMAP = 0xA0     # | ADC
    _CMD_ALL_ON = 0xA4        # | 1
    _CMD_INVERT = 0xA6        # | 1
    _CMD_MULTIPLEX = 0xA8
    _CMD_DCDC = 0xAD
    _CMD_DISPLAY = 0xAE       # | 1
    _CMD_PAGE = 0xB0          # | A[2:0]
    _CMD_SCAN = 0xC0          # | (scan << 3)
    _CMD_OFFSET = 0xD3
    _CMD_CLOCK = 0xD5
    _CMD_PRECHARGE = 0xD9
    _CMD_COM = 0xDA
    _CMD_VCOM = 0xDB

    def __init__(self, connection, width=128, height=64, column_offset=2, internal_dcdc=True):
        self._setup(_I2CBus(connection, self.I2C_CHUNK), width, height, column_offset, internal_dcdc)

    def _setup(self, bus, width, height, column_offset, internal_dcdc):
        if not 1 <= width <= self.WIDTH:
            raise ValueError('width must be 1..132')
        if not 8 <= height <= self.HEIGHT or height % 8:
            raise ValueError('height must be a multiple of 8, 8..64')
        if column_offset < 0 or column_offset + width > self.WIDTH:
            raise ValueError('column_offset + width must be at most 132')
        self._bus = bus
        self.width = width
        self.height = height
        self._offset = column_offset
        self._dcdc = internal_dcdc
        self._pages = height // 8
        self._fb = bytearray(width * self._pages)
        self._adc = 0
        self._scan = 0
        self._init_sequence()

    # ---- internals -----------------------------------------------------------------------

    @staticmethod
    def _sleep_ms(ms):
        time.sleep(ms / 1000.0)

    def _command(self, *values):
        """Send bytes as one command stream."""
        self._bus.write_commands(bytes(values))

    def _address_bytes(self, page, column):
        return bytes((self._CMD_PAGE | (page & 7), self._CMD_COL_LO | (column & 0x0F),
                      self._CMD_COL_HI | ((column >> 4) & 0x0F)))

    def _write_page(self, page, data):
        self._bus.write_commands(self._address_bytes(page, self._offset))
        self._bus.write_data(data)

    def _init_sequence(self):
        """Datasheet power-on flow: setup stream, DC-DC, RAM clear, display on."""
        self._bus.hardware_reset()
        self._sleep_ms(1)
        self._command(self._CMD_DISPLAY,
                      self._CMD_CLOCK, 0x50,
                      self._CMD_MULTIPLEX, self.height - 1,
                      self._CMD_OFFSET, 0x00,
                      self._CMD_START_LINE,
                      self._CMD_SEG_REMAP | self._adc,
                      self._CMD_SCAN | (self._scan << 3),
                      self._CMD_COM, 0x12,
                      self._CMD_CONTRAST, 0x80,
                      self._CMD_PRECHARGE, 0x22,
                      self._CMD_VCOM, 0x35,
                      self._CMD_PUMP,
                      self._CMD_ALL_ON,
                      self._CMD_INVERT)
        if self._dcdc:
            self._command(self._CMD_DCDC, 0x8B)
            self._sleep_ms(100)
        else:
            self._command(self._CMD_DCDC, 0x8A)
        blank = bytes(self.WIDTH)
        for page in range(8):
            self._bus.write_commands(self._address_bytes(page, 0))
            self._bus.write_data(blank)
        self._command(self._CMD_DISPLAY | 1)
        self._sleep_ms(150 if self._dcdc else 50)

    def _show_page(self, page):
        start = page * self.width
        self._write_page(page, self._fb[start:start + self.width])

    # ---- public API ----------------------------------------------------------------------

    def clear(self):
        """Zero the framebuffer. Call ``show`` to update the panel."""
        for i in range(len(self._fb)):
            self._fb[i] = 0

    def set_pixel(self, x, y, on=True):
        """Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.

        Args:
            x: Column, 0 .. width - 1.
            y: Row, 0 .. height - 1.
            on: True to set the pixel, False to clear it.
        """
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return
        i = (y >> 3) * self.width + x
        if on:
            self._fb[i] |= 1 << (y & 7)
        else:
            self._fb[i] &= ~(1 << (y & 7)) & 0xFF

    def show(self):
        """Write the whole framebuffer to the display RAM (page + column command, then width data bytes per page)."""
        for page in range(self._pages):
            self._show_page(page)


class SH1106Full(SH1106Minimal):
    """SH1106 OLED controller-driver — full interface (I²C).

    Extends SH1106Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip,
    hardware scroll (display start line), display offset, multiplex ratio, clock / oscillator,
    pre-charge, COM configuration, VCOM level, pump voltage, DC-DC control, status and RAM reads
    (I²C only) and raw commands.

    Args:
        connection: I2CConnection at 0x3C or 0x3D.
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8 and at most 64 (default 64).
        column_offset: First RAM column shown (default 2).
        internal_dcdc: True for the internal charge pump (default), False for an external VPP.
    """

    # ---- framebuffer ---------------------------------------------------------------------

    def get_pixel(self, x, y):
        """Return a framebuffer pixel; out-of-range coordinates read as False.

        Args:
            x: Column.
            y: Row.

        Returns:
            bool: True if the pixel is set.
        """
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return False
        return bool(self._fb[(y >> 3) * self.width + x] & (1 << (y & 7)))

    def fill(self, on=True):
        """Set (or clear) the whole framebuffer.

        Args:
            on: True to set every pixel.
        """
        value = 0xFF if on else 0
        for i in range(len(self._fb)):
            self._fb[i] = value

    def draw_line(self, x0, y0, x1, y1, on=True):
        """Draw a Bresenham line into the framebuffer.

        Args:
            x0: Start column.
            y0: Start row.
            x1: End column.
            y1: End row.
            on: True to set pixels, False to clear them.
        """
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            self.set_pixel(x0, y0, on)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def draw_rect(self, x, y, w, h, on=True, fill=False):
        """Draw a rectangle outline or a filled rectangle into the framebuffer.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.
            on: True to set pixels, False to clear them.
            fill: True for a filled rectangle.
        """
        if w <= 0 or h <= 0:
            return
        if fill:
            for yy in range(y, y + h):
                for xx in range(x, x + w):
                    self.set_pixel(xx, yy, on)
            return
        self.draw_line(x, y, x + w - 1, y, on)
        self.draw_line(x, y + h - 1, x + w - 1, y + h - 1, on)
        self.draw_line(x, y, x, y + h - 1, on)
        self.draw_line(x + w - 1, y, x + w - 1, y + h - 1, on)

    def blit(self, x, y, columns, height_pages=1):
        """Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer.

        ``columns`` holds ``height_pages`` groups of columns, page by page: byte
        ``p * n + c`` is column ``c`` of page row ``p`` (n = len(columns) / height_pages).
        The bitmap is clipped to the framebuffer; ``y`` must be a multiple of 8.

        Args:
            x: Left column of the bitmap.
            y: Top row, a multiple of 8.
            columns: Bitmap bytes.
            height_pages: Number of 8-pixel-high rows of the bitmap.

        Raises:
            ValueError: If y is not a multiple of 8.
        """
        if y % 8:
            raise ValueError('y must be a multiple of 8')
        n = len(columns) // height_pages
        for p in range(height_pages):
            page = y // 8 + p
            if page < 0 or page >= self._pages:
                continue
            for c in range(n):
                xx = x + c
                if 0 <= xx < self.width:
                    self._fb[page * self.width + xx] = columns[p * n + c]

    def show_page(self, page):
        """Write one framebuffer page to the display RAM (cheaper partial update).

        Args:
            page: Page 0 .. height / 8 - 1.
        """
        if 0 <= page < self._pages:
            self._show_page(page)

    # ---- display control -----------------------------------------------------------------

    def set_contrast(self, value):
        """Set the segment output current (contrast).

        Args:
            value: Contrast, 0..255 (reset 0x80).
        """
        self._command(self._CMD_CONTRAST, value & 0xFF)

    def set_display(self, on):
        """Switch the display on or off (RAM and settings are kept).

        Args:
            on: True for display on, False for off.
        """
        self._command(self._CMD_DISPLAY | (1 if on else 0))

    def sleep(self):
        """Display off; the DC-DC stops, RAM and settings are kept and stay writable."""
        self.set_display(False)

    def wake(self):
        """Display on, then wait 100 ms for VPP when the internal DC-DC is used."""
        self.set_display(True)
        self._sleep_ms(100 if self._dcdc else 50)

    def set_invert(self, on):
        """Invert the displayed image without changing RAM.

        Args:
            on: True to invert.
        """
        self._command(self._CMD_INVERT | (1 if on else 0))

    def set_all_on(self, on):
        """Force all pixels on (test mode, RAM unchanged).

        Args:
            on: True to light every pixel.
        """
        self._command(self._CMD_ALL_ON | (1 if on else 0))

    def set_flip(self, horizontal, vertical):
        """Mirror columns (segment re-map) and/or rows (COM scan direction).

        The framebuffer is unchanged; both take effect on the panel immediately.

        Args:
            horizontal: True to reverse the segment mapping (ADC = 1).
            vertical: True to reverse the COM scan direction (0xC8).
        """
        self._adc = 1 if horizontal else 0
        self._scan = 1 if vertical else 0
        self._command(self._CMD_SEG_REMAP | self._adc, self._CMD_SCAN | (self._scan << 3))

    def set_start_line(self, line):
        """Set the RAM line shown at COM0 (hardware vertical scroll).

        Args:
            line: Start line, 0..63.
        """
        self._command(self._CMD_START_LINE | (line & 0x3F))

    def set_display_offset(self, offset):
        """Shift the COM mapping.

        Args:
            offset: Offset, 0..63.
        """
        self._command(self._CMD_OFFSET, offset & 0x3F)

    def set_multiplex(self, ratio):
        """Set the multiplex ratio.

        Args:
            ratio: 1..64.
        """
        self._command(self._CMD_MULTIPLEX, (ratio - 1) & 0x3F)

    def set_clock(self, divide, osc):
        """Set the clock divide ratio and oscillator frequency.

        Args:
            divide: Divide ratio, 1..16.
            osc: Oscillator setting, 0..15 (5 = nominal, 5 % steps).
        """
        self._command(self._CMD_CLOCK, ((osc & 0x0F) << 4) | ((divide - 1) & 0x0F))

    def set_precharge(self, precharge, discharge):
        """Set the pre-charge and discharge periods.

        Args:
            precharge: DCLKs, 1..15.
            discharge: DCLKs, 1..15.
        """
        self._command(self._CMD_PRECHARGE, ((discharge & 0x0F) << 4) | (precharge & 0x0F))

    def set_com_config(self, alternative):
        """Select the COM pin hardware configuration.

        Args:
            alternative: True for alternative (0x12, reset), False for sequential (0x02).
        """
        self._command(self._CMD_COM, 0x12 if alternative else 0x02)

    def set_vcom_deselect(self, value):
        """Set the VCOM deselect level.

        Args:
            value: 0..255 (reset 0x35).
        """
        self._command(self._CMD_VCOM, value & 0xFF)

    def set_pump_voltage(self, level):
        """Set the charge pump output voltage VPP.

        Args:
            level: 0 = 7.4 V (reset), 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V.
        """
        self._command(self._CMD_PUMP | (level & 3))

    def set_dcdc(self, on):
        """Switch the internal DC-DC on or off (call with the display off).

        Args:
            on: True for the internal DC-DC (0x8B), False for an external VPP (0x8A).
        """
        self._dcdc = bool(on)
        self._command(self._CMD_DCDC, 0x8B if on else 0x8A)

    # ---- reads and raw access ------------------------------------------------------------

    def read_status(self):
        """Read the status byte (I²C only).

        Returns:
            int: Bit 7 BUSY, bit 6 = 1 when the display is off.

        Raises:
            NotImplementedError: On the SPI interface (write-only).
        """
        if not self._bus.can_read:
            raise NotImplementedError('the SPI interface is write-only')
        return self._bus.read_status()

    def read_page(self, page, column, n):
        """Read ``n`` bytes of display RAM (I²C only); the dummy byte after an address change is discarded.

        Args:
            page: RAM page 0..7.
            column: Start RAM column.
            n: Number of bytes.

        Returns:
            bytes: The RAM bytes.

        Raises:
            NotImplementedError: On the SPI interface (write-only).
        """
        if not self._bus.can_read:
            raise NotImplementedError('the SPI interface is write-only')
        self._bus.write_commands(self._address_bytes(page, column))
        return self._bus.read_ram(n)

    def send_command(self, *values):
        """Send raw bytes as one command stream.

        Args:
            *values: Command and argument bytes.
        """
        self._command(*values)


class SH1106SpiMinimal(SH1106Minimal):
    """SH1106 over 4-wire SPI — minimal interface.

    Same framebuffer, drawing and initialization as ``SH1106Minimal``, but driven through a
    ``DisplayConnection`` (CS, D/C = A0, RES optional, no BUSY). The chip must be strapped for
    4-wire SPI (IM0 = IM1 = IM2 = 0). SPI mode 0, up to 2 MHz (4 MHz at VDD1 >= 2.4 V). Every
    command byte, including arguments, is sent with D/C low; RAM data with D/C high. SPI cannot
    read: ``read_status`` / ``read_page`` of the full class raise NotImplementedError.

    Args:
        connection: DisplayConnection (4-wire, D/C required, RES optional, no BUSY).
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8 and at most 64 (default 64).
        column_offset: First RAM column shown (default 2).
        internal_dcdc: True for the internal charge pump (default).

    Raises:
        ValueError: If width, height or column_offset is out of range.
    """

    def __init__(self, connection, width=128, height=64, column_offset=2, internal_dcdc=True):
        self._setup(_SpiBus(connection), width, height, column_offset, internal_dcdc)


class SH1106SpiFull(SH1106Full):
    """SH1106 over 4-wire SPI — full interface (see ``SH1106Full``; the reads raise NotImplementedError).

    Args:
        connection: DisplayConnection (4-wire, D/C required, RES optional, no BUSY).
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8 and at most 64 (default 64).
        column_offset: First RAM column shown (default 2).
        internal_dcdc: True for the internal charge pump (default).
    """

    def __init__(self, connection, width=128, height=64, column_offset=2, internal_dcdc=True):
        self._setup(_SpiBus(connection), width, height, column_offset, internal_dcdc)
