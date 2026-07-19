"""
SiPo (serial-in/parallel-out shift register) sigrok protocol decoder.

Decodes the SER IN/SRCK/RCK/SRCLR/G control sequence used by cascadable
SIPO shift registers such as the TPIC6B595, SN74HC595, and SN74HCT595 into
latched output-register writes:

  - Byte: each 8-bit value shifted in on SER IN, sampled MSB-first on
    SRCK rising edges (same framing as sigrok's built-in `spi` decoder,
    but decoded directly since libsigrokdecode does not support declaring
    extra logic channels on a decoder stacked on a non-`logic` input)
  - Latch: RCK rising edge — the bytes shifted in since the previous latch,
    now driving the output register
  - Clear: SRCLR LOW pulse (clears the shift register only, not the
    output register)
  - Outputs disabled: G HIGH period (forces every output off without
    disturbing the storage register's contents)

SRCLR and G are optional channels; omit them if those pins are tied off
in hardware.

Outputs OUTPUT_PYTHON packets:
  ('LATCH', bytes)
  ('CLEAR', None)
"""

from .pd import Decoder
