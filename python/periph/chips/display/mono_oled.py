"""Shared building blocks of the monochrome page-organised OLED controller drivers.

``MonoFramebuffer`` holds a page-major 1 bpp framebuffer (one byte = 8 vertical pixels of one
column, bit 0 = top) with the drawing primitives; ``I2cOledBus`` and ``SpiOledBus`` are the two
transports (control-byte framed I²C, D/C-pin framed 4-wire SPI) that the SSD130x style command
streams are sent through.
"""


class MonoFramebuffer:
    """Page-major monochrome framebuffer with drawing primitives.

    Args:
        width: Columns.
        height: Rows, a multiple of 8.

    Attributes:
        width: Columns.
        height: Rows.
        pages: ``height // 8``.
        data: The ``bytearray`` holding ``width * pages`` bytes (page 0 first).
    """

    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.pages = height // 8
        self.data = bytearray(width * self.pages)

    def clear(self):
        """Zero the framebuffer."""
        self.fill(False)

    def fill(self, on=True):
        """Set (or clear) every pixel.

        Args:
            on: True to set every pixel.
        """
        value = 0xFF if on else 0
        for i in range(len(self.data)):
            self.data[i] = value

    def set_pixel(self, x, y, on=True):
        """Set or clear one pixel; out-of-range coordinates are ignored.

        Args:
            x: Column, 0 .. width - 1.
            y: Row, 0 .. height - 1.
            on: True to set the pixel, False to clear it.
        """
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return
        i = (y >> 3) * self.width + x
        if on:
            self.data[i] |= 1 << (y & 7)
        else:
            self.data[i] &= ~(1 << (y & 7)) & 0xFF

    def get_pixel(self, x, y):
        """Return a pixel; out-of-range coordinates read as False.

        Args:
            x: Column.
            y: Row.

        Returns:
            bool: True if the pixel is set.
        """
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return False
        return bool(self.data[(y >> 3) * self.width + x] & (1 << (y & 7)))

    def draw_line(self, x0, y0, x1, y1, on=True):
        """Draw a Bresenham line.

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
        """Draw a rectangle outline or a filled rectangle.

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
        """Copy a column-major bitmap (8 pixels per byte, bit 0 = top).

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
            if page < 0 or page >= self.pages:
                continue
            for c in range(n):
                xx = x + c
                if 0 <= xx < self.width:
                    self.data[page * self.width + xx] = columns[p * n + c]

    def page(self, page):
        """Return a ``memoryview`` of one page.

        Args:
            page: Page 0 .. pages - 1.
        """
        start = page * self.width
        return memoryview(self.data)[start:start + self.width]

    def transposed(self):
        """Return the framebuffer column-major (for vertical addressing)."""
        out = bytearray(len(self.data))
        k = 0
        for x in range(self.width):
            for p in range(self.pages):
                out[k] = self.data[p * self.width + x]
                k += 1
        return out


class I2cOledBus:
    """I²C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data).

    Args:
        connection: I2CConnection at 0x3C or 0x3D.
        chunk: Largest write in bytes including the control byte (32 for Arduino-style I²C layers).
    """

    can_read = True

    def __init__(self, connection, chunk=32):
        self._conn = connection
        self._chunk = chunk

    def write_commands(self, data):
        """Send bytes as one command stream."""
        self._conn.write(b'\x00' + bytes(data))

    def write_data(self, data):
        """Send RAM data bytes, each chunk framed with its own 0x40 control byte."""
        view = memoryview(data)
        step = self._chunk - 1
        for i in range(0, len(view), step):
            self._conn.write(b'\x40' + bytes(view[i:i + step]))

    def read_status(self):
        """Write a command-mode control byte and read one status byte."""
        self._conn.write(b'\x00')
        return bytes(self._conn.read(1))[0]

    def read_ram(self, n):
        """Write a data-mode control byte, read ``n + 1`` bytes and discard the dummy byte."""
        self._conn.write(b'\x40')
        return bytes(self._conn.read(n + 1))[1:]

    def hardware_reset(self):
        """No reset line on the I²C interface."""


class SpiOledBus:
    """4-wire SPI transport on a DisplayConnection: D/C low for every command byte, high for RAM data.

    Args:
        connection: DisplayConnection (D/C required, RES optional, no BUSY).
    """

    can_read = False
    SPI_CHUNK = 240

    def __init__(self, connection):
        self._conn = connection

    def write_commands(self, data):
        """Send each byte with D/C low."""
        for b in data:
            self._conn.send_command(b)

    def write_data(self, data):
        """Send RAM data with D/C high in chunks of ``SPI_CHUNK`` bytes."""
        view = memoryview(data)
        for i in range(0, len(view), self.SPI_CHUNK):
            self._conn.send_data(bytes(view[i:i + self.SPI_CHUNK]))

    def hardware_reset(self):
        """Pulse RES through the display connection."""
        self._conn.reset()
