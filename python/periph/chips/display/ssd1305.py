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


class SSD1305Minimal:
    """SSD1305 132 x 64 OLED controller-driver — minimal interface (I²C).

    Drives a monochrome SSD1305 OLED panel (2.23" 128 x 32 or 1.3" 128 x 64 modules) over I²C at
    ``0x3C`` (SA0 = low) or ``0x3D`` (SA0 = high). The chip has 132 RAM columns; 128-pixel-wide
    modules usually show RAM columns 4..131, so ``column_offset`` defaults to 4.

    All drawing goes into a framebuffer of ``width * height / 8`` bytes (page-major, bit 0 =
    top pixel of the page); ``show`` sends it page by page (page addressing) to the display RAM.

    The chip has **no charge pump**: the OLED supply VCC (7..15 V) must be applied by the board
    before the display is switched on. The initialization follows the datasheet power-on flow:
    hardware reset (SPI), display off, setup stream (clock, multiplex, offset, start line,
    mapping, COM configuration, contrast, pre-charge, VCOMH, external-VCC master configuration,
    page addressing), RAM cleared, display on + 100 ms.

    Class attributes:
        WIDTH, HEIGHT: Maximum size, 132 x 64.
        I2C_CHUNK: Largest write in bytes including the control byte (32-byte limit of some I²C layers).

    Args:
        connection: I2CConnection at 0x3C or 0x3D (or, for the SPI classes, a DisplayConnection).
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64). Heights up to 32 use sequential COM wiring.
        column_offset: First RAM column shown (default 4; 0 for a true 132-column panel).

    Raises:
        ValueError: If width, height or column_offset is out of range.
    """

    WIDTH = 132
    HEIGHT = 64
    I2C_CHUNK = 32

    _CMD_COL_LO = 0x00        # | A[3:0]
    _CMD_COL_HI = 0x10        # | A[7:4]
    _CMD_ADDR_MODE = 0x20
    _CMD_COL_RANGE = 0x21
    _CMD_PAGE_RANGE = 0x22
    _CMD_START_LINE = 0x40    # | A[5:0]
    _CMD_CONTRAST = 0x81
    _CMD_BRIGHTNESS = 0x82
    _CMD_LUT = 0x91
    _CMD_BANK_1_16 = 0x92
    _CMD_BANK_17_32 = 0x93
    _CMD_SEG_REMAP = 0xA0     # | ADC
    _CMD_SCROLL_AREA = 0xA3
    _CMD_ALL_ON = 0xA4        # | 1
    _CMD_INVERT = 0xA6        # | 1
    _CMD_MULTIPLEX = 0xA8
    _CMD_DIM_MODE = 0xAB
    _CMD_DIM_ON = 0xAC
    _CMD_MASTER = 0xAD
    _CMD_DISPLAY = 0xAE       # | 1
    _CMD_PAGE = 0xB0          # | A[2:0]
    _CMD_SCAN = 0xC0          # | (scan << 3)
    _CMD_OFFSET = 0xD3
    _CMD_CLOCK = 0xD5
    _CMD_AREA_COLOR = 0xD8
    _CMD_PRECHARGE = 0xD9
    _CMD_COM = 0xDA
    _CMD_VCOMH = 0xDB

    def __init__(self, connection, width=128, height=64, column_offset=4):
        self._setup(_I2CBus(connection, self.I2C_CHUNK), width, height, column_offset)

    def _setup(self, bus, width, height, column_offset):
        if not 1 <= width <= self.WIDTH:
            raise ValueError('width must be 1..132')
        if not 16 <= height <= self.HEIGHT or height % 8:
            raise ValueError('height must be a multiple of 8, 16..64')
        if column_offset < 0 or column_offset + width > self.WIDTH:
            raise ValueError('column_offset + width must be at most 132')
        self._bus = bus
        self.width = width
        self.height = height
        self._offset = column_offset
        self._pages = height // 8
        self._fb = bytearray(width * self._pages)
        self._adc = 0
        self._scan = 0
        self._mode = 'page'
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

    def _init_sequence(self):
        """Datasheet power-on flow: reset, setup stream, RAM clear, display on."""
        self._bus.hardware_reset()
        self._sleep_ms(1)
        self._command(self._CMD_DISPLAY,
                      self._CMD_CLOCK, 0x70,
                      self._CMD_MULTIPLEX, self.height - 1,
                      self._CMD_OFFSET, 0x00,
                      self._CMD_START_LINE,
                      self._CMD_SEG_REMAP | self._adc,
                      self._CMD_SCAN | (self._scan << 3),
                      self._CMD_COM, 0x12 if self.height > 32 else 0x02,
                      self._CMD_CONTRAST, 0x80,
                      self._CMD_PRECHARGE, 0x22,
                      self._CMD_VCOMH, 0x34,
                      self._CMD_MASTER, 0x8E,
                      self._CMD_ALL_ON,
                      self._CMD_INVERT,
                      self._CMD_ADDR_MODE, 0x02)
        blank = bytes(self.WIDTH)
        for page in range(8):
            self._bus.write_commands(self._address_bytes(page, 0))
            self._bus.write_data(blank)
        self._command(self._CMD_DISPLAY | 1)
        self._sleep_ms(100)

    def _show_page(self, page):
        start = page * self.width
        self._bus.write_commands(self._address_bytes(page, self._offset))
        self._bus.write_data(self._fb[start:start + self.width])

    def _show_all(self):
        if self._mode == 'page':
            for page in range(self._pages):
                self._show_page(page)
            return
        self._command(self._CMD_COL_RANGE, self._offset, self._offset + self.width - 1,
                      self._CMD_PAGE_RANGE, 0, self._pages - 1)
        if self._mode == 'horizontal':
            self._bus.write_data(self._fb)
        else:
            out = bytearray()
            for x in range(self.width):
                for page in range(self._pages):
                    out.append(self._fb[page * self.width + x])
            self._bus.write_data(out)

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
        """Write the whole framebuffer to the display RAM.

        Page addressing (default): per page one page + column command stream, then ``width`` data
        bytes. Horizontal / vertical addressing: column and page range commands, then one data stream.
        """
        self._show_all()


