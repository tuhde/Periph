from ._neopixel_rgb_base import _NeoPixelRGBMinimal, _NeoPixelRGBFull


class WS2812BMinimal(_NeoPixelRGBMinimal):
    """WS2812B addressable RGB LED strip — minimal interface.

    Drives a chain of n WS2812B pixels over a NeoPixel connection.
    Maintains an internal GRB buffer; fill() writes all pixels and
    transmits immediately.

    Args:
        connection: Configured NeoPixel connection (MicroPython, CircuitPython, or Linux).
        n: Number of pixels in the strip.
    """

    def __init__(self, connection, n):
        """Initialise WS2812BMinimal with a connection and pixel count.

        Args:
            connection: Configured NeoPixel connection.
            n: Number of pixels in the strip (must be >= 1).
        """
        super().__init__(connection, n, channel_order=(1, 0, 2), reset_bytes=0)


class WS2812BFull(_NeoPixelRGBFull):
    """WS2812B full interface — per-pixel control, brightness, rotation, HSV fill.

    Adds individual pixel addressing, explicit show(), global brightness
    scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
    to update the buffer, then show() to transmit; or use the inherited
    fill() for an immediate all-same-colour update.

    Args:
        connection: Configured NeoPixel connection.
        n: Number of pixels in the strip.
    """

    def __init__(self, connection, n):
        """Initialise WS2812BFull with a connection and pixel count.

        Args:
            connection: Configured NeoPixel connection.
            n: Number of pixels in the strip.
        """
        super().__init__(connection, n, channel_order=(1, 0, 2), reset_bytes=0)
