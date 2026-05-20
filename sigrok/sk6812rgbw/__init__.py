"""
SK6812RGBW addressable RGBW LED sigrok protocol decoder.

Stacks on the neopixel transport decoder (which handles all NZR timing).
Groups the incoming byte stream into 32-bit pixels (4 bytes each) in the
chip's wire order (GRBW) and presents them in user-friendly RGBW order with
a #RRGGBBWW hex colour annotation.

The SK6812RGBW requires a ≥80 µs reset pulse rather than the WS2812B's
≥50 µs. Set the neopixel decoder's reset_us option to 80 when capturing
SK6812RGBW data.

Stack: logic → neopixel (reset_us=80) → sk6812rgbw
"""

from .pd import Decoder