class SSD1305Full(SSD1305Minimal):
    """SSD1305 OLED controller-driver — full interface (I²C).

    Extends SSD1305Minimal with drawing primitives, contrast, brightness, dim mode, sleep, invert,
    all-on test, flip, start line, display offset, multiplex ratio, clock / oscillator, pre-charge,
    COM configuration, VCOMH level, addressing mode, hardware scrolling, area-colour mode with
    LUT / bank colours, status and RAM reads (I²C only) and raw commands.

    Args:
        connection: I2CConnection at 0x3C or 0x3D.
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64).
        column_offset: First RAM column shown (default 4).
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
        if not 0 <= page < self._pages:
            return
        if self._mode == 'page':
            self._show_page(page)
            return
        start = page * self.width
        self._command(self._CMD_COL_RANGE, self._offset, self._offset + self.width - 1,
                      self._CMD_PAGE_RANGE, page, page)
        self._bus.write_data(self._fb[start:start + self.width])

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
        """Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards."""
        self.set_display(False)

    def wake(self):
        """Display on, then wait 100 ms (tAF) for SEG / COM to turn on."""
        self.set_display(True)
        self._sleep_ms(100)

    def set_dim(self, on):
        """Switch between dim mode and normal display-on.

        Configure the dim levels with ``set_dim_mode`` first.

        Args:
            on: True for display on in dim mode (0xAC), False for normal display on (0xAF).
        """
        self._command(self._CMD_DIM_ON if on else self._CMD_DISPLAY | 1)

    def set_dim_mode(self, contrast, brightness):
        """Configure the contrast and colour-bank brightness used in dim mode.

        Args:
            contrast: BANK0 contrast, 0..255.
            brightness: Colour-bank brightness, 0..255.
        """
        self._command(self._CMD_DIM_MODE, 0x00, contrast & 0xFF, brightness & 0xFF)

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
            ratio: 16..64.
        """
        self._command(self._CMD_MULTIPLEX, (ratio - 1) & 0x3F)

    def set_clock(self, divide, osc):
        """Set the clock divide ratio and oscillator frequency.

        Args:
            divide: Divide ratio, 1..16.
            osc: Oscillator setting, 0..15 (7 = reset value; higher is faster).
        """
        self._command(self._CMD_CLOCK, ((osc & 0x0F) << 4) | ((divide - 1) & 0x0F))

    def set_precharge(self, phase1, phase2):
        """Set the pre-charge phase periods.

        Args:
            phase1: DCLKs, 1..15.
            phase2: DCLKs, 1..15.
        """
        self._command(self._CMD_PRECHARGE, ((phase2 & 0x0F) << 4) | (phase1 & 0x0F))

    def set_com_config(self, alternative, remap=False):
        """Select the COM pin hardware configuration.

        Args:
            alternative: True for alternative (reset), False for sequential.
            remap: True to enable the COM left / right remap.
        """
        self._command(self._CMD_COM, 0x02 | (0x10 if alternative else 0) | (0x20 if remap else 0))

    def set_vcomh(self, value):
        """Set the VCOMH deselect level (only bits 5:2 are used).

        Args:
            value: 0x00 (about 0.43 x VCC) .. 0x3C (about 0.83 x VCC); reset 0x34.
        """
        self._command(self._CMD_VCOMH, value & 0x3C)

    def set_addressing_mode(self, mode):
        """Select how ``show`` writes the display RAM.

        Args:
            mode: ``"page"`` (reset default), ``"horizontal"`` or ``"vertical"``.

        Raises:
            ValueError: If mode is not one of the three names.
        """
        codes = {'horizontal': 0, 'vertical': 1, 'page': 2}
        if mode not in codes:
            raise ValueError('mode must be page, horizontal or vertical')
        self._mode = mode
        self._command(self._CMD_ADDR_MODE, codes[mode])

    # ---- hardware scrolling ----------------------------------------------------------------

    _INTERVALS = (0b000, 0b001, 0b010, 0b011, 0b100, 0b101, 0b110)   # 6, 32, 64, 128, 3, 4, 2 frames

    def set_scroll_horizontal(self, right, start_page, end_page, interval, columns=1):
        """Set up a continuous horizontal scroll (start it with ``scroll_start``).

        Do not call while scrolling is active; call ``scroll_stop`` first.

        Args:
            right: True to scroll right, False for left.
            start_page: First scrolled page, 0..7.
            end_page: Last scrolled page, start_page..7.
            interval: Step interval table index 0..6 = 6, 32, 64, 128, 3, 4, 2 frames.
            columns: Columns per step, 1..4.
        """
        self._command(0x26 if right else 0x27, columns & 0x07, start_page & 0x07,
                      self._INTERVALS[interval], end_page & 0x07)

    def set_scroll_vertical(self, right, start_page, end_page, interval, columns, rows):
        """Set up a continuous vertical + horizontal scroll (start it with ``scroll_start``).

        Args:
            right: True to scroll right, False for left.
            start_page: First scrolled page, 0..7.
            end_page: Last scrolled page, start_page..7.
            interval: Step interval table index 0..6 = 6, 32, 64, 128, 3, 4, 2 frames.
            columns: Horizontal columns per step, 0..4.
            rows: Vertical rows per step, 1..63.
        """
        self._command(0x29 if right else 0x2A, columns & 0x07, start_page & 0x07,
                      self._INTERVALS[interval], end_page & 0x07, rows & 0x3F)

    def set_scroll_area(self, top_fixed, scroll_rows):
        """Set the vertical scroll area.

        Args:
            top_fixed: Rows in the fixed top area, 0..63.
            scroll_rows: Rows in the scrolling area, 1..64 (top_fixed + scroll_rows <= multiplex ratio).
        """
        self._command(self._CMD_SCROLL_AREA, top_fixed & 0x3F, scroll_rows & 0x7F)

    def scroll_start(self):
        """Activate the scroll configured by the last scroll setup command (0x2F)."""
        self._command(0x2F)

    def scroll_stop(self):
        """Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires."""
        self._command(0x2E)
        self._show_all()

    # ---- area colour -------------------------------------------------------------------------

    def set_area_color(self, enable, low_power=False):
        """Enable the area-colour mode and/or the low-power display mode.

        Args:
            enable: True to enable area colour (monochrome panels keep it off).
            low_power: True for the low-power display mode.
        """
        self._command(self._CMD_AREA_COLOR, (0x30 if enable else 0) | (0x05 if low_power else 0))

    def set_brightness(self, value):
        """Set the colour-bank brightness (no visible effect in monochrome mode).

        Args:
            value: Brightness, 0..255 (reset 0x80).
        """
        self._command(self._CMD_BRIGHTNESS, value & 0xFF)

    def set_lut(self, bank0, a, b, c):
        """Set the drive pulse widths of BANK0 and colours A, B, C.

        Args:
            bank0: Pulse width code 31..63 (32..64 clocks), reset 0b110001.
            a: Colour A code, 31..63.
            b: Colour B code, 31..63.
            c: Colour C code, 31..63.
        """
        self._command(self._CMD_LUT, bank0 & 0x3F, a & 0x3F, b & 0x3F, c & 0x3F)

    def set_bank_colors(self, banks_1_16, banks_17_32=None):
        """Assign colours A..D to the area-colour banks (2 bits per bank).

        Args:
            banks_1_16: 4 bytes for banks 1..16 (PAGE0).
            banks_17_32: Optional 4 bytes for banks 17..32 (PAGE1).

        Raises:
            ValueError: If a group is not exactly 4 bytes.
        """
        if len(banks_1_16) != 4 or (banks_17_32 is not None and len(banks_17_32) != 4):
            raise ValueError('bank colour groups need 4 bytes')
        self._command(self._CMD_BANK_1_16, *banks_1_16)
        if banks_17_32 is not None:
            self._command(self._CMD_BANK_17_32, *banks_17_32)

    # ---- reads and raw access ------------------------------------------------------------

    def read_status(self):
        """Read the status byte (I²C only).

        Returns:
            int: Bit 6 = 1 when the display is off; the other bits are reserved.

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


class SSD1305SpiMinimal(SSD1305Minimal):
    """SSD1305 over 4-wire SPI — minimal interface.

    Same framebuffer, drawing and initialization as ``SSD1305Minimal``, but driven through a
    ``DisplayConnection`` (CS, D/C, RES optional, no BUSY). The chip must be strapped for
    4-wire SPI (BS0 = BS1 = BS2 = 0). SPI mode 0, up to 4 MHz (2 MHz recommended). Every
    command byte, including arguments, is sent with D/C low; RAM data with D/C high. SPI cannot
    read: ``read_status`` / ``read_page`` of the full class raise NotImplementedError.

    Args:
        connection: DisplayConnection (4-wire, D/C required, RES optional, no BUSY).
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64).
        column_offset: First RAM column shown (default 4).

    Raises:
        ValueError: If width, height or column_offset is out of range.
    """

    def __init__(self, connection, width=128, height=64, column_offset=4):
        self._setup(_SpiBus(connection), width, height, column_offset)


class SSD1305SpiFull(SSD1305Full):
    """SSD1305 over 4-wire SPI — full interface (see ``SSD1305Full``; the reads raise NotImplementedError).

    Args:
        connection: DisplayConnection (4-wire, D/C required, RES optional, no BUSY).
        width: Visible columns, 1..132 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64).
        column_offset: First RAM column shown (default 4).
    """

    def __init__(self, connection, width=128, height=64, column_offset=4):
        self._setup(_SpiBus(connection), width, height, column_offset)
