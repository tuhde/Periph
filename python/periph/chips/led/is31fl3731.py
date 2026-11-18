try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


class IS31FL3731Minimal:
    """IS31FL3731 144-LED matrix driver (CharliePlex) — minimal interface.

    Lights individual LEDs of the 9 x 16 matrix with 8-bit PWM in Picture
    mode, drawing into frame 1. Construction runs the initialization sequence:
    software shutdown while configuring, Picture mode showing frame 1,
    frame 1 initialised (all LED-control bits on, PWM and blink cleared),
    then normal operation. LED current is set by R_EXT (3.2 mA at 20 kOhm).
    The driver keeps a shadow copy of every frame (the chip only allows frame
    register reads in software shutdown).

    Args:
        connection: RegisterConnection (I2C, address 0x74-0x77).
    """

    ADDRESS = 0x74
    ROWS = 9
    COLS = 16

    _REG_COMMAND = 0xFD
    _PAGE_FUNCTION = 0x0B
    _REG_CTRL = 0x00
    _REG_BLINK = 0x12
    _REG_PWM = 0x24

    _FN_CONFIG = 0x00
    _FN_PICTURE = 0x01
    _FN_AUTOPLAY1 = 0x02
    _FN_AUTOPLAY2 = 0x03
    _FN_DISPLAY = 0x05
    _FN_AUDIO_SYNC = 0x06
    _FN_FRAME_STATE = 0x07
    _FN_BREATH1 = 0x08
    _FN_BREATH2 = 0x09
    _FN_SHUTDOWN = 0x0A
    _FN_AGC = 0x0B
    _FN_AUDIO_RATE = 0x0C

    def __init__(self, connection):
        self._connection = connection
        self._page = None
        self._frame = 0
        self._pwm = [bytearray(144) for _ in range(8)]
        self._ctrl = [bytearray(b'\xff' * 18) for _ in range(8)]
        self._blink = [bytearray(18) for _ in range(8)]
        self._ic = 0
        self._be = 0
        self._bpt = 0
        self._func_write(self._FN_SHUTDOWN, 0x00)
        self._func_write(self._FN_CONFIG, bytes([0x00, 0x00]))
        self._func_write(self._FN_DISPLAY, 0x00)
        self._init_frame(0)
        self._func_write(self._FN_SHUTDOWN, 0x01)

    def _select(self, page):
        if self._page != page:
            self._connection.write_reg(self._REG_COMMAND, page)
            self._page = page

    def _func_write(self, reg, data):
        self._select(self._PAGE_FUNCTION)
        self._connection.write_reg(reg, data)

    def _func_read(self, reg, length=1):
        self._select(self._PAGE_FUNCTION)
        return self._connection.read_reg(reg, length)

    def _init_frame(self, frame):
        self._ctrl[frame][:] = b'\xff' * 18
        self._blink[frame][:] = bytes(18)
        self._pwm[frame][:] = bytes(144)
        self._select(frame)
        self._connection.write_reg(self._REG_CTRL, bytes(self._ctrl[frame]))
        self._connection.write_reg(self._REG_BLINK, bytes(18))
        self._connection.write_reg(self._REG_PWM, bytes(144))

    @staticmethod
    def _check_pixel(row, col):
        if row < 0 or row > 8:
            raise ValueError('row must be in 0..8')
        if col < 0 or col > 15:
            raise ValueError('col must be in 0..15')

    @staticmethod
    def _check_level(value, name, maximum):
        if value < 0 or value > maximum:
            raise ValueError('{} must be in 0..{}'.format(name, maximum))

    def set_pixel(self, row, col, brightness):
        """Set the PWM brightness of one LED in the frame being drawn.

        Args:
            row: Common line, 0-8 (CA/CB x - 1).
            col: Sink line, 0-15 (y - 1).
            brightness: PWM value, 0-255.

        Raises:
            ValueError: If an argument is out of range.
        """
        self._check_pixel(row, col)
        self._check_level(brightness, 'brightness', 255)
        self._pwm[self._frame][16 * row + col] = brightness
        self._select(self._frame)
        self._connection.write_reg(self._REG_PWM + 16 * row + col, brightness)

    def clear(self):
        """Set all 144 PWM values of the frame being drawn to 0 (one block write)."""
        self.fill(0)

    def fill(self, brightness):
        """Set all 144 PWM values of the frame being drawn (one block write).

        Args:
            brightness: PWM value, 0-255.

        Raises:
            ValueError: If brightness is out of range.
        """
        self._check_level(brightness, 'brightness', 255)
        self._pwm[self._frame][:] = bytes([brightness]) * 144
        self._select(self._frame)
        self._connection.write_reg(self._REG_PWM, bytes([brightness]) * 144)


