class WS2812BMinimal:
    """WS2812B addressable RGB LED strip — minimal interface.

    Drives a chain of n WS2812B pixels over a NeoPixel transport.
    Maintains an internal GRB buffer; fill() writes all pixels and
    transmits immediately.

    Args:
        transport: Configured NeoPixel transport (MicroPython, CircuitPython, or Linux).
        n: Number of pixels in the strip.
    """

    def __init__(self, transport, n):
        """Initialise WS2812BMinimal with a transport and pixel count.

        Args:
            transport: Configured NeoPixel transport.
            n: Number of pixels in the strip (must be >= 1).
        """
        self._transport = transport
        self._n = n
        self._buf = bytearray(n * 3)

    def fill(self, r, g, b):
        """Fill every pixel with one colour and send to the strip immediately.

        Clamps each channel to [0, 255]. Stores G, R, B in the internal buffer
        (GRB wire order) then transmits.

        Args:
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
        """
        r = max(0, min(255, int(r)))
        g = max(0, min(255, int(g)))
        b = max(0, min(255, int(b)))
        for i in range(self._n):
            self._buf[i * 3]     = g
            self._buf[i * 3 + 1] = r
            self._buf[i * 3 + 2] = b
        self._transport.write(bytes(self._buf))

    def off(self):
        """Turn off all pixels (fill with black and send).

        Equivalent to fill(0, 0, 0).
        """
        self.fill(0, 0, 0)


class WS2812BFull(WS2812BMinimal):
    """WS2812B full interface — extends WS2812BMinimal with per-pixel control.

    Adds individual pixel addressing, explicit show(), global brightness
    scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
    to update the buffer, then show() to transmit; or use the inherited
    fill() for an immediate all-same-colour update.

    Args:
        transport: Configured NeoPixel transport.
        n: Number of pixels in the strip.
    """

    def __init__(self, transport, n):
        """Initialise WS2812BFull with a transport and pixel count.

        Args:
            transport: Configured NeoPixel transport.
            n: Number of pixels in the strip.
        """
        super().__init__(transport, n)
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
        self._buf[index * 3]     = max(0, min(255, int(g)))
        self._buf[index * 3 + 1] = max(0, min(255, int(r)))
        self._buf[index * 3 + 2] = max(0, min(255, int(b)))

    def set_pixels(self, colors):
        """Write a sequence of (r, g, b) tuples into the buffer starting at pixel 0.

        Extra entries beyond the strip length are ignored. Call show() to transmit.

        Args:
            colors: Iterable of (r, g, b) tuples (0–255 each).
        """
        for i, (r, g, b) in enumerate(colors):
            if i >= self._n:
                break
            self._buf[i * 3]     = max(0, min(255, int(g)))
            self._buf[i * 3 + 1] = max(0, min(255, int(r)))
            self._buf[i * 3 + 2] = max(0, min(255, int(b)))

    def show(self):
        """Transmit the current buffer to the strip, applying brightness scaling.

        Each channel value is scaled: sent = stored * brightness // 255.
        """
        bri = self._brightness
        if bri == 255:
            self._transport.write(bytes(self._buf))
        else:
            scaled = bytearray(len(self._buf))
            for i, v in enumerate(self._buf):
                scaled[i] = v * bri // 255
            self._transport.write(bytes(scaled))

    def rotate(self, steps=1):
        """Shift the pixel buffer left by steps positions (wraps around).

        Does not transmit — call show() afterwards.

        Args:
            steps: Number of pixel positions to shift left (default 1).
        """
        steps = steps % self._n
        if steps == 0:
            return
        n3 = self._n * 3
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


from ._color import _hsv_to_rgb  # noqa: F401 — re-exported for callers
