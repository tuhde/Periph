import time


class _I2CBus:
    """I²C transport: commands on the command address, RAM data on the data address (chunked)."""

    can_read = True

    def __init__(self, command_connection, data_connection, chunk):
        self._cmd = command_connection
        self._data = data_connection
        self._chunk = chunk

    def write_command(self, data):
        self._cmd.write(data)

    def write_data(self, data):
        view = memoryview(data)
        for i in range(0, len(view), self._chunk):
            self._data.write(bytes(view[i:i + self._chunk]))

    def read_status(self, nop):
        self._cmd.write(bytes((nop,)))
        return bytes(self._cmd.read(3))

    def read_ram(self, n):
        return bytes(self._data.read(n + 1))[1:]

    def hardware_reset(self):
        pass


class _SpiBus:
    """4-wire SPI transport on a DisplayConnection: D/C low for every command byte, high for RAM data."""

    can_read = False
    SPI_CHUNK = 240

    def __init__(self, connection):
        self._conn = connection

    def write_command(self, data):
        for b in data:
            self._conn.send_command(b)

    def write_data(self, data):
        view = memoryview(data)
        for i in range(0, len(view), self.SPI_CHUNK):
            self._conn.send_data(bytes(view[i:i + self.SPI_CHUNK]))

    def hardware_reset(self):
        self._conn.reset()


