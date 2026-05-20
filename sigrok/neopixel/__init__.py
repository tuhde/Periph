"""
NeoPixel NZR single-wire transport sigrok protocol decoder.

Takes a single logic channel (DIN) and decodes the WS2812B-compatible
NZR protocol into a byte stream and reset pulses:

  - bit 0: high pulse ≤ threshold (default 600 ns)
  - bit 1: high pulse > threshold
  - reset: low period ≥ reset_us option (default 50 µs)

Outputs OUTPUT_ANN for bits, bytes, and resets, and OUTPUT_PYTHON
packets that chip-level decoders (ws2812b, sk6812rgbw) stack on top of:

  ('BYTE',  byte_value)      — one decoded byte, MSB first
  ('RESET', byte_count)      — frame boundary; byte_count bytes since last reset

Chip decoders stack on this decoder; they do not need to handle timing.
"""

from .pd import Decoder
