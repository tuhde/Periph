_DEFAULT = object()

_FRAME_BYTES = 600 * 448 // 2


class UC8159Minimal:
    """UC8159 seven-colour e-paper controller — minimal interface.

    Drives a 600 x 448 ACeP seven-colour electrophoretic panel such as the
    Waveshare 5.65inch e-Paper (F). The driver keeps the picture in a
    framebuffer in wire format (4 bits per pixel, two pixels per byte, 134 400
    bytes) and sends it on show(). Every update is DTM1, PON, wait BUSY, DRF,
    wait BUSY, POF, wait BUSY; show() blocks for the whole refresh (about
    30 s), which stalls the interpreter on MicroPython and CircuitPython.

    The connection is a display connection (DisplayConnection, see
    specs/transport_display.md) that owns CS#, D/C#, RES# and BUSY. BUSY is
    active low.

    Class attributes:
        BLACK, WHITE, GREEN, BLUE, RED, YELLOW, ORANGE, CLEAN: Colour codes 0..7.
        WIDTH, HEIGHT: 600, 448.

    Args:
        connection: A DisplayConnection (SIMPLEX wiring is enough).
        busy_timeout_ms: Limit for every BUSY wait in milliseconds, or None
            for no limit (a refresh takes about 30 s).

    Raises:
        TimeoutError: If BUSY does not go high during initialisation.
    """

    BLACK = 0
    WHITE = 1
    GREEN = 2
    BLUE = 3
    RED = 4
    YELLOW = 5
    ORANGE = 6
    CLEAN = 7

    WIDTH = 600
    HEIGHT = 448

    _CMD_PSR = 0x00
    _CMD_PWR = 0x01
    _CMD_POF = 0x02
    _CMD_PFS = 0x03
    _CMD_PON = 0x04
    _CMD_BTST = 0x06
    _CMD_DSLP = 0x07
    _CMD_DTM1 = 0x10
    _CMD_DRF = 0x12
    _CMD_PLL = 0x30
    _CMD_TSC = 0x40
    _CMD_TSE = 0x41
    _CMD_CDI = 0x50
    _CMD_LPD = 0x51
    _CMD_TCON = 0x60
    _CMD_TRES = 0x61
    _CMD_PWS = 0xE3
    _CMD_FLG = 0x71
    _CMD_VV = 0x81
    _CMD_VDCS = 0x82

    def __init__(self, connection, busy_timeout_ms=60000):
        self._connection = connection
        self._timeout = busy_timeout_ms
        self._fb = bytearray(b'\x11') * _FRAME_BYTES
        self._psr = 0xEF
        self._cdi = 0x37
        self._asleep = False
        self._init_sequence()

    @property
    def width(self):
        """int: Panel width in pixels (600)."""
        return self.WIDTH

    @property
    def height(self):
        """int: Panel height in pixels (448)."""
        return self.HEIGHT

    def clear(self, color=WHITE):
        """Fill the framebuffer with one colour. The panel is unchanged until show().

        Args:
            color: Colour code 0..7 (WHITE default).
        """
        c = color & 7
        self._fb = bytearray(bytes(((c << 4) | c,))) * _FRAME_BYTES

    def set_pixel(self, x, y, color=BLACK):
        """Set one pixel in the framebuffer. Out-of-range coordinates are ignored.

        Args:
            x: Column, 0 <= x < 600.
            y: Row, 0 <= y < 448.
            color: Colour code 0..7 (BLACK default).
        """
        if x < 0 or y < 0 or x >= self.WIDTH or y >= self.HEIGHT:
            return
        i = (y * self.WIDTH + x) >> 1
        c = color & 7
        if x & 1:
            self._fb[i] = (self._fb[i] & 0xF0) | c
        else:
            self._fb[i] = (self._fb[i] & 0x0F) | (c << 4)

    def show(self):
        """Send the frame and run a full display update (about 30 s).

        DTM1 + 134 400 bytes, PON, wait BUSY, DRF, wait BUSY, POF, wait BUSY.
        A sleeping controller is woken (hardware reset + initialisation) first.

        Raises:
            TimeoutError: If a BUSY wait exceeds busy_timeout_ms.
        """
        if self._asleep:
            self._wake()
        self._connection.send_command(self._CMD_DTM1)
        view = memoryview(self._fb)
        for off in range(0, _FRAME_BYTES, 4096):
            self._connection.send_data(bytes(view[off:off + 4096]))
        self._connection.send_command(self._CMD_PON)
        self._wait_ready()
        self._connection.send_command(self._CMD_DRF)
        self._wait_ready()
        self._connection.send_command(self._CMD_POF)
        self._wait_ready()

    def sleep(self):
        """Put the controller into deep sleep (DSLP with check code 0xA5).

        Only a hardware reset restores communication; show() and Full.wake()
        do that. The panel keeps its image.
        """
        self._connection.send_command(self._CMD_DSLP, b'\xa5')
        self._asleep = True

    # -- internals ---------------------------------------------------------

    def _wait_ready(self, timeout_ms=_DEFAULT):
        if timeout_ms is _DEFAULT:
            timeout_ms = self._timeout
        if not self._connection.wait_busy(False, timeout_ms):
            raise TimeoutError('UC8159: BUSY did not go high within %s ms' % timeout_ms)

    def _init_sequence(self):
        # The vendor demo's initialisation commands (sigrok/tests/uc8159).
        c = self._connection
        c.reset()
        self._wait_ready()
        c.send_command(self._CMD_PSR, bytes((self._psr, 0x08)))
        c.send_command(self._CMD_PWR, b'\x37\x00\x23\x23')
        c.send_command(self._CMD_PFS, b'\x00')
        c.send_command(self._CMD_BTST, b'\xc7\xc7\x1d')
        c.send_command(self._CMD_PLL, b'\x3c')
        c.send_command(self._CMD_TSE, b'\x00')
        c.send_command(self._CMD_CDI, bytes((self._cdi,)))
        c.send_command(self._CMD_TCON, b'\x22')
        c.send_command(self._CMD_TRES, b'\x02\x58\x01\xc0')   # 600 x 448
        c.send_command(self._CMD_PWS, b'\xaa')

    def _wake(self):
        self._asleep = False
        self._init_sequence()


