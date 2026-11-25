"""
DS1881 dual audio digital potentiometer sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes addresses 0x28-0x2F (A2 A1 A0). Each
byte of a write is decoded as a command byte: wiper 0 / wiper 1 with the
position and the attenuation in dB for the last-seen option (Option 1 assumed
until a configuration write is seen; MUTE for the mute position), or the
configuration register (V/NV, ZC, option); 11xxxxxx is flagged as reserved.
Several command bytes in one transaction are shown separately. A read is
annotated as the round-robin sequence wiper 0, wiper 1, configuration,
wiper 0, ... with the same decoding.

Warns on a write to another address, a position beyond the configured
option's range, configuration bits 5:3 set, and reserved command bytes.

Timing row: poweron_start (capture start) and poweron_ready (first
transaction after power-on), checked by
conformance/adc_dac/ds1881_conformance.py against
specs/adc_dac/ds1881_timing.conf (>= 1 ms).
"""

from .pd import Decoder
