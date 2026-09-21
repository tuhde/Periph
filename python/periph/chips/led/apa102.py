from ._color import _hsv_to_rgb


class APA102Minimal:
    """APA102 addressable RGB LED strip — minimal interface.

    Drives a chain of n APA102 pixels over an SPI connection.
    Maintains an internal BGR+brightness buffer; fill() writes all pixels
    and transmits immediately with start/end framing.

    Args:
        connection: Configured SPI connection (MicroPython, CircuitPython, or Linux).
        n: Number of pixels in the strip.
    """

    def __init__(self, connection, n):
        """Initialise APA102Minimal with a connection and pixel count.

        Args:
            connection: Configured SPI connection.
            n: Number of pixels in the strip (must be >= 1).
        """
        self._connection = connection
        self._n = n
        self._buf = bytearray(n * 4)
        for i in range(n):
            self._buf[i * 4] = 0xE0 | 31  # brightness = 31 (max)
            self._buf[i * 4 + 1] = 0      # blue
            self._buf[i * 4 + 2] = 0      # green
            self._buf[i * 4 + 3] = 0      # red

    def fill(self, r, g, b):
        """Fill every pixel with one colour and send to the strip immediately.

        Clamps each channel to [0, 255]. Stores brightness/B/G/R in the
        internal buffer (BGR wire order with hardware brightness byte first)
        then transmits the full frame (start + pixels + end).

        Args:
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
        """
        r = max(0, min(255, int(r)))
        g = max(0, min(255, int(g)))
        b = max(0, min(255, int(b)))
        for i in range(self._n):
            self._buf[i * 4] = 0xE0 | 31  # hardware brightness = 31
            self._buf[i * 4 + 1] = b       # blue
            self._buf[i * 4 + 2] = g       # green
            self._buf[i * 4 + 3] = r       # red
        self._write_frame()

    def off(self):
        """Turn off all pixels (fill with black and send).

        Equivalent to fill(0, 0, 0).
        """
        self.fill(0, 0, 0)

    def _write_frame(self):
        """Send the full APA102 frame (start + pixel buffer + end)."""
        start_frame = bytes(4)  # 0x00 × 4
        end_bytes = max(4, (self._n + 15) // 16)
        end_frame = bytes([0xFF] * end_bytes)
        self._connection.write(start_frame + bytes(self._buf) + end_frame)


class APA102Full(APA102Minimal):
    """APA102 full interface — extends APA102Minimal with per-pixel control.

    Adds individual pixel addressing with per-pixel hardware brightness,
    explicit show(), global software brightness scaling, buffer rotation,
    and HSV fill. Call set_pixel() / set_pixels() to update the buffer,
    then show() to transmit; or use the inherited fill() for an immediate
    all-same-colour update.

    Args:
        connection: Configured SPI connection.
        n: Number of pixels in the strip.
    """

    def __init__(self, connection, n):
        """Initialise APA102Full with a connection and pixel count.

        Args:
            connection: Configured SPI connection.
            n: Number of pixels in the strip.
        """
        super().__init__(connection, n)
        self._brightness = 255  # global software brightness (0–255)

    @property
    def brightness(self):
        """Global brightness scalar applied at show() time (0–255)."""
        return self._brightness

    @brightness.setter
    def brightness(self, value):
        self._brightness = max(0, min(255, int(value)))

    def set_pixel(self, index, r, g, b, pixel_brightness=31):
        """Set one pixel in the buffer without sending.

        Clamps index to [0, n-1], RGB channels to [0, 255], and
        hardware brightness to [0, 31]. Call show() to transmit.

        Args:
            index: Zero-based pixel index.
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
            pixel_brightness: Per-pixel hardware brightness 0–31 (default 31).
        """
        index = max(0, min(self._n - 1, int(index)))
        r = max(0, min(255, int(r)))
        g = max(0, min(255, int(g)))
        b = max(0, min(255, int(b)))
        pixel_brightness = max(0, min(31, int(pixel_brightness)))
        self._buf[index * 4] = 0xE0 | pixel_brightness
        self._buf[index * 4 + 1] = b
        self._buf[index * 4 + 2] = g
        self._buf[index * 4 + 3] = r

    def set_pixels(self, colors):
        """Write a sequence of color tuples into the buffer starting at pixel 0.

        Each element is (r, g, b) or (r, g, b, pixel_brightness).
        Missing brightness defaults to 31. Extra entries beyond the strip
        length are ignored. Call show() to transmit.

        Args:
            colors: Iterable of (r, g, b) or (r, g, b, pixel_brightness) tuples.
        """
        for i, color in enumerate(colors):
            if i >= self._n:
                break
            r, g, b = color[0], color[1], color[2]
            pixel_brightness = color[3] if len(color) > 3 else 31
            r = max(0, min(255, int(r)))
            g = max(0, min(255, int(g)))
            b = max(0, min(255, int(b)))
            pixel_brightness = max(0, min(31, int(pixel_brightness)))
            self._buf[i * 4] = 0xE0 | pixel_brightness
            self._buf[i * 4 + 1] = b
            self._buf[i * 4 + 2] = g
            self._buf[i * 4 + 3] = r

    def show(self):
        """Transmit the current buffer to the strip, applying software brightness scaling.

        Each RGB channel value is scaled: sent = stored * brightness // 255.
        The per-pixel hardware brightness byte is not affected by this scaling.
        """
        bri = self._brightness
        start_frame = bytes(4)
        end_bytes = max(4, (self._n + 15) // 16)
        end_frame = bytes([0xFF] * end_bytes)

        if bri == 255:
            self._connection.write(start_frame + bytes(self._buf) + end_frame)
        else:
            scaled = bytearray(len(self._buf))
            for i in range(self._n):
                base = i * 4
                # Hardware brightness byte unchanged
                scaled[base] = self._buf[base]
                # Scale RGB channels
                scaled[base + 1] = self._buf[base + 1] * bri // 255  # blue
                scaled[base + 2] = self._buf[base + 2] * bri // 255  # green
                scaled[base + 3] = self._buf[base + 3] * bri // 255  # red
            self._connection.write(start_frame + bytes(scaled) + end_frame)

    def rotate(self, steps=1):
        """Shift the pixel buffer left by steps positions (wraps around).

        Operates on whole 4-byte pixel units. Does not transmit — call show()
        afterwards.

        Args:
            steps: Number of pixel positions to shift left (default 1).
        """
        steps = steps % self._n
        if steps == 0:
            return
        s4 = steps * 4
        self._buf = self._buf[s4:] + self._buf[:s4]

    def fill_hsv(self, h, s, v):
        """Fill every pixel with one HSV colour and send to the strip immediately.

        Converts HSV to RGB then calls fill() at hardware brightness 31.

        Args:
            h: Hue (0.0–1.0).
            s: Saturation (0.0–1.0).
            v: Value/brightness (0.0–1.0).
        """
        r, g, b = _hsv_to_rgb(h, s, v)
        self.fill(r, g, b)