class IS31FL3731Full(IS31FL3731Minimal):
    """IS31FL3731 full interface — frames, auto-play, blink, breath, audio, shutdown, INTB.

    Args:
        connection: RegisterConnection (I2C, address 0x74-0x77).
    """

    GAMMA_32 = bytes([0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
                      78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255])
    GAMMA_64 = bytes([0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
                      24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
                      77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
                      158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255])

    def __init__(self, connection):
        IS31FL3731Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    def get_pixel(self, row, col):
        """Read the PWM value of one LED from the shadow copy of the frame being drawn.

        Returns:
            int: PWM value, 0-255.
        """
        self._check_pixel(row, col)
        return self._pwm[self._frame][16 * row + col]

    def set_pixels(self, data):
        """Write a whole frame of PWM values (one block write).

        Args:
            data: 144 PWM values, row-major (row * 16 + col).

        Raises:
            ValueError: If data is not 144 bytes.
        """
        data = bytes(data)
        if len(data) != 144:
            raise ValueError('data must be 144 bytes')
        self._pwm[self._frame][:] = data
        self._select(self._frame)
        self._connection.write_reg(self._REG_PWM, data)

    def set_led_enabled(self, row, col, on):
        """Set the LED-control (on/off) bit of one LED in the frame being drawn.

        Args:
            row: 0-8.
            col: 0-15.
            on: True to enable the LED.
        """
        self._set_bit(self._ctrl, self._REG_CTRL, row, col, on)

    def set_blink(self, row, col, on):
        """Set the blink-enable bit of one LED in the frame being drawn.

        Args:
            row: 0-8.
            col: 0-15.
            on: True to let the LED blink (needs enable_blink()).
        """
        self._set_bit(self._blink, self._REG_BLINK, row, col, on)

    def _set_bit(self, cache, base, row, col, on):
        self._check_pixel(row, col)
        idx = 2 * row + (col >> 3)
        mask = 1 << (col & 7)
        buf = cache[self._frame]
        if on:
            buf[idx] |= mask
        else:
            buf[idx] &= ~mask & 0xFF
        self._select(self._frame)
        self._connection.write_reg(base + idx, buf[idx])

    def set_blink_all(self, on):
        """Set all 18 blink-control bytes of the frame being drawn.

        Args:
            on: True to enable blinking of every LED.
        """
        self._blink[self._frame][:] = (b'\xff' if on else b'\x00') * 18
        self._select(self._frame)
        self._connection.write_reg(self._REG_BLINK, bytes(self._blink[self._frame]))

    def _write_display(self):
        self._func_write(self._FN_DISPLAY, (self._ic << 5) | (self._be << 3) | self._bpt)

    def enable_blink(self, enabled, period=1):
        """Enable or disable blinking (50 % duty).

        Args:
            enabled: True to enable.
            period: Blink period in units of 0.27 s, 0-7.

        Raises:
            ValueError: If period is out of range.
        """
        self._check_level(period, 'period', 7)
        self._be = 1 if enabled else 0
        self._bpt = period
        self._write_display()

    def select_frame(self, frame):
        """Select the frame subsequent drawing calls write to (does not change the display).

        Args:
            frame: 0-7 (frame 1-8).
        """
        self._check_level(frame, 'frame', 7)
        self._frame = frame
        self._select(frame)

    def show_frame(self, frame):
        """Show a frame in Picture mode.

        Args:
            frame: 0-7 (frame 1-8).
        """
        self._check_level(frame, 'frame', 7)
        self._func_write(self._FN_CONFIG, bytes([0x00, frame]))

    def clear_frame(self, frame):
        """Initialise a frame: LED-control 0xFF, PWM and blink 0.

        Args:
            frame: 0-7.
        """
        self._check_level(frame, 'frame', 7)
        self._init_frame(frame)

    def clear_all_frames(self):
        """Initialise all eight frames."""
        for frame in range(8):
            self._init_frame(frame)

    def copy_frame(self, src, dst):
        """Copy the PWM values of one frame to another.

        Args:
            src: Source frame, 0-7.
            dst: Destination frame, 0-7.
        """
        self._check_level(src, 'src', 7)
        self._check_level(dst, 'dst', 7)
        data = bytes(self._pwm[src])
        self._pwm[dst][:] = data
        self._select(dst)
        self._connection.write_reg(self._REG_PWM, data)

    def set_picture_mode(self):
        """Switch to Picture mode (shows the frame selected by show_frame)."""
        self._func_write(self._FN_CONFIG, 0x00)

    def start_auto_play(self, frames=0, loops=0, delay_ms=121, start_frame=0):
        """Start Auto Frame Play.

        Args:
            frames: Number of frames played, 1-7, or 0 for all eight.
            loops: Number of loops, 1-7, or 0 for endless.
            delay_ms: Frame delay in ms (rounded to a multiple of 11 ms, 11-704).
            start_frame: First frame, 0-7.

        Raises:
            ValueError: If an argument is out of range.
        """
        self._check_level(frames, 'frames', 7)
        self._check_level(loops, 'loops', 7)
        self._check_level(start_frame, 'start_frame', 7)
        a = int(delay_ms / 11 + 0.5)
        if a < 1 or a > 64:
            raise ValueError('delay_ms must be in 11..704')
        self._func_write(self._FN_AUTOPLAY1, bytes([(loops << 4) | frames, a & 0x3F]))
        self._func_write(self._FN_CONFIG, 0x08 | start_frame)

    def stop_auto_play(self):
        """Stop Auto Frame Play and keep showing the current frame (Picture mode).

        Reading the current frame clears the movie-finished flag.
        """
        frame = self.current_frame()
        self._func_write(self._FN_CONFIG, bytes([0x00, frame]))

    def set_audio_mode(self, enabled):
        """Switch Audio Frame Play on, or back to Picture mode.

        Args:
            enabled: True for Audio Frame Play.
        """
        self._func_write(self._FN_CONFIG, 0x10 if enabled else 0x00)

    def set_audio_sync(self, enabled):
        """Let the audio input modulate the matrix intensity.

        Args:
            enabled: True to enable.
        """
        self._func_write(self._FN_AUDIO_SYNC, 1 if enabled else 0)

    def set_audio_gain(self, gain_db, agc=False, fast=False):
        """Configure the audio gain.

        Args:
            gain_db: 0, 3, ... 21 dB.
            agc: True to enable automatic gain control.
            fast: True for fast AGC, False for slow.

        Raises:
            ValueError: If gain_db is not a multiple of 3 in 0..21.
        """
        if gain_db < 0 or gain_db > 21 or gain_db % 3:
            raise ValueError('gain_db must be a multiple of 3 in 0..21')
        self._func_write(self._FN_AGC, ((1 if fast else 0) << 4) | ((1 if agc else 0) << 3) | (gain_db // 3))

    def set_audio_rate(self, a):
        """Set the audio ADC sample interval (46 us x a, 0 = 46 us x 256).

        Args:
            a: 0-255.
        """
        self._check_level(a, 'a', 255)
        self._func_write(self._FN_AUDIO_RATE, a)

    def set_breath(self, enabled, fade_in=0, fade_out=0, extinguish=0):
        """Configure the breath (fade) function.

        Args:
            enabled: True to enable.
            fade_in: Fade-in exponent, 0-7 (26 ms x 2^n).
            fade_out: Fade-out exponent, 0-7 (26 ms x 2^n).
            extinguish: Extinguish exponent, 0-7 (3.5 ms x 2^n).
        """
        self._check_level(fade_in, 'fade_in', 7)
        self._check_level(fade_out, 'fade_out', 7)
        self._check_level(extinguish, 'extinguish', 7)
        self._func_write(self._FN_BREATH1, bytes([(fade_out << 4) | fade_in,
                                                 ((1 if enabled else 0) << 4) | extinguish]))

    def set_intensity_control(self, use_frame1):
        """Choose whether frame 1's PWM values apply to all frames.

        Args:
            use_frame1: True to use frame 1's intensity for every frame.
        """
        self._ic = 1 if use_frame1 else 0
        self._write_display()

    def shutdown(self, on=True):
        """Enter or leave software shutdown (frame registers are kept).

        Args:
            on: True for shutdown, False for normal operation.
        """
        self._func_write(self._FN_SHUTDOWN, 0 if on else 1)

    def frame_state(self):
        """Read FRAME_STATE (reading clears the movie-finished flag).

        Returns:
            int: bit 4 = movie finished, bits 2:0 = current frame.
        """
        return self._func_read(self._FN_FRAME_STATE)[0]

    def current_frame(self):
        """Return the frame being displayed, 0-7."""
        return self.frame_state() & 0x07

    def movie_finished(self):
        """Return True if an Auto Frame Play movie has finished (clears the flag)."""
        return (self.frame_state() & 0x10) != 0

    def read_function_register(self, reg):
        """Read a function-page register and restore the selected page.

        Args:
            reg: Register address, 0-0x0C.

        Returns:
            int: Register value.
        """
        self._check_level(reg, 'reg', 0x0C)
        prev = self._page
        value = self._func_read(reg)[0]
        self._restore(prev)
        return value

    def write_function_register(self, reg, value):
        """Write a function-page register and restore the selected page.

        Args:
            reg: Register address, 0-0x0C.
            value: Byte to write.
        """
        self._check_level(reg, 'reg', 0x0C)
        self._check_level(value, 'value', 255)
        prev = self._page
        self._func_write(reg, value)
        self._restore(prev)

    def _restore(self, prev):
        if prev is not None and prev != self._page:
            self._select(prev)

    def poll_interrupt(self):
        """Read FRAME_STATE (clears INT) and restore the selected page.

        Returns:
            int: FRAME_STATE (INT = 0x10, current frame in bits 2:0).
        """
        prev = self._page
        value = self._func_read(self._FN_FRAME_STATE)[0]
        self._restore(prev)
        return value

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to the end of an Auto Frame Play movie.

        ``callback(status)`` receives FRAME_STATE. Uses ``int_pin`` or
        ``connection.int_pin`` (falling edge of INTB), else a 50 ms polling
        thread (Linux).

        Args:
            callback: Callable(status: int).
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._poll_stop = True
        if self._int_source is not None:
            self._int_source.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if self._callback:
            self._callback(status)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            status = self.poll_interrupt()
            if (status & 0x10) and self._callback:
                self._callback(status)
            time.sleep(0.05)