class UC1611sMinimal:
    """UC1611s graphic LCD controller-driver — minimal interface.

    Drives an EA DOGXL240-7 (240 x 128 dots, 3.3 V) in 1 bit per pixel black/white
    mode over I²C. The chip carries the command/data bit in its I²C address, so it
    answers on two consecutive 7-bit addresses: ``0x38 + 2 n`` for commands and
    ``0x39 + 2 n`` for display RAM data (n = A3 << 1 | A2, the two address pins). The
    driver therefore takes two connections on the same bus.

    All drawing goes into a framebuffer of ``width * height / 8`` bytes (page-major,
    bit 0 = top pixel of the page, 3840 bytes at 240 x 128); ``show`` sends it to the
    display RAM. The initialization follows the DOG module's bottom-view example:
    System Reset, temperature compensation -0.10 %/degC, COM end 127, potentiometer
    (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.

    Error handling: the chip occasionally NACKs a transfer (seen on long cables and for about 6 ms
    while the charge pump restarts), which the bus layer reports as OSError. Every transfer is
    retried up to ``MAX_ATTEMPTS`` times, ``RETRY_DELAY_MS`` apart: a command stream is repeated and
    a RAM page is rewritten from its address command (a partial write cannot be continued). Repeated
    attempts are counted (``retry_count``); a transfer that still fails raises its OSError and is
    counted in ``error_count``.

    Class attributes:
        WIDTH, HEIGHT: Display size of the DOGXL240-7, 240 x 128.
        I2C_CHUNK: Largest data write in bytes (the 32-byte limit of some I²C layers).
        MAX_ATTEMPTS, RETRY_DELAY_MS: Attempts per transfer and the wait between them.

    Args:
        command_connection: I2CConnection at the command address (0x38, 0x3A, 0x3C, 0x3E).
        data_connection: I2CConnection at the data address (command address + 1).
        width: Columns, 1..240 (default 240).
        height: Rows, a multiple of 8 and at most 128 (default 128).

    Raises:
        ValueError: If width or height is out of range.
        OSError: If the chip does not acknowledge the System Reset of the initialization.
    """

    WIDTH = 240
    HEIGHT = 128
    I2C_CHUNK = 32
    MAX_ATTEMPTS = 8
    RETRY_DELAY_MS = 2

    _PAGES_RAM = 20           # RAM pages cleared at init (160 rows / 8)
    _CMD_TEMP = 0x24          # | TC[1:0]
    _CMD_PANEL = 0x28         # | PC[1:0]
    _CMD_PUMP = 0x2C          # | PC[3:2]
    _CMD_SCROLL_LO = 0x40     # | SL[3:0]
    _CMD_SCROLL_HI = 0x50     # | SL[7:4]
    _CMD_PAGE_LO = 0x60       # | PA[3:0]
    _CMD_PAGE_HI = 0x70       # | PA[6:4]
    _CMD_POTENTIOMETER = 0x81
    _CMD_ISOLATION = 0x82
    _CMD_PARTIAL = 0x84       # | LC[9:8]
    _CMD_RAM_ADDR = 0x88      # | AC[2:0]
    _CMD_FIXED = 0x90         # | FL[3:0]
    _CMD_LINE_RATE = 0xA0     # | LC[5:4]
    _CMD_ALL_ON = 0xA4        # | DC1
    _CMD_INVERSE = 0xA6       # | DC0
    _CMD_DISPLAY = 0xA8       # | DC[4:2]
    _CMD_MAPPING = 0xC0
    _CMD_NLINE = 0xC8
    _CMD_PATTERN = 0xD0       # | DC[7:5]
    _CMD_RESET = 0xE2
    _CMD_NOP = 0xE3
    _CMD_BIAS = 0xE8          # | BR[1:0]
    _CMD_COM_END = 0xF1
    _CMD_PART_START = 0xF2
    _CMD_PART_END = 0xF3

    _top_view = False
    _contrast = 0x8F

    def __init__(self, command_connection, data_connection, width=240, height=128):
        self._setup(_I2CBus(command_connection, data_connection, self.I2C_CHUNK), width, height)

    def _setup(self, bus, width, height):
        if not 1 <= width <= self.WIDTH:
            raise ValueError('width must be 1..240')
        if not 8 <= height <= self.HEIGHT or height % 8:
            raise ValueError('height must be a multiple of 8, 8..128')
        self._bus = bus
        self.width = width
        self.height = height
        self._pages = height // 8
        self._fb = bytearray(width * self._pages)
        self._mx = 0 if self._top_view else 1
        self._my = 1 if self._top_view else 0
        self._pattern = 0
        self._retries = 0
        self._errors = 0
        self._init_sequence()

    # ---- internals -----------------------------------------------------------------------

    @staticmethod
    def _sleep_ms(ms):
        time.sleep(ms / 1000.0)

    def _command(self, *values):
        """Send bytes as one command transaction on the command address, retried on OSError."""
        data = bytes(values)
        for attempt in range(self.MAX_ATTEMPTS):
            try:
                self._bus.write_command(data)
                return
            except OSError as e:
                err = e
                self._retries += 1
                self._sleep_ms(self.RETRY_DELAY_MS)
        self._errors += 1
        raise err

    def _write_chunks(self, data):
        """One pass over the data in transport-sized chunks (raises OSError on a NACK)."""
        self._bus.write_data(data)

    def _write_data(self, data):
        """Send RAM data without retry (the write position of a partial write is unknown)."""
        try:
            self._write_chunks(data)
        except OSError:
            self._errors += 1
            raise

    def _address_bytes(self, page, column):
        pa = (self._pattern << 5) | (page & 0x1F)
        return bytes((self._CMD_PAGE_LO | (pa & 0x0F), self._CMD_PAGE_HI | ((pa >> 4) & 0x07),
                      column & 0x0F, 0x10 | (column >> 4)))

    def _set_address(self, page, column=0):
        """Set the RAM page (with the write pattern in PA[6:5]) and the column pointer."""
        self._command(*self._address_bytes(page, column))

    def _write_page(self, page, data):
        """Write one page: address command, then the data; a failure repeats both."""
        for attempt in range(self.MAX_ATTEMPTS):
            try:
                self._bus.write_command(self._address_bytes(page, 0))
                self._write_chunks(data)
                return
            except OSError as e:
                err = e
                self._retries += 1
                self._sleep_ms(self.RETRY_DELAY_MS)
        self._errors += 1
        raise err

    def _read_status(self):
        """Read Status1..3. A NOP write first restarts the chip's status cycle (it advances by n + 1 per read)."""
        if not self._bus.can_read:
            raise NotImplementedError('the SPI interface of the module is write-only')
        for attempt in range(self.MAX_ATTEMPTS):
            try:
                return self._bus.read_status(self._CMD_NOP)
            except OSError as e:
                err = e
                self._retries += 1
                self._sleep_ms(self.RETRY_DELAY_MS)
        self._errors += 1
        raise err

    def _wait_display_enabled(self, timeout_ms):
        """Poll Status1.DE until the pump is up (SPI cannot read it: wait a fixed 10 ms instead).

        NACKs while the pump starts are expected and not counted.
        """
        if not self._bus.can_read:
            self._sleep_ms(min(timeout_ms, 10))
            return
        waited = 0
        while waited <= timeout_ms:
            try:
                if self._bus.read_status(self._CMD_NOP)[0] & 0x08:
                    return
            except OSError:
                pass
            self._sleep_ms(self.RETRY_DELAY_MS)
            waited += self.RETRY_DELAY_MS

    def _mapping(self):
        return (self._my << 2) | (self._mx << 1)

    def _reset_chip(self):
        """System Reset in its own transaction, then the conservative 150 ms wait."""
        self._command(self._CMD_RESET)
        self._sleep_ms(150)

    def _configure(self):
        """Configuration stream of the DOG bottom-view example (display stays off)."""
        last = self.height - 1
        self._command(self._CMD_TEMP | 1,
                      self._CMD_MAPPING, self._mapping(),
                      self._CMD_LINE_RATE | 3,
                      self._CMD_COM_END, last,
                      self._CMD_PART_START, 0,
                      self._CMD_PART_END, last,
                      self._CMD_POTENTIOMETER, self._contrast,
                      self._CMD_RAM_ADDR | 1,
                      self._CMD_PATTERN | 1)

    def _enable(self):
        """Display enable in On/Off mode; the chip waits for VLCD itself."""
        self._command(self._CMD_DISPLAY | 1)

    def _init_sequence(self):
        """Power-up wait, System Reset, configuration, RAM clear, display on."""
        self._sleep_ms(150)
        self._bus.hardware_reset()
        self._reset_chip()
        self._configure()
        blank = bytes(self.width)
        for page in range(self._PAGES_RAM):
            self._write_page(page, blank)
        self._enable()
        self._wait_display_enabled(200)

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
        """Write the whole framebuffer to the display RAM (page + column, then width data bytes per page)."""
        for page in range(self._pages):
            self._show_page(page)

    def ok(self):
        """Return True if no transfer has failed after all retries since construction or ``clear_errors``."""
        return self._errors == 0

    def retry_count(self):
        """Return the number of transfer attempts that had to be repeated."""
        return self._retries

    def error_count(self):
        """Return the number of transfers that still failed after ``MAX_ATTEMPTS`` attempts."""
        return self._errors

    def clear_errors(self):
        """Reset the retry and error counters."""
        self._retries = 0
        self._errors = 0


