"""
DHTxx single-wire sigrok protocol decoder.

Decodes the host/sensor timing of the DHT11 / DHT22 single-wire protocol
on top of a logic-input source. Annotates the start signal, sensor
response, data bits, decoded byte values, and the checksum.

This is a base/transport decoder; per-chip decoders (DHT11, DHT22) can
stack on top to interpret decoded byte values.
"""

from .pd import Decoder
