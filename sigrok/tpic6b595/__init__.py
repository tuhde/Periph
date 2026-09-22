"""
TPIC6B595 8-bit power SiPo shift register sigrok protocol decoder.

Stacks on the `sipo` transport decoder (see `specs/transport_sipo.md`).
Reverses each `LATCH` payload back into per-device order (device 0 =
nearest the controller, per `specs/io_expander/tpic6b595.md`, "Data
Conversion") and annotates, for every cascaded device, which of
DRAIN0–DRAIN7 are ON vs OFF. Annotates `CLEARED` on `CLEAR` packets.
A configurable decoder option (`num_devices`, default 1) tells it how
many bytes to expect per `LATCH`; it emits a warning annotation if a
`LATCH` payload's length is not an exact multiple of `num_devices`.
"""

from .pd import Decoder
