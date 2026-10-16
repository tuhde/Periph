class SSD1331Minimal:
    """SSD1331 96 x 64 RGB OLED controller — minimal interface.

    Drives the Waveshare 0.95inch RGB OLED over 4-wire SPI. The picture is kept
    in a 96 x 64 RGB565 framebuffer (12 288 bytes, high byte first) and sent to
    the display RAM by show().

    The connection is a display connection (DisplayConnection, see
    specs/transport_display.md) with ``busy=None`` (the chip has no BUSY pin).
    The SSD1331 takes command parameters with D/C# low, so the driver sends
    every byte of a command as its own send_command(byte); only pixel data uses
    send_data().

    Class attributes:
        WIDTH, HEIGHT: Panel size, 96 x 64.
        BLACK, WHITE, RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA: RGB565 colours.

    Args:
        connection: A DisplayConnection (SIMPLEX wiring, no BUSY pin).
    """

    WIDTH = 96
    HEIGHT = 64
    BUFFER_SIZE = WIDTH * HEIGHT * 2

    BLACK = 0x0000
    WHITE = 0xFFFF
    RED = 0xF800
    GREEN = 0x07E0
    BLUE = 0x001F
    YELLOW = 0xFFE0
    CYAN = 0x07FF
    MAGENTA = 0xF81F

    _CMD_COLUMN = 0x15
    _CMD_ROW = 0x75
    _CMD_CONTRAST_A = 0x81
    _CMD_CONTRAST_B = 0x82
    _CMD_CONTRAST_C = 0x83
    _CMD_MASTER_CURRENT = 0x87
    _CMD_PRECHARGE_A = 0x8A
    _CMD_PRECHARGE_B = 0x8B
    _CMD_PRECHARGE_C = 0x8C
    _CMD_REMAP = 0xA0
    _CMD_START_LINE = 0xA1
    _CMD_OFFSET = 0xA2
    _CMD_MODE_NORMAL = 0xA4
    _CMD_MUX = 0xA8
    _CMD_DIM_SETTING = 0xAB
    _CMD_MASTER_CONFIG = 0xAD
    _CMD_DISPLAY_DIM = 0xAC
    _CMD_DISPLAY_OFF = 0xAE
    _CMD_DISPLAY_ON = 0xAF
    _CMD_POWER_SAVE = 0xB0
    _CMD_PHASE = 0xB1
    _CMD_CLOCK = 0xB3
    _CMD_GRAY_LINEAR = 0xB9
    _CMD_PRECHARGE_LEVEL = 0xBB
    _CMD_VCOMH = 0xBE
    _CMD_LINE = 0x21
    _CMD_RECT = 0x22
    _CMD_COPY = 0x23
    _CMD_DIM = 0x24
    _CMD_CLEAR = 0x25
    _CMD_FILL = 0x26
    _CMD_SCROLL_SETUP = 0x27
    _CMD_SCROLL_OFF = 0x2E
    _CMD_SCROLL_ON = 0x2F

    @staticmethod
    def rgb(r, g, b):
        """Pack 8-bit R, G, B into an RGB565 value.

        Args:
            r: Red 0..255.
            g: Green 0..255.
            b: Blue 0..255.

        Returns:
            int: RGB565.
        """
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)

    def __init__(self, connection):
        self._connection = connection
        self._fb = bytearray(self.BUFFER_SIZE)
        self._remap = 0x72
        self._init_sequence()

    @property
    def width(self):
        """int: Panel width in pixels (96)."""
        return self.WIDTH

    @property
    def height(self):
        """int: Panel height in pixels (64)."""
        return self.HEIGHT

    def clear(self, color=BLACK):
        """Fill the framebuffer with one colour. The display is unchanged until show().

        Args:
            color: RGB565 colour (default black).
        """
        hi = (color >> 8) & 0xFF
        lo = color & 0xFF
        if hi == lo:
            self._fb = bytearray((hi,)) * self.BUFFER_SIZE
        else:
            self._fb = bytearray((hi, lo)) * (self.WIDTH * self.HEIGHT)

    def set_pixel(self, x, y, color=WHITE):
        """Set one framebuffer pixel. Out-of-range coordinates are ignored.

        Args:
            x: Column 0..95.
            y: Row 0..63.
            color: RGB565 colour (default white).
        """
        if x < 0 or y < 0 or x >= self.WIDTH or y >= self.HEIGHT:
            return
        i = (y * self.WIDTH + x) * 2
        self._fb[i] = (color >> 8) & 0xFF
        self._fb[i + 1] = color & 0xFF

    def show(self):
        """Send the whole framebuffer to the display RAM.

        Sets the column window 0..95 and the row window 0..63 and writes
        12 288 bytes in 192-byte (one row) transfers.
        """
        self._window(0, 0, self.WIDTH - 1, self.HEIGHT - 1)
        view = memoryview(self._fb)
        step = 2 * self.WIDTH
        for off in range(0, self.BUFFER_SIZE, step):
            self._connection.send_data(view[off:off + step])

    def display_on(self):
        """Switch the display on (0xAF); the RAM content is shown."""
        self._cmd(self._CMD_DISPLAY_ON)

    def display_off(self):
        """Switch the display off (0xAE); the RAM content is kept."""
        self._cmd(self._CMD_DISPLAY_OFF)

    # -- internals ---------------------------------------------------------

    def _cmd(self, *values):
        # One command byte or parameter each, all with D/C# low.
        for v in values:
            self._connection.send_command(v & 0xFF)

    def _window(self, x0, y0, x1, y1):
        self._cmd(self._CMD_COLUMN, x0, x1, self._CMD_ROW, y0, y1)

    def _init_sequence(self):
        self._connection.reset()
        self._connection.delay_ms(100)
        self._cmd(self._CMD_DISPLAY_OFF)
        self._cmd(self._CMD_CONTRAST_A, 0xFF, self._CMD_CONTRAST_B, 0xFF, self._CMD_CONTRAST_C, 0xFF)
        self._cmd(self._CMD_MASTER_CURRENT, 0x06)
        self._cmd(self._CMD_PRECHARGE_A, 0x64, self._CMD_PRECHARGE_B, 0x78, self._CMD_PRECHARGE_C, 0x64)
        self._cmd(self._CMD_REMAP, self._remap)
        self._cmd(self._CMD_START_LINE, 0x00, self._CMD_OFFSET, 0x00)
        self._cmd(self._CMD_MODE_NORMAL)
        self._cmd(self._CMD_MUX, 0x3F, self._CMD_MASTER_CONFIG, 0x8E)
        self._cmd(self._CMD_POWER_SAVE, 0x0B, self._CMD_PHASE, 0x31, self._CMD_CLOCK, 0xF0)
        self._cmd(self._CMD_PRECHARGE_LEVEL, 0x3A, self._CMD_VCOMH, 0x3E)
        self._cmd(self._CMD_SCROLL_OFF)
        self._cmd(self._CMD_CLEAR, 0x00, 0x00, 0x5F, 0x3F)
        self._connection.delay_ms(100)
        self._cmd(self._CMD_DISPLAY_ON)


