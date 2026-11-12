import time

from periph.chips.display.mono_oled import I2cOledBus, MonoFramebuffer, SpiOledBus


class SSD1306Minimal:
    """SSD1306 128 x 64 OLED controller-driver — minimal interface (I²C).

    Drives a monochrome SSD1306 OLED panel (0.96" 128 x 64 or 128 x 32 modules) over I²C at
    ``0x3C`` (SA0 = low) or ``0x3D`` (SA0 = high). The chip has an exact 128-column RAM, so there
    is no column offset.

    All drawing goes into a framebuffer of ``width * height / 8`` bytes (page-major, bit 0 =
    top pixel of the page); ``show`` sends it page by page (page addressing, the reset default).
    The initialization follows the datasheet and the charge-pump application note: hardware
    reset (SPI), display off, setup stream (clock, multiplex, offset, start line, charge pump,
    page addressing, mapping, COM configuration, contrast, pre-charge, VCOMH), RAM cleared,
    display on + 100 ms. With ``external_vcc=False`` (default) the internal charge pump makes
    the OLED supply VCC from VBAT.

    Class attributes:
        WIDTH, HEIGHT: Maximum size, 128 x 64.
        I2C_CHUNK: Largest write in bytes including the control byte (32-byte limit of some I²C layers).

    Args:
        connection: I2CConnection at 0x3C or 0x3D (or, for the SPI classes, a DisplayConnection).
        width: Visible columns, 1..128 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64). Heights up to 32 use sequential COM wiring.
        external_vcc: True when the board supplies VCC (charge pump stays off), False for the internal pump.

    Raises:
        ValueError: If width or height is out of range.
    """

    WIDTH = 128
    HEIGHT = 64
    I2C_CHUNK = 32

    _CMD_ADDR_MODE = 0x20
    _CMD_COL_RANGE = 0x21
    _CMD_PAGE_RANGE = 0x22
    _CMD_START_LINE = 0x40    # | A[5:0]
    _CMD_CONTRAST = 0x81
    _CMD_CHARGE_PUMP = 0x8D
    _CMD_SEG_REMAP = 0xA0     # | ADC
    _CMD_SCROLL_AREA = 0xA3
    _CMD_ALL_ON = 0xA4        # | 1
    _CMD_INVERT = 0xA6        # | 1
    _CMD_MULTIPLEX = 0xA8
    _CMD_DISPLAY = 0xAE       # | 1
    _CMD_PAGE = 0xB0          # | A[2:0]
    _CMD_SCAN = 0xC0          # | (scan << 3)
    _CMD_OFFSET = 0xD3
    _CMD_CLOCK = 0xD5
    _CMD_PRECHARGE = 0xD9
    _CMD_COM = 0xDA
    _CMD_VCOMH = 0xDB

    def __init__(self, connection, width=128, height=64, external_vcc=False):
        self._setup(I2cOledBus(connection, self.I2C_CHUNK), width, height, external_vcc)

    def _setup(self, bus, width, height, external_vcc):
        if not 1 <= width <= self.WIDTH:
            raise ValueError('width must be 1..128')
        if not 16 <= height <= self.HEIGHT or height % 8:
            raise ValueError('height must be a multiple of 8, 16..64')
        self._bus = bus
        self._canvas = MonoFramebuffer(width, height)
        self.width = width
        self.height = height
        self._pages = height // 8
        self._external_vcc = external_vcc
        self._adc = 0
        self._scan = 0
        self._mode = 'page'
        self._scrolling = False
        self._init_sequence()

    # ---- internals -----------------------------------------------------------------------

    @staticmethod
    def _sleep_ms(ms):
        time.sleep(ms / 1000.0)

    def _command(self, *values):
        """Send bytes as one command stream."""
        self._bus.write_commands(bytes(values))

    def _address_bytes(self, page):
        return bytes((self._CMD_PAGE | (page & 7), 0x00, 0x10))

    def _init_sequence(self):
        """Datasheet power-on flow: reset, setup stream, RAM clear, display on."""
        self._bus.hardware_reset()
        self._sleep_ms(1)
        self._command(self._CMD_DISPLAY,
                      self._CMD_CLOCK, 0x80,
                      self._CMD_MULTIPLEX, self.height - 1,
                      self._CMD_OFFSET, 0x00,
                      self._CMD_START_LINE,
                      self._CMD_CHARGE_PUMP, 0x10 if self._external_vcc else 0x14,
                      self._CMD_ADDR_MODE, 0x02,
                      self._CMD_SEG_REMAP | self._adc,
                      self._CMD_SCAN | (self._scan << 3),
                      self._CMD_COM, 0x12 if self.height > 32 else 0x02,
                      self._CMD_CONTRAST, 0x7F,
                      self._CMD_PRECHARGE, 0x22,
                      self._CMD_VCOMH, 0x20,
                      self._CMD_ALL_ON,
                      self._CMD_INVERT)
        blank = bytes(self.WIDTH)
        for page in range(8):
            self._bus.write_commands(self._address_bytes(page))
            self._bus.write_data(blank)
        self._command(self._CMD_DISPLAY | 1)
        self._sleep_ms(100)

    def _check_ram_access(self):
        if self._scrolling:
            raise RuntimeError('RAM access while a scroll is active; call scroll_stop() first')

    def _show_page(self, page):
        self._bus.write_commands(self._address_bytes(page))
        self._bus.write_data(self._canvas.page(page))

    def _show_all(self):
        self._check_ram_access()
        if self._mode == 'page':
            for page in range(self._pages):
                self._show_page(page)
            return
        self._command(self._CMD_COL_RANGE, 0, self.width - 1, self._CMD_PAGE_RANGE, 0, self._pages - 1)
        if self._mode == 'horizontal':
            self._bus.write_data(self._canvas.data)
        else:
            self._bus.write_data(self._canvas.transposed())

    # ---- public API ----------------------------------------------------------------------

    def clear(self):
        """Zero the framebuffer. Call ``show`` to update the panel."""
        self._canvas.clear()

    def set_pixel(self, x, y, on=True):
        """Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.

        Args:
            x: Column, 0 .. width - 1.
            y: Row, 0 .. height - 1.
            on: True to set the pixel, False to clear it.
        """
        self._canvas.set_pixel(x, y, on)

    def show(self):
        """Write the whole framebuffer to the display RAM.

        Page addressing (default): per page one page + column command stream, then ``width``
        data bytes. Horizontal / vertical addressing: column and page range commands, then one
        data stream.

        Raises:
            RuntimeError: While a hardware scroll is active (call ``scroll_stop`` first).
        """
        self._show_all()