class UC8159Full(UC8159Minimal):
    """UC8159 seven-colour e-paper controller — full interface.

    Extends UC8159Minimal with drawing helpers, scan direction, frame rate,
    border/data interval, temperature-sensor source, VCOM_DC and the read
    commands (status, temperature, low power, VCOM). The read_* methods need a
    half-duplex wire mode; with a SIMPLEX connection they raise RuntimeError.

    Args:
        connection: A DisplayConnection.
        busy_timeout_ms: Limit for every BUSY wait in milliseconds, or None.
    """

    # -- framebuffer -------------------------------------------------------

    def get_pixel(self, x, y):
        """Return the colour code (0..7) of one framebuffer pixel.

        Args:
            x: Column.
            y: Row.

        Returns:
            int: Colour code (BLACK when out of range).
        """
        if x < 0 or y < 0 or x >= self.WIDTH or y >= self.HEIGHT:
            return self.BLACK
        b = self._fb[(y * self.WIDTH + x) >> 1]
        return (b & 7) if x & 1 else ((b >> 4) & 7)

    def draw_line(self, x0, y0, x1, y1, color=UC8159Minimal.BLACK):
        """Bresenham line into the framebuffer.

        Args:
            x0: Start column.
            y0: Start row.
            x1: End column.
            y1: End row.
            color: Colour code 0..7.
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

    def draw_rect(self, x, y, w, h, color=UC8159Minimal.BLACK, fill=False):
        """Rectangle outline or filled rectangle into the framebuffer.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.
            color: Colour code 0..7.
            fill: True fills the rectangle.
        """
        if w <= 0 or h <= 0:
            return
        if fill:
            for j in range(h):
                for i in range(w):
                    self.set_pixel(x + i, y + j, color)
            return
        self.draw_line(x, y, x + w - 1, y, color)
        self.draw_line(x, y + h - 1, x + w - 1, y + h - 1, color)
        self.draw_line(x, y, x, y + h - 1, color)
        self.draw_line(x + w - 1, y, x + w - 1, y + h - 1, color)

    def blit(self, x, y, data, w, h, color=UC8159Minimal.BLACK):
        """Copy a 1-bit-per-pixel bitmap into the framebuffer.

        Row-major, MSB-first, rows padded to whole bytes. Set bits are drawn in
        color, clear bits leave the framebuffer unchanged.

        Args:
            x: Left column.
            y: Top row.
            data: Bitmap bytes.
            w: Bitmap width in pixels.
            h: Bitmap height in pixels.
            color: Colour code 0..7.
        """
        if w <= 0 or h <= 0:
            return
        row_bytes = (w + 7) // 8
        for j in range(h):
            for i in range(w):
                if data[j * row_bytes + (i >> 3)] & (0x80 >> (i & 7)):
                    self.set_pixel(x + i, y + j, color)

    # -- control -----------------------------------------------------------

    def wake(self):
        """Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept."""
        self._wake()

    def wait_busy(self, timeout_ms=_DEFAULT):
        """Wait for BUSY to go high.

        Args:
            timeout_ms: Limit in milliseconds, None for no limit; the
                constructor's busy_timeout_ms when omitted.

        Returns:
            bool: True when ready, False on timeout.
        """
        if timeout_ms is _DEFAULT:
            timeout_ms = self._timeout
        return self._connection.wait_busy(False, timeout_ms)

    def set_flip(self, horizontal, vertical):
        """Mirror the picture: horizontal clears SHL, vertical clears UD.

        The framebuffer is unchanged.

        Args:
            horizontal: Mirror left/right.
            vertical: Mirror top/bottom.
        """
        if horizontal:
            self._psr &= ~0x04 & 0xFF
        else:
            self._psr |= 0x04
        if vertical:
            self._psr &= ~0x08 & 0xFF
        else:
            self._psr |= 0x08
        self._connection.send_command(self._CMD_PSR, bytes((self._psr, 0x08)))

    def set_frame_rate(self, code):
        """Frame rate (PLL raw code).

        Args:
            code: 0x3C = 50 Hz (default), 0x3A = 100 Hz, 0x39 = 200 Hz,
                0x00..0x0F = 12.5 .. 200 Hz.
        """
        self._connection.send_command(self._CMD_PLL, bytes((code & 0xFF,)))

    def set_interval(self, cdi, border=UC8159Minimal.WHITE, polarity=1):
        """VCOM and data interval (CDI).

        Args:
            cdi: Interval, 0..15 (0b0111 = 10 hsync).
            border: Border colour code 0..7 (default WHITE).
            polarity: DDX, 1 = default (the colour codes assume 1).
        """
        self._cdi = ((border & 7) << 5) | ((polarity & 1) << 4) | (cdi & 0x0F)
        self._connection.send_command(self._CMD_CDI, bytes((self._cdi,)))

    def set_temperature_sensor(self, external, offset=0):
        """Temperature sensor source (TSE): internal (default) or external LM75.

        Args:
            external: True selects the external sensor.
            offset: Temperature offset, -8..+7 degrees Celsius.
        """
        value = (0x80 if external else 0x00) | (offset & 0x0F)
        self._connection.send_command(self._CMD_TSE, bytes((value,)))

    def set_vcom_dc(self, volts):
        """VCOM_DC (VDCS): -0.10 .. -4.0 V in 0.05 V steps.

        Args:
            volts: VCOM voltage.
        """
        code = int((-0.10 - volts) / 0.05 + 2.5)
        code = max(2, min(0x50, code))
        self._connection.send_command(self._CMD_VDCS, bytes((code,)))

    def send_command(self, command, data=b''):
        """Send a raw command and parameters (escape hatch).

        Args:
            command: Command byte.
            data: Parameter bytes.
        """
        self._connection.send_command(command, data)

    # -- reads (half-duplex wiring only) -----------------------------------

    def read_status(self):
        """Read FLG: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.

        Returns:
            int: The status byte.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        return self._read(self._CMD_FLG, 1)[0]

    def read_temperature(self):
        """Read the internal temperature sensor (TSC).

        Returns:
            int: Signed degrees Celsius.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        value = self._read(self._CMD_TSC, 2)[0]
        return value - 256 if value > 127 else value

    def read_low_power(self):
        """Read low-power detection (LPD).

        Returns:
            bool: True when VDD is below 2.5 V.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        return (self._read(self._CMD_LPD, 1)[0] & 1) == 0

    def read_vcom(self):
        """Read the measured VCOM (VV).

        Returns:
            float: Volts, -0.10 V - 0.05 V x (VV - 2).

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        c = self._read(self._CMD_VV, 1)[0] & 0x7F
        return -0.10 - 0.05 * (c - 2 if c > 2 else 0)

    # -- internals ---------------------------------------------------------

    def _read(self, cmd, length):
        self._connection.send_command(cmd)
        return self._connection.read_data(length)
