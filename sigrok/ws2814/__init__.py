"""
WS2814 addressable RGBW LED sigrok protocol decoder.

Stacks on the neopixel connection decoder (which handles all NZR timing).
Groups the incoming byte stream into 32-bit pixels (4 bytes each) in the
chip's wire order (RGBW, identity — no reorder) and presents them with a
#RRGGBBWW hex colour annotation.

The WS2814 requires a >=280 us reset pulse, longer than both the WS2812B's
>=50 us and the SK6812RGBW's >=80 us. Set the neopixel decoder's reset_us
option to 280 when capturing WS2814 data.

Stack: logic -> neopixel (reset_us=280) -> ws2814
"""

from .pd import Decoder