class UC1611sFull(UC1611sMinimal):
    """UC1611s graphic LCD controller-driver — full interface.

    Extends UC1611sMinimal with drawing primitives, contrast, sleep, invert, flip /
    top view, hardware scroll with fixed lines, bias ratio, temperature compensation,
    line rate, charge pump / external VLCD, panel loading, partial display, N-line
    inversion, isolation clock, the four RAM patterns, status and RAM reads, and raw
    commands.

    Args:
        command_connection: I2CConnection at the command address (0x38, 0x3A, 0x3C, 0x3E).
        data_connection: I2CConnection at the data address (command address + 1).
        width: Columns, 1..240 (default 240).
        height: Rows, a multiple of 8 and at most 128 (default 128).
        top_view: True for the 12:00 viewing direction (module rotated 180 degrees: MY = 1, MX = 0).
        contrast: Potentiometer PM 0..255 (default 0x8F, the DOG example).
    """

    def __init__(self, command_connection, data_connection, width=240, height=128, top_view=False, contrast=0x8F):
        self._check_options(top_view, contrast)
        super().__init__(command_connection, data_connection, width, height)

    def _check_options(self, top_view, contrast):
        if not 0 <= contrast <= 255:
            raise ValueError('contrast must be 0..255')
        self._top_view = top_view
        self._contrast = contrast

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
        """Set the potentiometer PM that adjusts VLCD (contrast).

        Args:
            value: PM, 0..255.
        """
        self._contrast = value & 0xFF
        self._command(self._CMD_POTENTIOMETER, self._contrast)

    def set_display(self, on):
        """Switch the display on, or into sleep (registers and RAM kept, below 1 uA).

        Args:
            on: True for display on (On/Off mode), False for sleep.
        """
        self._command(self._CMD_DISPLAY | (1 if on else 0))

    def sleep(self):
        """Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active."""
        self.set_display(False)

    def wake(self):
        """Leave sleep mode; the chip restores VLCD itself.

        The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump restarts, so the
        driver polls DE (at most 50 ms) instead of sleeping.
        """
        self.set_display(True)
        self._wait_display_enabled(50)

    def set_invert(self, on):
        """Invert the displayed image without changing RAM.

        Args:
            on: True to invert.
        """
        self._command(self._CMD_INVERSE | (1 if on else 0))

    def set_all_on(self, on):
        """Force all pixels on (test mode, RAM unchanged).

        Args:
            on: True to light every pixel.
        """
        self._command(self._CMD_ALL_ON | (1 if on else 0))

    def set_flip(self, mirror_x, mirror_y):
        """Mirror columns and/or rows.

        MY acts at once; MX is applied to RAM writes, so call ``show`` afterwards to
        re-send the framebuffer.

        Args:
            mirror_x: True to mirror the columns (SEG order; the bottom-view default).
            mirror_y: True to mirror the rows (COM order).
        """
        self._mx = 1 if mirror_x else 0
        self._my = 1 if mirror_y else 0
        self._command(self._CMD_MAPPING, self._mapping())

    def set_scroll_line(self, line):
        """Scroll the image up by ``line`` rows (hardware scroll, wraps at 160 rows).

        Args:
            line: SL, 0 .. 159 - 2 * fixed lines.
        """
        self._command(self._CMD_SCROLL_LO | (line & 0x0F), self._CMD_SCROLL_HI | ((line >> 4) & 0x0F))

    def set_fixed_lines(self, lines):
        """Keep the first ``2 * lines`` rows out of the scroll (soft-icon area).

        Args:
            lines: FL, 0..15.
        """
        self._command(self._CMD_FIXED | (lines & 0x0F))

    def set_bias_ratio(self, ratio):
        """Select the LCD bias ratio.

        Args:
            ratio: 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12.
        """
        self._command(self._CMD_BIAS | (ratio & 3))

    def set_temperature_compensation(self, curve):
        """Select the VBIAS temperature compensation coefficient.

        Args:
            curve: 0 = -0.05 %/degC, 1 = -0.10, 2 = -0.15, 3 = 0.00.
        """
        self._command(self._CMD_TEMP | (curve & 3))

    def set_line_rate(self, rate):
        """Select the line rate (On/Off mode).

        Args:
            rate: 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second.
        """
        self._command(self._CMD_LINE_RATE | (rate & 3))

    def set_pump(self, internal):
        """Choose the internal 11x charge pump or an external VLCD supply.

        Args:
            internal: True for the internal charge pump (reset), False for external VLCD.
        """
        self._command(self._CMD_PUMP | (3 if internal else 0))

    def set_panel_loading(self, high):
        """Tell the chip the panel capacitance.

        Args:
            high: True for 33..55 nF (reset), False for up to 33 nF.
        """
        self._command(self._CMD_PANEL | (3 if high else 0))

    def set_partial_display(self, on, start=0, end=None):
        """Scan only rows ``start`` .. ``end`` (the rest sleeps), or return to full scan.

        Args:
            on: True to enable partial display.
            start: First scanned row (DST).
            end: Last scanned row (DEN); default height - 1.
        """
        if on:
            self._command(self._CMD_PART_START, start & 0xFF,
                          self._CMD_PART_END, (self.height - 1 if end is None else end) & 0xFF,
                          self._CMD_PARTIAL | 3)
        else:
            self._command(self._CMD_PARTIAL)

    def set_nline_inversion(self, lines, xor=False):
        """Set N-line inversion (cross-talk tuning).

        Args:
            lines: Number of lines, 0 (off) .. 63.
            xor: True for XOR mode.
        """
        self._command(self._CMD_NLINE, (0x40 if xor else 0) | (lines & 0x3F))

    def set_isolation_clock(self, front, back):
        """Set the isolation clock before and after the COM pulse.

        Args:
            front: ISOF, 0..15 (reset 1).
            back: ISOB, 0..15 (reset 0); use a higher VLCD when raising it.
        """
        self._command(self._CMD_ISOLATION, 0x13, front & 0x0F)
        self._command(self._CMD_ISOLATION, 0x14, back & 0x0F)

    def set_display_pattern(self, pattern):
        """Select which of the four RAM screens (patterns) is displayed.

        Args:
            pattern: 0..3.
        """
        self._command(self._CMD_PATTERN | ((pattern & 3) << 1) | 1)

    def set_write_pattern(self, pattern):
        """Select the RAM screen that ``show`` / ``show_page`` write to (PA[6:5]).

        Args:
            pattern: 0..3.
        """
        self._pattern = pattern & 3

    # ---- reads and raw access ------------------------------------------------------------

    def read_status(self):
        """Read the three status bytes (a NOP is sent first to restart the chip's status cycle).

        Returns:
            bytes: Status1 (Ver, MX, MY, WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]),
            Status3 (product code 1 in bits 7..4, ESD flag in bit 0).

        Raises:
            OSError: If the read fails after all attempts.
        """
        return self._read_status()

    def read_ram(self, page, column, n):
        """Read ``n`` bytes of display RAM (the dummy byte after an address change is discarded).

        Args:
            page: RAM page.
            column: Start column.
            n: Number of bytes.

        Returns:
            bytes: The RAM bytes.

        Raises:
            OSError: If address or read fail after all attempts.
        """
        if not self._bus.can_read:
            raise NotImplementedError('the SPI interface of the module is write-only')
        for attempt in range(self.MAX_ATTEMPTS):
            try:
                self._bus.write_command(self._address_bytes(page, column))
                return self._bus.read_ram(n)
            except OSError as e:
                err = e
                self._retries += 1
                self._sleep_ms(self.RETRY_DELAY_MS)
        self._errors += 1
        raise err

    def reset(self):
        """System Reset and reconfigure with the cached contrast and mapping (RAM is kept)."""
        self._reset_chip()
        self._configure()
        self._enable()
        self._wait_display_enabled(50)

    def send_command(self, *values):
        """Send raw bytes as one command transaction.

        Args:
            *values: Command and argument bytes.
        """
        self._command(*values)

    def send_data(self, data):
        """Send raw bytes to the display RAM (chunked) at the current RAM address.

        Args:
            data: Bytes to write.
        """
        self._write_data(data)


