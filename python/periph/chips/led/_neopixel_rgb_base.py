from ._color import _hsv_to_rgb


class _NeoPixelRGBMinimal:
    """Shared minimal-tier logic for 3-channel (RGB) NeoPixel-protocol LED drivers.

    Drives a chain of n pixels over a NeoPixel connection. Maintains an
    internal buffer in wire order; fill() writes all pixels and transmits
    immediately. Subclasses fix channel_order and reset_bytes for their
    specific chip and expose a public __init__(self, connection, n)
    constructor that calls this one with those fixed values.

    Args:
        connection: Configured NeoPixel connection (MicroPython, CircuitPython, or Linux).
        n: Number of pixels in the strip.
        channel_order: Tuple (i_r, i_g, i_b) — wire[k] = (r, g, b)[channel_order[k]].
        reset_bytes: Extra zero-bytes appended after pixel data for this chip's reset pulse.
    """

    def __init__(self, connection, n, channel_order, reset_bytes):
        """Initialise the base with a connection, pixel count, and chip-specific values.

        Args:
            connection: Configured NeoPixel connection.
            n: Number of pixels in the strip (must be >= 1).
            channel_order: Tuple (i_r, i_g, i_b) mapping wire position to (r, g, b) index.
            reset_bytes: Extra zero-bytes appended after pixel data.
        """
        self._connection = connection
        self._n = n
        self._buf = bytearray(n * 3)
        self._channel_order = channel_order
        self._reset = bytes(reset_bytes)

    def fill(self, r, g, b):
        """Fill every pixel with one colour and send to the strip immediately.

        Clamps each channel to [0, 255]. Stores the three channels in the
        internal buffer using this chip's wire channel order, then transmits.

        Args:
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
        """
        vals = (max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))))
        i0, i1, i2 = self._channel_order
        w0, w1, w2 = vals[i0], vals[i1], vals[i2]
        for i in range(self._n):
            self._buf[i * 3]     = w0
            self._buf[i * 3 + 1] = w1
            self._buf[i * 3 + 2] = w2
        self._connection.write(bytes(self._buf) + self._reset)

    def off(self):
        """Turn off all pixels (fill with black and send).

        Equivalent to fill(0, 0, 0).
        """
        self.fill(0, 0, 0)


class _NeoPixelRGBFull(_NeoPixelRGBMinimal):
    """Shared full-tier logic for 3-channel (RGB) NeoPixel-protocol LED drivers.

    Adds individual pixel addressing, explicit show(), global brightness
    scaling, buffer rotation, and HSV fill on top of _NeoPixelRGBMinimal.
    Call set_pixel() / set_pixels() to update the buffer, then show() to
    transmit; or use the inherited fill() for an immediate all-same-colour
    update.

    Args:
        connection: Configured NeoPixel connection.
        n: Number of pixels in the strip.
        channel_order: Tuple (i_r, i_g, i_b) mapping wire position to (r, g, b) index.
        reset_bytes: Extra zero-bytes appended after pixel data.
    """

    def __init__(self, connection, n, channel_order, reset_bytes):
        """Initialise the base with a connection, pixel count, and chip-specific values.

        Args:
            connection: Configured NeoPixel connection.
            n: Number of pixels in the strip.
            channel_order: Tuple (i_r, i_g, i_b) mapping wire position to (r, g, b) index.
            reset_bytes: Extra zero-bytes appended after pixel data.
        """
        super().__init__(connection, n, channel_order, reset_bytes)
        self._brightness = 255

    @property
    def brightness(self):
        """Global brightness scalar applied at show() time (0–255)."""
        return self._brightness

    @brightness.setter
    def brightness(self, value):
        self._brightness = max(0, min(255, int(value)))

    def set_pixel(self, index, r, g, b):
        """Set one pixel in the buffer without sending.

        Clamps index to [0, n-1] and each channel to [0, 255].
        Call show() to transmit.

        Args:
            index: Zero-based pixel index.
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
        """
        index = max(0, min(self._n - 1, int(index)))
        vals = (max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))))
        i0, i1, i2 = self._channel_order
        self._buf[index * 3]     = vals[i0]
        self._buf[index * 3 + 1] = vals[i1]
        self._buf[index * 3 + 2] = vals[i2]

    def set_pixels(self, colors):
        """Write a sequence of (r, g, b) tuples into the buffer starting at pixel 0.

        Extra entries beyond the strip length are ignored. Call show() to transmit.

        Args:
            colors: Iterable of (r, g, b) tuples (0–255 each).
        """
        i0, i1, i2 = self._channel_order
        for i, (r, g, b) in enumerate(colors):
            if i >= self._n:
                break
            vals = (max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))))
            self._buf[i * 3]     = vals[i0]
            self._buf[i * 3 + 1] = vals[i1]
            self._buf[i * 3 + 2] = vals[i2]

    def show(self):
        """Transmit the current buffer to the strip, applying brightness scaling.

        Each channel value is scaled: sent = stored * brightness // 255.
        """
        bri = self._brightness
        if bri == 255:
            self._connection.write(bytes(self._buf) + self._reset)
        else:
            scaled = bytearray(len(self._buf))
            for i, v in enumerate(self._buf):
                scaled[i] = v * bri // 255
            self._connection.write(bytes(scaled) + self._reset)

    def rotate(self, steps=1):
        """Shift the pixel buffer left by steps positions (wraps around).

        Does not transmit — call show() afterwards.

        Args:
            steps: Number of pixel positions to shift left (default 1).
        """
        steps = steps % self._n
        if steps == 0:
            return
        s3 = steps * 3
        self._buf = self._buf[s3:] + self._buf[:s3]

    def fill_hsv(self, h, s, v):
        """Fill every pixel with one HSV colour and send to the strip immediately.

        Converts HSV to RGB then calls fill().

        Args:
            h: Hue (0.0–1.0).
            s: Saturation (0.0–1.0).
            v: Value/brightness (0.0–1.0).
        """
        r, g, b = _hsv_to_rgb(h, s, v)
        self.fill(r, g, b)
