_ZEROS = bytes(512)

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


class UC8276CMinimal:
    """UC8276C black/white/red e-paper controller — minimal interface.

    Drives a 400 x 300 (up to 400 source x 300 gate) white/black/red
    electrophoretic panel such as the Waveshare 4.2inch e-Paper (B). The
    driver keeps the picture in framebuffers and sends it on show(). Every
    update is the datasheet sequence DTM1 + DTM2 -> PON -> DRF -> POF, waiting
    on BUSY_N after each power/refresh step; show() blocks for the whole
    refresh (about 17 s on the reference panel), which stalls the interpreter
    on MicroPython and CircuitPython.

    The connection is a display connection (DisplayConnection for MicroPython,
    CircuitPython or Linux, see specs/transport_display.md) that owns CS#, D/C#,
    RES# and BUSY_N. BUSY_N is active low.

    Pixel polarity: the framebuffer mapping (B/W bit 1 = white, red bit 1 =
    red) holds for CDI.DDX = 01, which the driver writes explicitly at
    initialisation instead of relying on the OTP default.

    Class attributes:
        BLACK, WHITE, RED: Colours.
        MODE_KWR, MODE_KW: Pixel modes (PSR.KW/R).

    Args:
        connection: A DisplayConnection (SIMPLEX wiring is enough).
        width: Pixels per row, a multiple of 8, 8..400.
        height: Rows, 1..300.
        busy_timeout_ms: Limit for every BUSY_N wait in milliseconds, or None
            for no limit. The full refresh was measured at 16.8 s.

    Raises:
        ValueError: If width or height is out of range.
        TimeoutError: If BUSY_N does not go high during initialisation.
    """

    BLACK = 0
    WHITE = 1
    RED = 2

    MODE_KWR = 0
    MODE_KW = 1

    _CMD_PSR = 0x00
    _CMD_PWR = 0x01
    _CMD_POF = 0x02
    _CMD_PON = 0x04
    _CMD_BTST = 0x06
    _CMD_DSLP = 0x07
    _CMD_DTM1 = 0x10
    _CMD_DRF = 0x12
    _CMD_DTM2 = 0x13
    _CMD_AUTO = 0x17
    _CMD_LUTC = 0x20
    _CMD_LUTWW = 0x21
    _CMD_LUTKW = 0x22
    _CMD_LUTWK = 0x23
    _CMD_LUTKK = 0x24
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

    MAX_WIDTH = 400
    MAX_HEIGHT = 300

    def __init__(self, connection, width=400, height=300, busy_timeout_ms=30000):
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
        self._bw = bytearray(b'\xff') * self._size
        self._red = None
        self._psr = 0x0F
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
        """Fill the framebuffers with one colour. The panel is unchanged until show().

        Args:
            color: WHITE (default), BLACK or RED.
        """
        n = self._size
        self._bw = bytearray(b'\xff' if color == self.WHITE else b'\x00') * n
        if color == self.RED:
            self._red = bytearray(b'\xff') * n
        elif self._red is not None:
            self._red = bytearray(n)

    def set_pixel(self, x, y, color=BLACK):
        """Set one pixel in the framebuffer. Out-of-range coordinates are ignored.

        The red plane is allocated on the first RED pixel.

        Args:
            x: Column, 0 <= x < width.
            y: Row, 0 <= y < height.
            color: BLACK (default), WHITE or RED.
        """
        if x < 0 or y < 0 or x >= self._width or y >= self._height:
            return
        index = y * self._row_bytes + (x >> 3)
        mask = 0x80 >> (x & 7)
        if color == self.WHITE:
            self._bw[index] |= mask
        else:
            self._bw[index] &= ~mask & 0xFF
        if color == self.RED:
            if self._red is None:
                self._red = bytearray(self._size)
            self._red[index] |= mask
        elif self._red is not None:
            self._red[index] &= ~mask & 0xFF

    def show(self):
        """Write both planes to the controller and run a full display update.

        DTM1, DTM2, PON, wait BUSY_N, DRF, wait BUSY_N, POF, wait BUSY_N. Blocks
        for the whole refresh. A sleeping controller is woken (hardware reset +
        initialisation) first.

        Raises:
            TimeoutError: If a BUSY_N wait exceeds busy_timeout_ms.
        """
        if self._asleep:
            self._wake()
        self._write_planes()
        self._power_cycle_refresh()

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
            raise TimeoutError('UC8276C: BUSY_N did not go high within %s ms' % timeout_ms)

    def _init_sequence(self):
        self._connection.reset()
        self._wait_ready()
        self._connection.send_command(self._CMD_PSR, bytes((self._psr,)))
        if self._width != self.MAX_WIDTH or self._height != self.MAX_HEIGHT:
            self._connection.send_command(self._CMD_TRES, bytes((
                (self._width >> 8) & 1, self._width & 0xF8,
                (self._height >> 8) & 1, self._height & 0xFF)))
        # VBD = 11, DDX = 01, CDI = 0111: the framebuffer polarity holds
        self._connection.send_command(self._CMD_CDI, b'\xd7')

    def _wake(self):
        self._asleep = False
        self._init_sequence()

    def _send_zeros(self, total):
        while total > 0:
            n = min(total, len(_ZEROS))
            self._connection.send_data(_ZEROS[:n])
            total -= n

    def _send_plane(self, cmd, plane):
        self._connection.send_command(cmd)
        if plane is None:
            self._send_zeros(self._size)
        else:
            self._connection.send_data(bytes(plane))

    def _write_planes(self):
        self._send_plane(self._CMD_DTM1, self._bw)
        self._send_plane(self._CMD_DTM2, self._red)

    def _power_cycle_refresh(self):
        self._connection.send_command(self._CMD_PON)
        self._wait_ready()
        self._connection.send_command(self._CMD_DRF)
        self._wait_ready()
        self._connection.send_command(self._CMD_POF)
        self._wait_ready()