class UC1611sSpiMinimal(UC1611sMinimal):
    """UC1611s over 4-wire SPI — minimal interface.

    Same framebuffer, drawing and initialization as ``UC1611sMinimal``, but the module is driven through a
    ``DisplayConnection`` (CS#, D/C# = CD, RES# = RST, optional). The module must be strapped for 4-wire
    SPI (BM0 = D13 = VSS). SPI mode 0, up to 8 MHz. Every command byte, including the arguments of
    two- and three-byte commands, is sent with D/C# low as its own CS window; RAM data is sent with D/C# high.
    There is no read-back and no acknowledge: nothing is retried (``retry_count`` stays 0), the driver waits a
    fixed 10 ms for the charge pump after reset or wake instead of polling, and ``read_status`` /
    ``read_ram`` of the full class raise NotImplementedError.

    Args:
        connection: DisplayConnection (4-wire, D/C# required, RES# optional, no BUSY).
        width: Columns, 1..240 (default 240).
        height: Rows, a multiple of 8 and at most 128 (default 128).

    Raises:
        ValueError: If width or height is out of range.
    """

    def __init__(self, connection, width=240, height=128):
        self._setup(_SpiBus(connection), width, height)


class UC1611sSpiFull(UC1611sFull):
    """UC1611s over 4-wire SPI — full interface (see ``UC1611sFull``; the reads raise NotImplementedError).

    Args:
        connection: DisplayConnection (4-wire, D/C# required, RES# optional, no BUSY).
        width: Columns, 1..240 (default 240).
        height: Rows, a multiple of 8 and at most 128 (default 128).
        top_view: True for the 12:00 viewing direction (MY = 1, MX = 0).
        contrast: Potentiometer PM 0..255 (default 0x8F, the DOG example).
    """

    def __init__(self, connection, width=240, height=128, top_view=False, contrast=0x8F):
        self._check_options(top_view, contrast)
        self._setup(_SpiBus(connection), width, height)
