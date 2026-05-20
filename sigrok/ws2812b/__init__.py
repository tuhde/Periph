"""
WS2812B addressable RGB LED sigrok protocol decoder.

Stacks on the neopixel transport decoder (which handles all NZR timing).
Groups the incoming byte stream into 24-bit pixels (3 bytes each) in the
chip's wire order (GRB) and presents them in user-friendly RGB order with
a #RRGGBB hex colour annotation.

Stack: logic → neopixel → ws2812b
"""

from .pd import Decoder
