from ._neopixel_rgbw_base import _NeoPixelRGBWMinimal, _NeoPixelRGBWFull

_RESET_BYTES = 24


class SK6812RGBWMinimal(_NeoPixelRGBWMinimal):
    """SK6812RGBW addressable RGBW LED strip — minimal interface.

    Drives a chain of n SK6812RGBW pixels over a NeoPixel connection.
    Maintains an internal GRBW buffer; fill() writes all pixels and
    transmits immediately. Each pixel has four channels: red, green,
    blue, and white.

    Args:
        connection: Configured NeoPixel connection (MicroPython, CircuitPython, or Linux).
        n: Number of pixels in the strip.
    """

    def __init__(self, connection, n):
        """Initialise SK6812RGBWMinimal with a connection and pixel count.

        Args:
            connection: Configured NeoPixel connection.
            n: Number of pixels in the strip (must be >= 1).
        """
        super().__init__(connection, n, channel_order=(1, 0, 2, 3), reset_bytes=_RESET_BYTES)


class SK6812RGBWFull(_NeoPixelRGBWFull):
    """SK6812RGBW full interface — per-pixel control, brightness, rotation, HSV fill.

    Adds individual pixel addressing, explicit show(), global brightness
    scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
    to update the buffer, then show() to transmit; or use the inherited
    fill() for an immediate all-same-colour update.

    The white channel defaults to 0 in all set methods, allowing RGB-only
    usage alongside explicit RGBW addressing.

    Args:
        connection: Configured NeoPixel connection.
        n: Number of pixels in the strip.
    """

    def __init__(self, connection, n):
        """Initialise SK6812RGBWFull with a connection and pixel count.

        Args:
            connection: Configured NeoPixel connection.
            n: Number of pixels in the strip.
        """
        super().__init__(connection, n, channel_order=(1, 0, 2, 3), reset_bytes=_RESET_BYTES)