class UC8276CFull(UC8276CMinimal):
    """UC8276C black/white/red e-paper controller — full interface.

    Extends UC8276CMinimal with drawing helpers, partial-window updates, the
    auto sequence, pixel mode / scan direction, power and VCOM settings, frame
    rate, user LUTs and the read commands (status, temperature, revision,
    VCOM, low power, OTP).

    Register layouts follow the panel datasheet where it disagrees with the
    chip datasheet (PWR, PLL, VCOM_DC); see specs/display/uc8276c.md. The
    read_* methods need a half-duplex wire mode (the controller drives the
    shared DIN/DOUT line); with a SIMPLEX connection they raise RuntimeError.

    Args:
        connection: A DisplayConnection.
        width: Pixels per row, a multiple of 8, 8..400.
        height: Rows, 1..300.
        busy_timeout_ms: Limit for every BUSY_N wait in milliseconds, or None.
    """

    # -- framebuffer -------------------------------------------------------

    def get_pixel(self, x, y):
        """Return the colour of one framebuffer pixel.

        Args:
            x: Column.
            y: Row.

        Returns:
            int: BLACK, WHITE or RED (BLACK when out of range).
        """
        if x < 0 or y < 0 or x >= self._width or y >= self._height:
            return self.BLACK
        index = y * self._row_bytes + (x >> 3)
        mask = 0x80 >> (x & 7)
        if self._red is not None and self._red[index] & mask:
            return self.RED
        return self.WHITE if self._bw[index] & mask else self.BLACK

    def draw_line(self, x0, y0, x1, y1, color=UC8276CMinimal.BLACK):
        """Bresenham line into the framebuffer.

        Args:
            x0: Start column.
            y0: Start row.
            x1: End column.
            y1: End row.
            color: BLACK (default), WHITE or RED.
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

    def draw_rect(self, x, y, w, h, color=UC8276CMinimal.BLACK, fill=False):
        """Rectangle outline or filled rectangle into the framebuffer.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.
            color: BLACK (default), WHITE or RED.
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

    def blit(self, x, y, data, w, h, color=UC8276CMinimal.BLACK):
        """Copy a 1-bit-per-pixel bitmap into the framebuffer.

        Row-major, MSB-first, rows padded to whole bytes. Set bits are drawn in
        color, clear bits leave the framebuffer unchanged.

        Args:
            x: Left column.
            y: Top row.
            data: Bitmap bytes.
            w: Bitmap width in pixels.
            h: Bitmap height in pixels.
            color: BLACK (default), WHITE or RED.
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
        of both planes, PON, DRF, POF, PTOUT. Saves SPI time; the waveform is
        the OTP full waveform, so the panel still flashes.

        Args:
            x: Left column.
            y: Top row.
            w: Width in pixels.
            h: Height in pixels.

        Raises:
            ValueError: If the window is empty or outside the panel.
            TimeoutError: If a BUSY_N wait exceeds busy_timeout_ms.
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
        self._connection.send_command(self._CMD_PTIN)
        self._connection.send_command(self._CMD_PTL, bytes((
            (x0 >> 8) & 1, x0 & 0xF8,
            (x1 >> 8) & 1, (x1 & 0xF8) | 0x07,
            (y0 >> 8) & 1, y0 & 0xFF,
            (y1 >> 8) & 1, y1 & 0xFF,
            0x00)))  # PT_SCAN = 0: scan only inside the window
        win_bytes = (x1 - x0 + 1) // 8
        for cmd, plane in ((self._CMD_DTM1, self._bw), (self._CMD_DTM2, self._red)):
            self._connection.send_command(cmd)
            for row in range(y0, y1 + 1):
                if plane is None:
                    self._send_zeros(win_bytes)
                else:
                    start = row * self._row_bytes + (x0 >> 3)
                    self._connection.send_data(bytes(plane[start:start + win_bytes]))
        try:
            self._power_cycle_refresh()
        finally:
            self._connection.send_command(self._CMD_PTOUT)

    def refresh_auto(self, deep_sleep=False):
        """Full-frame update using the AUTO sequence instead of PON/DRF/POF.

        AUTO is listed only in the chip datasheet. On the reference panel
        (Waveshare 4.2inch e-Paper (B)) it starts no refresh: the call returns
        after the plane transfer and the panel keeps its picture. Use show()
        unless your panel is known to accept it.

        Args:
            deep_sleep: True runs AUTO 0xA7 (also enters deep sleep afterwards).
        """
        if self._asleep:
            self._wake()
        self._write_planes()
        self._connection.send_command(self._CMD_AUTO, bytes((0xA7 if deep_sleep else 0xA5,)))
        self._wait_ready()
        if deep_sleep:
            self._asleep = True

    def wake(self):
        """Hardware reset and initialisation (exit from deep sleep). Framebuffers are kept."""
        self._wake()

    def wait_busy(self, timeout_ms=_DEFAULT):
        """Wait for BUSY_N to go high.

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

    def set_mode(self, mode):
        """Select the pixel mode.

        Args:
            mode: MODE_KWR (black/white/red, default) or MODE_KW (black/white
                with NEW/OLD; needs KW LUTs in OTP).
        """
        if mode == self.MODE_KW:
            self._psr |= 0x10
        else:
            self._psr &= ~0x10 & 0xFF
        self._write_psr()

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

    def set_power(self, vdh, vdl, vdhr, vgh=16.0):
        """Source/gate power (PWR, panel-datasheet layout, internal DC-DC).

        Advanced: the values must match the panel vendor's; a wrong set can
        drive the panel outside its rating. The driver does not write PWR by
        default.

        Args:
            vdh: Positive source voltage, 2.4..11.0 V (0.2 V steps).
            vdl: Negative source voltage magnitude, 2.4..11.0 V.
            vdhr: Red source voltage, 2.4..11.0 V.
            vgh: Gate voltage magnitude: 16, 15, 14 or 13 V (VGL = -VGH).
        """
        lv = max(0, min(3, int(16.0 - vgh + 0.5)))
        self._connection.send_command(self._CMD_PWR, bytes((
            0x03,  # VDS_EN | VDG_EN
            lv,    # VCOM_HV = 0, VGHL_LV[1:0]
            self._code(vdh, 2.4, 0.2, 0x2B),
            self._code(abs(vdl), 2.4, 0.2, 0x2B),
            self._code(vdhr, 2.4, 0.2, 0x2B))))

    def set_vcom_dc(self, volts):
        """VCOM_DC (VDCS): -0.10 .. -3.25 V in 0.05 V steps (panel layout).

        Args:
            volts: VCOM voltage, e.g. the value printed on the panel label.
        """
        self._connection.send_command(
            self._CMD_VDCS, bytes((self._code(-0.10 - volts, 0.0, 0.05, 0x3F),)))

    def set_frame_rate(self, hz):
        """Frame rate (PLL, M/N layout): the nearest rate of the panel's table.

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

    def set_interval(self, cdi, border=3, polarity=1):
        """VCOM and data interval (CDI).

        Args:
            cdi: Interval, 0..15 (0b0111 = 10 hsync, the default).
            border: Border data selection VBD, 0..3 (3 = floating in KWR).
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

    def write_lut(self, lut_c, lut_ww, lut_kw, lut_wk, lut_kk):
        """Load user waveform tables and select them (PSR.REG = 1).

        The LUT commands exist only in the chip datasheet; verify the panel
        accepts them. Lengths: lut_c 56, lut_ww 42, lut_kw 56, lut_wk 56,
        lut_kk 56 bytes. Any table may be None to leave it alone.

        Args:
            lut_c: VCOM LUT.
            lut_ww: White-to-white LUT.
            lut_kw: Black-to-white (red in KWR) LUT.
            lut_wk: White-to-black LUT.
            lut_kk: Black-to-black LUT.
        """
        for cmd, table in ((self._CMD_LUTC, lut_c), (self._CMD_LUTWW, lut_ww),
                           (self._CMD_LUTKW, lut_kw), (self._CMD_LUTWK, lut_wk),
                           (self._CMD_LUTKK, lut_kk)):
            if table is not None:
                self._connection.send_command(cmd, bytes(table))
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
        """Read FLG: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.

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

    def read_revision(self, length=1):
        """Read REV.

        The panel datasheet gives a 1-byte LUT revision, the chip datasheet 5
        bytes (reserved, CHIP_REV, LUT_REV[7:0], [15:8], [23:16]).

        Args:
            length: 1 (panel form, default) or 5 (chip form).

        Returns:
            tuple: (chip_rev, lut_version), or (None, lut_rev) for the 1-byte form.

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        raw = self._read(self._CMD_REV, length)
        if length == 1:
            return (None, raw[0])
        return (raw[1], raw[2] | (raw[3] << 8) | (raw[4] << 16))

    def read_vcom(self):
        """Read the measured VCOM (VV, panel layout).

        Returns:
            float: Volts, -0.10 V - 0.05 V x VV[5:0].

        Raises:
            RuntimeError: When the connection is SIMPLEX.
        """
        return -0.10 - 0.05 * (self._read(self._CMD_VV, 1)[0] & 0x3F)

    def read_low_power(self):
        """Read low-power detection (LPD, with LPMD = 0).

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