class SSD1306Full(SSD1306Minimal):
    """SSD1306 OLED controller-driver — full interface (I²C).

    Extends SSD1306Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip,
    start line, display offset, multiplex ratio, clock / oscillator, pre-charge, COM
    configuration, VCOMH level, charge pump, addressing mode, hardware scrolling and raw
    commands. The SSD1306 cannot be read over its serial interfaces.

    Args:
        connection: I2CConnection at 0x3C or 0x3D.
        width: Visible columns, 1..128 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64).
        external_vcc: True when the board supplies VCC (charge pump off).
    """

    # Scroll interval table index -> step interval in frames: 5, 64, 128, 256, 3, 4, 25, 2.

    # ---- framebuffer ---------------------------------------------------------------------

    def get_pixel(self, x, y):
        """Return a framebuffer pixel; out-of-range coordinates read as False.

        Args:
            x: Column.
            y: Row.

        Returns:
            bool: True if the pixel is set.
        """
        return self._canvas.get_pixel(x, y)

    def fill(self, on=True):
        """Set (or clear) the whole framebuffer.

        Args:
            on: True to set every pixel.
        """
        self._canvas.fill(on)

    def draw_line(self, x0, y0, x1, y1, on=True):
        """Draw a Bresenham line into the framebuffer.

        Args:
            x0: Start column.
            y0: Start row.
            x1: End column.
            y1: End row.
            on: True to set pixels, False to clear them.
        """
        self._canvas.draw_line(x0, y0, x1, y1, on)

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
        self._canvas.draw_rect(x, y, w, h, on, fill)

    def blit(self, x, y, columns, height_pages=1):
        """Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer.

        Args:
            x: Left column of the bitmap.
            y: Top row, a multiple of 8.
            columns: Bitmap bytes, ``height_pages`` groups of columns page by page.
            height_pages: Number of 8-pixel-high rows of the bitmap.

        Raises:
            ValueError: If y is not a multiple of 8.
        """
        self._canvas.blit(x, y, columns, height_pages)

    def show_page(self, page):
        """Write one framebuffer page to the display RAM (cheaper partial update).

        Args:
            page: Page 0 .. height / 8 - 1.

        Raises:
            RuntimeError: While a hardware scroll is active (call ``scroll_stop`` first).
        """
        self._check_ram_access()
        if not 0 <= page < self._pages:
            return
        if self._mode == 'page':
            self._show_page(page)
            return
        self._command(self._CMD_COL_RANGE, 0, self.width - 1, self._CMD_PAGE_RANGE, page, page)
        self._bus.write_data(self._canvas.page(page))

    # ---- display control -----------------------------------------------------------------

    def set_contrast(self, value):
        """Set the segment output current (contrast).

        Args:
            value: Contrast, 0..255 (reset 0x7F).
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

        The framebuffer is unchanged; RAM written before a segment re-map is not re-mapped, so
        call ``show`` again.

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
            osc: Oscillator setting, 0..15 (8 = reset value; higher is faster).
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
        """Set the VCOMH deselect level (only bits 6:4 are used).

        Args:
            value: 0x00 (about 0.65 x VCC) .. 0x70; reset 0x20 (about 0.77 x VCC).
        """
        self._command(self._CMD_VCOMH, value & 0x70)

    def set_charge_pump(self, enabled):
        """Enable or disable the internal charge pump (takes effect with the display on).

        Send before ``set_display(True)`` / ``wake``.

        Args:
            enabled: True for the internal pump (0x14), False for external VCC (0x10).
        """
        self._command(self._CMD_CHARGE_PUMP, 0x14 if enabled else 0x10)

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

    def set_scroll_horizontal(self, right, start_page, end_page, interval):
        """Set up a continuous horizontal scroll, one column per step (start it with ``scroll_start``).

        Do not call while scrolling is active; call ``scroll_stop`` first.

        Args:
            right: True to scroll right, False for left.
            start_page: First scrolled page, 0..7.
            end_page: Last scrolled page, start_page..7.
            interval: Step interval table index 0..7 = 5, 64, 128, 256, 3, 4, 25, 2 frames.
        """
        self._command(0x26 if right else 0x27, 0x00, start_page & 7, interval & 7, end_page & 7, 0x00, 0xFF)

    def set_scroll_vertical(self, right, start_page, end_page, interval, rows):
        """Set up a continuous vertical + horizontal (diagonal) scroll (start it with ``scroll_start``).

        Args:
            right: True to scroll right, False for left.
            start_page: First scrolled page, 0..7.
            end_page: Last scrolled page, start_page..7.
            interval: Step interval table index 0..7 (see ``set_scroll_horizontal``).
            rows: Vertical rows per step, 1..63.
        """
        self._command(0x29 if right else 0x2A, 0x00, start_page & 7, interval & 7, end_page & 7, rows & 0x3F)

    def set_scroll_area(self, top_fixed, scroll_rows):
        """Set the vertical scroll area.

        Args:
            top_fixed: Rows in the fixed top area, 0..63.
            scroll_rows: Rows in the scrolling area, 1..64 (top_fixed + scroll_rows <= multiplex ratio).
        """
        self._command(self._CMD_SCROLL_AREA, top_fixed & 0x3F, scroll_rows & 0x7F)

    def scroll_start(self):
        """Activate the scroll configured by the last scroll setup command (0x2F).

        While it is active, ``show`` and ``show_page`` raise ``RuntimeError``.
        """
        self._command(0x2F)
        self._scrolling = True

    def scroll_stop(self):
        """Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires."""
        self._command(0x2E)
        self._scrolling = False
        self._show_all()

    def send_command(self, *values):
        """Send raw bytes as one command stream.

        Args:
            *values: Command and argument bytes.
        """
        self._command(*values)


class SSD1306SpiMinimal(SSD1306Minimal):
    """SSD1306 over 4-wire SPI — minimal interface.

    Same framebuffer, drawing and initialization as ``SSD1306Minimal``, but driven through a
    ``DisplayConnection`` (CS, D/C, RES optional, no BUSY). The chip must be strapped for 4-wire
    SPI (BS0 = BS1 = BS2 = 0). SPI mode 0, up to 10 MHz (4 MHz recommended). Every command byte,
    including arguments, is sent with D/C low; RAM data with D/C high.

    Args:
        connection: DisplayConnection (4-wire, D/C required, RES optional, no BUSY).
        width: Visible columns, 1..128 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64).
        external_vcc: True when the board supplies VCC (charge pump off).

    Raises:
        ValueError: If width or height is out of range.
    """

    def __init__(self, connection, width=128, height=64, external_vcc=False):
        self._setup(SpiOledBus(connection), width, height, external_vcc)


class SSD1306SpiFull(SSD1306Full):
    """SSD1306 over 4-wire SPI — full interface (see ``SSD1306Full``).

    Args:
        connection: DisplayConnection (4-wire, D/C required, RES optional, no BUSY).
        width: Visible columns, 1..128 (default 128).
        height: Rows, a multiple of 8, 16..64 (default 64).
        external_vcc: True when the board supplies VCC (charge pump off).
    """

    def __init__(self, connection, width=128, height=64, external_vcc=False):
        self._setup(SpiOledBus(connection), width, height, external_vcc)
