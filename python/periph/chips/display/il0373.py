# Frame rate in Hz for PLL M (1..7) and N (1..7), panel datasheet p. 24.
_PLL_HZ = (
    (29, 14, 10, 7, 6, 5, 4),
    (57, 29, 19, 14, 11, 10, 8),
    (86, 43, 29, 21, 17, 14, 12),
    (114, 57, 38, 29, 23, 19, 16),
    (150, 72, 48, 36, 29, 24, 20),
    (171, 86, 57, 43, 34, 29, 24),
    (200, 100, 67, 50, 40, 33, 29),
)

_DEFAULT = object()


class IL0373Minimal:
    """IL0373 black/white e-paper controller — minimal interface.

    Drives a 104 x 212 (up to 160 source x 296 gate) black/white
    electrophoretic panel such as the Waveshare 2.13inch e-Paper (D). The
    driver keeps the picture in a framebuffer and sends it on show(). Every
    update follows the vendor reference flow: PON (if the booster is off),
    DTM1 + DTM2, DRF and wait on BUSY, CDI border floating, POF and wait.
    show() blocks for the whole refresh, which stalls the interpreter on
    MicroPython and CircuitPython.

    The connection is a display connection (DisplayConnection for MicroPython,
    CircuitPython or Linux, see specs/transport_display.md) that owns CS#, D/C#,
    RES# and BUSY. BUSY is active low.

    Pixel polarity: bit 1 = white, bit 0 = black (CDI.DDX[0] = 1, KW mode). The
    controller drives a pixel only when its NEW and OLD bits differ, so the
    driver sends OLD = NOT NEW: every pixel is driven to its final state and a
    refresh does not depend on the previous picture.

    Class attributes:
        BLACK, WHITE: Colours.

    Args:
        connection: A DisplayConnection (SIMPLEX wiring is enough).
        width: Pixels per row, a multiple of 8, 8..160.
        height: Rows, 1..296.
        busy_timeout_ms: Limit for every BUSY wait in milliseconds, or None
            for no limit (the panel datasheet gives no refresh time).

    Raises:
        ValueError: If width or height is out of range.
        TimeoutError: If BUSY does not go high during initialisation.
    """

    BLACK = 0
    WHITE = 1

    _CMD_PSR = 0x00
    _CMD_PWR = 0x01
    _CMD_POF = 0x02
    _CMD_PON = 0x04
    _CMD_BTST = 0x06
    _CMD_DSLP = 0x07
    _CMD_DTM1 = 0x10
    _CMD_DRF = 0x12
    _CMD_DTM2 = 0x13
    _CMD_LUTC = 0x20
    _CMD_PLL = 0x30
    _CMD_TSC = 0x40
    _CMD_TSE = 0x41
    _CMD_CDI = 0x50
    _CMD_LPD = 0x51
    _CMD_TRES = 0x61
    _CMD_REV = 0x70
    _CMD_FLG = 0x71
    _CMD_VV = 0x81
    _CMD_VDCS = 0x82
    _CMD_PTL = 0x90
    _CMD_PTIN = 0x91
    _CMD_PTOUT = 0x92
    _CMD_ROTP = 0xA2

    MAX_WIDTH = 160
    MAX_HEIGHT = 296

    def __init__(self, connection, width=104, height=212, busy_timeout_ms=30000):
        if width < 8 or width > self.MAX_WIDTH or width % 8:
            raise ValueError('width must be a multiple of 8 in 8..%d' % self.MAX_WIDTH)
        if height < 1 or height > self.MAX_HEIGHT:
            raise ValueError('height must be in 1..%d' % self.MAX_HEIGHT)
        self._connection = connection
        self._width = width
        self._height = height
        self._timeout = busy_timeout_ms
        self._row_bytes = width // 8
        self._size = self._row_bytes * height
        self._fb = bytearray(b'\xff') * self._size
        self._psr = 0x1F
        self._powered = False
        self._asleep = False
        self._init_sequence()

    @property
    def width(self):
        """int: Panel width in pixels."""
        return self._width

    @property
    def height(self):
        """int: Panel height in pixels."""
        return self._height

    def clear(self, color=WHITE):
        """Fill the framebuffer with one colour. The panel is unchanged until show().

        Args:
            color: WHITE (default) or BLACK.
        """
        self._fb = bytearray(b'\xff' if color == self.WHITE else b'\x00') * self._size

    def set_pixel(self, x, y, color=BLACK):
        """Set one pixel in the framebuffer. Out-of-range coordinates are ignored.

        Args:
            x: Column, 0 <= x < width.
            y: Row, 0 <= y < height.
            color: BLACK (default) or WHITE.
        """
        if x < 0 or y < 0 or x >= self._width or y >= self._height:
            return
        index = y * self._row_bytes + (x >> 3)
        mask = 0x80 >> (x & 7)
        if color == self.WHITE:
            self._fb[index] |= mask
        else:
            self._fb[index] &= ~mask & 0xFF

    def show(self):
        """Write OLD and NEW data to the controller and run a full display update.

        PON (if the booster is off), DTM1 (OLD = NOT NEW), DTM2 (NEW), DRF,
        wait BUSY, CDI 0xF7 (border floating), POF, wait BUSY. Blocks for the
        whole refresh. A sleeping controller is woken (hardware reset +
        initialisation) first.

        Raises:
            TimeoutError: If a BUSY wait exceeds busy_timeout_ms.
        """
        if self._asleep:
            self._wake()
        self._ensure_power()
        self._write_planes()
        self._refresh_power_off()

    def sleep(self):
        """Put the controller into deep sleep (border floating, POF, DSLP 0xA5).

        Only a hardware reset restores communication; show() and Full.wake()
        do that. The panel keeps its image.

        Raises:
            TimeoutError: If the power-off BUSY wait exceeds busy_timeout_ms.
        """
        if self._powered:
            self._power_off()
        self._connection.send_command(self._CMD_DSLP, b'\xa5')
        self._asleep = True

    # -- internals ---------------------------------------------------------

    def _wait_ready(self, timeout_ms=_DEFAULT):
        if timeout_ms is _DEFAULT:
            timeout_ms = self._timeout
        if not self._connection.wait_busy(False, timeout_ms):
            raise TimeoutError('IL0373: BUSY did not go high within %s ms' % timeout_ms)

    def _init_sequence(self):
        # Vendor OTP reference flow: reset, booster soft start, power on, panel
        # setting, resolution, VCOM and data interval.
        self._connection.reset()
        self._wait_ready()
        self._connection.send_command(self._CMD_BTST, b'\x17\x17\x17')
        self._connection.send_command(self._CMD_PON)
        self._wait_ready()
        self._powered = True
        self._connection.send_command(self._CMD_PSR, bytes((self._psr,)))
        self._connection.send_command(self._CMD_TRES, bytes((
            self._width & 0xF8, (self._height >> 8) & 1, self._height & 0xFF)))
        self._connection.send_command(self._CMD_CDI, b'\x97')

    def _wake(self):
        self._asleep = False
        self._powered = False
        self._init_sequence()

    def _ensure_power(self):
        if self._powered:
            return
        self._connection.send_command(self._CMD_PON)
        self._wait_ready()
        self._powered = True
        self._connection.send_command(self._CMD_CDI, b'\x97')

    def _power_off(self):
        self._connection.send_command(self._CMD_CDI, b'\xf7')
        self._connection.send_command(self._CMD_POF)
        self._powered = False
        self._wait_ready()

    def _refresh_power_off(self):
        self._connection.send_command(self._CMD_DRF)
        self._wait_ready()
        self._power_off()

    @staticmethod
    def _invert(data):
        return bytes(b ^ 0xFF for b in data)

    def _write_planes(self):
        self._connection.send_command(self._CMD_DTM1)
        self._connection.send_data(self._invert(self._fb))
        self._connection.send_command(self._CMD_DTM2)
        self._connection.send_data(bytes(self._fb))