class SSD1331Full(SSD1331Minimal):
    """SSD1331 96 x 64 RGB OLED controller — full interface.

    Extends SSD1331Minimal with software drawing into the framebuffer, partial
    transfers, the controller's graphic acceleration commands (line, rectangle
    with fill, copy, dim and clear window, scrolling — these act on the display
    RAM only, not on the host framebuffer), and the electrical settings
    (contrast, master current, flip, display mode, power save, dim mode, gray
    table).

    Class attributes:
        MODE_NORMAL, MODE_ALL_ON, MODE_ALL_OFF, MODE_INVERSE: display modes.

    Args:
        connection: A DisplayConnection (SIMPLEX wiring, no BUSY pin).
    """

    MODE_NORMAL = 0xA4
    MODE_ALL_ON = 0xA5
    MODE_ALL_OFF = 0xA6
    MODE_INVERSE = 0xA7

    # -- framebuffer -------------------------------------------------------

    def get_pixel(self, x, y):
        """Return the RGB565 colour of one framebuffer pixel (0 when out of range).

        Args:
            x: Column.
            y: Row.

        Returns:
            int: RGB565.
        """
        if x < 0 or y < 0 or x >= self.WIDTH or y >= self.HEIGHT:
            return 0
        i = (y * self.WIDTH + x) * 2
        return (self._fb[i] << 8) | self._fb[i + 1]

    def draw_line(self, x0, y0, x1, y1, color=SSD1331Minimal.WHITE):
        """Bresenham line into the framebuffer.

        Args:
            x0: Start column.
            y0: Start row.
            x1: End column.
            y1: End row.
            color: RGB565 colour (default white).
        """
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            self.set_pixel(x0, y0, color)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def draw_rect(self, x, y, w, h, color=SSD1331Minimal.WHITE, fill=False):
        """Rectangle outline or filled rectangle into the framebuffer.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.
            color: RGB565 colour (default white).
            fill: True fills the rectangle.
        """
        if w <= 0 or h <= 0:
            return
        for j in range(h):
            for i in range(w):
                if fill or i == 0 or j == 0 or i == w - 1 or j == h - 1:
                    self.set_pixel(x + i, y + j, color)

    def blit(self, x, y, pixels, w, h):
        """Copy an RGB565 bitmap into the framebuffer.

        Args:
            x: Left column.
            y: Top row.
            pixels: Row-major sequence of w * h RGB565 values.
            w: Bitmap width in pixels.
            h: Bitmap height in pixels.
        """
        for j in range(h):
            for i in range(w):
                self.set_pixel(x + i, y + j, pixels[j * w + i])

    def show_region(self, x, y, w, h):
        """Send only a rectangle of the framebuffer (one window, row by row).

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels. The region is clipped to the panel.
        """
        if x < 0:
            w += x
            x = 0
        if y < 0:
            h += y
            y = 0
        w = min(w, self.WIDTH - x)
        h = min(h, self.HEIGHT - y)
        if w <= 0 or h <= 0:
            return
        self._window(x, y, x + w - 1, y + h - 1)
        view = memoryview(self._fb)
        for j in range(h):
            start = ((y + j) * self.WIDTH + x) * 2
            self._connection.send_data(view[start:start + w * 2])

    # -- graphic acceleration (display RAM only) ----------------------------

    @staticmethod
    def _color(c):
        # RGB565 -> three 5/6/5-bit operands (C = red, B = green, A = blue).
        return (((c >> 11) & 0x1F) << 1, (c >> 5) & 0x3F, (c & 0x1F) << 1)

    def hw_line(self, x0, y0, x1, y1, color):
        """Hardware line (0x21) on the display RAM.

        Args:
            x0: Start column 0..95.
            y0: Start row 0..63.
            x1: End column.
            y1: End row.
            color: RGB565 colour.
        """
        self._cmd(self._CMD_LINE, x0, y0, x1, y1, *self._color(color))

    def hw_rect(self, x, y, w, h, line_color, fill_color=0, fill=False):
        """Hardware rectangle (0x26 fill enable + 0x22) on the display RAM.

        Args:
            x: Left column.
            y: Top row.
            w: Width (>= 1).
            h: Height (>= 1).
            line_color: Outline RGB565.
            fill_color: Fill RGB565 (used when fill is True).
            fill: Fill the interior.
        """
        if w <= 0 or h <= 0:
            return
        self._cmd(self._CMD_FILL, 1 if fill else 0)
        self._cmd(self._CMD_RECT, x, y, x + w - 1, y + h - 1,
                  *(self._color(line_color) + self._color(fill_color)))

    def hw_clear(self, x0, y0, x1, y1):
        """Clear a window of the display RAM (0x25)."""
        self._cmd(self._CMD_CLEAR, x0, y0, x1, y1)

    def hw_dim(self, x0, y0, x1, y1):
        """Dim a window of the display RAM (0x24)."""
        self._cmd(self._CMD_DIM, x0, y0, x1, y1)

    def hw_copy(self, x0, y0, x1, y1, x, y):
        """Copy a window of the display RAM to (x, y) (0x23)."""
        self._cmd(self._CMD_COPY, x0, y0, x1, y1, x, y)

    def scroll(self, horizontal, start_row, rows, vertical, interval=0):
        """Configure and start continuous scrolling (0x27, 0x2F).

        Args:
            horizontal: Columns per step 0..95 (0 = none).
            start_row: First scrolled row.
            rows: Number of scrolled rows.
            vertical: Rows per step 0..63 (0 = none).
            interval: 0 = 6, 1 = 10, 2 = 100, 3 = 200 frames per step.
        """
        self._cmd(self._CMD_SCROLL_SETUP, horizontal, start_row, rows, vertical, interval & 3,
                  self._CMD_SCROLL_ON)

    def scroll_stop(self):
        """Stop scrolling (0x2E). The display RAM must be rewritten afterwards."""
        self._cmd(self._CMD_SCROLL_OFF)

    # -- settings ----------------------------------------------------------

    def set_contrast(self, a, b, c):
        """Contrast of colour channels A, B, C (0x81..0x83), 0..255."""
        self._cmd(self._CMD_CONTRAST_A, a, self._CMD_CONTRAST_B, b, self._CMD_CONTRAST_C, c)

    def set_master_current(self, n):
        """Master current (0x87), n/16 for n = 1..16 (clamped)."""
        self._cmd(self._CMD_MASTER_CURRENT, min(16, max(1, n)) - 1)

    def set_display_mode(self, mode):
        """Display mode: MODE_NORMAL, MODE_ALL_ON, MODE_ALL_OFF or MODE_INVERSE."""
        self._cmd(mode)

    def set_remap(self, flags):
        """Raw remap and colour depth byte (0xA0)."""
        self._remap = flags & 0xFF
        self._cmd(self._CMD_REMAP, self._remap)

    def set_flip(self, horizontal, vertical):
        """Mirror horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4)."""
        r = self._remap & ~0x12
        if horizontal:
            r |= 0x02
        if vertical:
            r |= 0x10
        self.set_remap(r)

    def set_power_save(self, on):
        """Power save mode (0xB0): True = 0x1A enable, False = 0x0B disable."""
        self._cmd(self._CMD_POWER_SAVE, 0x1A if on else 0x0B)

    def dim_mode(self, a, b, c, precharge):
        """Dim mode (0xAB + 0xAC): contrasts A/B/C and pre-charge voltage 0..31."""
        self._cmd(self._CMD_DIM_SETTING, 0x00, a, b, c, precharge & 0x1F, self._CMD_DISPLAY_DIM)

    def gray_linear(self):
        """Restore the linear gray scale table (0xB9)."""
        self._cmd(self._CMD_GRAY_LINEAR)

    def send_command(self, command, params=b''):
        """Send any command with parameters (all with D/C# low).

        Args:
            command: Command byte.
            params: Parameter bytes.
        """
        self._cmd(command, *params)
