from ._color import _hsv_to_rgb

_RESET_BYTES = bytes(24)


class SK6812RGBWMinimal:
    """SK6812RGBW addressable RGBW LED strip — minimal interface.

    Drives a chain of n SK6812RGBW pixels over a NeoPixel transport.
    Maintains an internal GRBW buffer; fill() writes all pixels and
    transmits immediately. Each pixel has four channels: red, green,
    blue, and white.

    Args:
        transport: Configured NeoPixel transport (MicroPython, CircuitPython, or Linux).
        n: Number of pixels in the strip.
    """

    def __init__(self, transport, n):
        """Initialise SK6812RGBWMinimal with a transport and pixel count.

        Args:
            transport: Configured NeoPixel transport.
            n: Number of pixels in the strip (must be >= 1).
        """
        self._transport = transport
        self._n = n
        self._buf = bytearray(n * 4)

    def fill(self, r, g, b, w=0):
        """Fill every pixel with one colour and send to the strip immediately.

        Clamps each channel to [0, 255]. Stores G, R, B, W in the internal
        buffer (GRBW wire order) then transmits. The white channel defaults to 0,
        allowing RGB-only usage.

        Args:
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
            w: White channel (0–255, default 0).
        """
        r = max(0, min(255, int(r)))
        g = max(0, min(255, int(g)))
        b = max(0, min(255, int(b)))
        w = max(0, min(255, int(w)))
        for i in range(self._n):
            self._buf[i * 4]     = g
            self._buf[i * 4 + 1] = r
            self._buf[i * 4 + 2] = b
            self._buf[i * 4 + 3] = w
        self._transport.write(bytes(self._buf) + _RESET_BYTES)

    def off(self):
        """Turn off all pixels (fill with black and send).

        Equivalent to fill(0, 0, 0, 0).
        """
        self.fill(0, 0, 0, 0)


class SK6812RGBWFull(SK6812RGBWMinimal):
    """SK6812RGBW full interface — extends SK6812RGBWMinimal with per-pixel control.

    Adds individual pixel addressing, explicit show(), global brightness
    scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
    to update the buffer, then show() to transmit; or use the inherited
    fill() for an immediate all-same-colour update.

    The white channel defaults to 0 in all set methods, allowing RGB-only
    usage alongside explicit RGBW addressing.

    Args:
        transport: Configured NeoPixel transport.
        n: Number of pixels in the strip.
    """

    def __init__(self, transport, n):
        """Initialise SK6812RGBWFull with a transport and pixel count.

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

    def set_pixel(self, index, r, g, b, w=0):
        """Set one pixel in the buffer without sending.

        Clamps index to [0, n-1] and each channel to [0, 255].
        Call show() to transmit. White channel defaults to 0.

        Args:
            index: Zero-based pixel index.
            r: Red channel (0–255).
            g: Green channel (0–255).
            b: Blue channel (0–255).
            w: White channel (0–255, default 0).
        """
        index = max(0, min(self._n - 1, int(index)))
        self._buf[index * 4]     = max(0, min(255, int(g)))
        self._buf[index * 4 + 1] = max(0, min(255, int(r)))
        self._buf[index * 4 + 2] = max(0, min(255, int(b)))
        self._buf[index * 4 + 3] = max(0, min(255, int(w)))

    def set_pixels(self, colors):
        """Write a sequence of (r, g, b, w) tuples into the buffer starting at pixel 0.

        Extra entries beyond the strip length are ignored. Call show() to transmit.
        White channel defaults to 0 if tuples have only 3 elements.

        Args:
            colors: Iterable of (r, g, b) or (r, g, b, w) tuples (0–255 each).
        """
        for i, color in enumerate(colors):
            if i >= self._n:
                break
            r, g, b = color[0], color[1], color[2]
            w = color[3] if len(color) > 3 else 0
            self._buf[i * 4]     = max(0, min(255, int(g)))
            self._buf[i * 4 + 1] = max(0, min(255, int(r)))
            self._buf[i * 4 + 2] = max(0, min(255, int(b)))
            self._buf[i * 4 + 3] = max(0, min(255, int(w)))

    def show(self):
        """Transmit the current buffer to the strip, applying brightness scaling.

        Each channel value is scaled: sent = stored * brightness // 255.
        Appends 24 zero-bytes for the extended SK6812RGBW reset (≥80 µs).
        """
        bri = self._brightness
        if bri == 255:
            self._transport.write(bytes(self._buf) + _RESET_BYTES)
        else:
            scaled = bytearray(len(self._buf))
            for i, v in enumerate(self._buf):
                scaled[i] = v * bri // 255
            self._transport.write(bytes(scaled) + _RESET_BYTES)

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

        Converts HSV to RGB (white=0) then calls fill().

        Args:
            h: Hue (0.0–1.0).
            s: Saturation (0.0–1.0).
            v: Value/brightness (0.0–1.0).
        """
        r, g, b = _hsv_to_rgb(h, s, v)
        self.fill(r, g, b, 0)