class IL0373Full(IL0373Minimal):
    """IL0373 black/white e-paper controller — full interface.

    Extends IL0373Minimal with drawing helpers, partial-window updates, scan
    direction, power and VCOM settings, frame rate, user LUTs and the read
    commands (status, temperature, revision, VCOM, low power, OTP).

    The read_* methods need a half-duplex wire mode (the controller drives the
    shared DIN/DOUT line); with a SIMPLEX connection they raise RuntimeError.

    Args:
        connection: A DisplayConnection.
        width: Pixels per row, a multiple of 8, 8..160.
        height: Rows, 1..296.
        busy_timeout_ms: Limit for every BUSY wait in milliseconds, or None.
    """

    # -- framebuffer -------------------------------------------------------

    def get_pixel(self, x, y):
        """Return the colour of one framebuffer pixel.

        Args:
            x: Column.
            y: Row.

        Returns:
            int: BLACK or WHITE (BLACK when out of range).
        """
        if x < 0 or y < 0 or x >= self._width or y >= self._height:
            return self.BLACK
        index = y * self._row_bytes + (x >> 3)
        mask = 0x80 >> (x & 7)
        return self.WHITE if self._fb[index] & mask else self.BLACK

    def draw_line(self, x0, y0, x1, y1, color=IL0373Minimal.BLACK):
        """Bresenham line into the framebuffer.

        Args:
            x0: Start column.
            y0: Start row.
            x1: End column.
            y1: End row.
            color: BLACK (default) or WHITE.
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

    def draw_rect(self, x, y, w, h, color=IL0373Minimal.BLACK, fill=False):
        """Rectangle outline or filled rectangle into the framebuffer.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.
            color: BLACK (default) or WHITE.
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

    def blit(self, x, y, data, w, h, color=IL0373Minimal.BLACK):
        """Copy a 1-bit-per-pixel bitmap into the framebuffer.

        Row-major, MSB-first, rows padded to whole bytes. Set bits are drawn in
        color, clear bits leave the framebuffer unchanged.

        Args:
            x: Left column.
            y: Top row.
            data: Bitmap bytes.
            w: Bitmap width in pixels.
            h: Bitmap height in pixels.
            color: BLACK (default) or WHITE.
        """
        if w <= 0 or h <= 0:
            return
        row_bytes = (w + 7) // 8
        for j in range(h):
            for i in range(w):
                if data[j * row_bytes + (i >> 3)] & (0x80 >> (i & 7)):
                    self.set_pixel(x + i, y + j, color)

    # -- updates -----------------------------------------------------------

    def show_region(self, x, y, w, h):
        """Update only a window: write just its rows, then refresh.

        PTIN, PTL (window widened to whole 8-pixel columns), the window's bytes
        of both planes, DRF, power off, PTOUT. Saves SPI time; the waveform is
        the OTP full waveform, so the panel still flashes.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.

        Raises:
            ValueError: If the window is empty or outside the panel.
            TimeoutError: If a BUSY wait exceeds busy_timeout_ms.
        """
        if w <= 0 or h <= 0:
            raise ValueError('empty window')
        if x < 0:
            w += x
            x = 0
        if y < 0:
            h += y
            y = 0
        if x >= self._width or y >= self._height or w <= 0 or h <= 0:
            raise ValueError('window outside the panel')
        x0 = x & ~7
        x1 = min((x + w - 1) | 7, self._width - 1)
        y0 = y
        y1 = min(y + h - 1, self._height - 1)
        if self._asleep:
            self._wake()
        self._ensure_power()
        self._connection.send_command(self._CMD_PTIN)
        self._connection.send_command(self._CMD_PTL, bytes((
            x0 & 0xF8, (x1 & 0xF8) | 0x07,
            (y0 >> 8) & 1, y0 & 0xFF,
            (y1 >> 8) & 1, y1 & 0xFF,
            0x00)))  # PT_SCAN = 0: scan only inside the window
        win_bytes = (x1 - x0 + 1) // 8
        for cmd, invert in ((self._CMD_DTM1, True), (self._CMD_DTM2, False)):
            self._connection.send_command(cmd)
            for row in range(y0, y1 + 1):
                start = row * self._row_bytes + (x0 >> 3)
                chunk = bytes(self._fb[start:start + win_bytes])
                self._connection.send_data(self._invert(chunk) if invert else chunk)
        try:
            self._refresh_power_off()
        finally:
            self._connection.send_command(self._CMD_PTOUT)

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

    # -- settings ----------------------------------------------------------

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
        self._write_psr()

    def set_power(self, vdh, vdl, vgh=20.0):
        """Source/gate power (PWR, internal DC-DC).

        Advanced: the values must match the panel vendor's; a wrong set can
        drive the panel outside its rating. The driver does not write PWR by
        default.

        Args:
            vdh: Positive source voltage, 6.4..15.0 V (0.2 V steps).
            vdl: Negative source voltage magnitude, 6.4..15.0 V.
            vgh: Gate voltage magnitude: 20, 19, 18 or 17 V (VGL = -VGH).
        """
        lv = max(0, min(3, int(20.0 - vgh + 0.5)))
        self._connection.send_command(self._CMD_PWR, bytes((
            0x03,  # VDS_EN | VDG_EN
            lv,    # VCOM_HV = 0, VGHL_LV[1:0]
            self._code(vdh, 6.4, 0.2, 0x2B),
            self._code(abs(vdl), 6.4, 0.2, 0x2B),
            0x03)))  # VDHR (red): unused in KW mode

    def set_vcom_dc(self, volts):
        """VCOM_DC (VDCS): -0.10 .. -3.00 V in 0.05 V steps.

        Args:
            volts: VCOM voltage, e.g. the value printed on the panel label.
        """
        self._connection.send_command(
            self._CMD_VDCS, bytes((self._code(-0.10 - volts, 0.0, 0.05, 0x3A),)))

    def set_frame_rate(self, hz):
        """Frame rate (PLL, M/N layout): the nearest rate of the table.

        Args:
            hz: Requested rate, 4..200 Hz.
        """
        best = 0x3C  # M = 7, N = 4 (50 Hz)
        best_diff = 10000
        for m in range(1, 8):
            for n in range(1, 8):
                diff = abs(_PLL_HZ[m - 1][n - 1] - hz)
                if diff < best_diff:
                    best_diff = diff
                    best = (m << 3) | n
        self._connection.send_command(self._CMD_PLL, bytes((best,)))

    def set_soft_start(self, a, b, c):
        """Booster soft start (BTST).

        Args:
            a: Phase A byte.
            b: Phase B byte.
            c: Phase C byte.
        """
        self._connection.send_command(self._CMD_BTST, bytes((a, b, c)))

    def set_interval(self, cdi, border=2, polarity=1):
        """VCOM and data interval (CDI).

        Args:
            cdi: Interval, 0..15 (0b0111 = 10 hsync).
            border: Border data selection VBD, 0..3 (2 as at initialisation).
            polarity: Data polarity DDX, 0..3. The framebuffer mapping assumes
                1; changing it is the caller's responsibility.
        """
        value = ((border & 3) << 6) | ((polarity & 3) << 4) | (cdi & 0x0F)
        self._connection.send_command(self._CMD_CDI, bytes((value,)))

    def set_temperature_sensor(self, external, offset=0):
        """Temperature sensor source (TSE): internal (default) or external LM75.

        Args:
            external: True selects the external sensor.
            offset: Temperature offset, -8..+7 degrees Celsius.
        """
        value = (0x80 if external else 0x00) | (offset & 0x0F)
        self._connection.send_command(self._CMD_TSE, bytes((value,)))

    def write_lut(self, table, data):
        """Load one user waveform table and select register LUTs (PSR.REG = 1).

        The chip datasheet gives 44 (VCOM) / 42 bytes per table, the panel
        datasheet 60 / 36 / 60 / 36 / 36; the bytes are sent as given.

        Args:
            table: 0 = VCOM, 1 = W-W, 2 = B-W, 3 = W-B, 4 = B-B (commands 0x20..0x24).
            data: Table bytes.

        Raises:
            ValueError: If table is not 0..4.
        """
        if table < 0 or table > 4:
            raise ValueError('table must be 0..4')
        self._connection.send_command(self._CMD_LUTC + table, bytes(data))
        self._psr |= 0x20
        self._write_psr()

    def send_command(self, command, data=b''):
        """Send a raw command and parameters (escape hatch).

        Args:
            command: Command byte.
            data: Parameter bytes.
        """
        self._connection.send_command(command, data)

    # -- reads (half-duplex wiring only) -----------------------------------

    def read_status(self):
        """Read FLG: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY.

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

    def read_revision(self):
        """Read REV (LUT_REV, one byte).

        Returns:
            int: The LUT revision.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        return self._read(self._CMD_REV, 1)[0]

    def read_vcom(self):
        """Read the measured VCOM (VV, panel layout).

        Returns:
            float: Volts, -0.10 V - 0.05 V x VV[5:0].

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        return -0.10 - 0.05 * (self._read(self._CMD_VV, 1)[0] & 0x3F)

    def read_low_power(self):
        """Read low-power detection (LPD).

        Returns:
            bool: True when the supply is low.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        return (self._read(self._CMD_LPD, 1)[0] & 1) == 0

    def read_otp(self, length):
        """Read OTP bytes from address 0 (ROTP; the dummy byte is dropped).

        Args:
            length: Number of bytes.

        Returns:
            bytes: The OTP contents.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        self._connection.send_command(self._CMD_ROTP)
        self._connection.read_data(1)
        return bytes(self._connection.read_data(length))

    # -- internals ---------------------------------------------------------

    def _write_psr(self):
        self._connection.send_command(self._CMD_PSR, bytes((self._psr,)))

    def _read(self, cmd, length):
        self._connection.send_command(cmd)
        return self._connection.read_data(length)

    @staticmethod
    def _code(value, base, step, max_code):
        code = int((value - base) / step + 0.5)
        return max(0, min(max_code, code))
