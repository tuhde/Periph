"""
PCF8578 row/column LCD dot-matrix driver sigrok protocol decoder.

Stacks on the i2c decoder. The PCF8578 uses a command-stream protocol with no
register map: each I2C write transaction contains one or more command bytes
(bit 7 = C, the continuation flag) followed by display data bytes; a read
transaction returns display RAM from the current data pointer.

The decoder annotates:
  - set-mode        (0x40 | T E1 E0 M1 M0) -> row/mixed mode, display status, multiplex rate
  - set-start-bank  (0x7C | B1 B0)         -> RAM bank shown at the top
  - device-select   (0x60 | A3..A0)        -> subaddress counter 0-15
  - RAM-access      (0x70 | G1 G0 Y1 Y0)   -> access mode and RAM bank
  - load-X-address  (0x00 | X5..X0)        -> RAM column 0-39
  - display data written/read, each byte with its RAM position (subaddress, X,
    bank) following the current access mode's auto-increment, and as an
    8-pixel column bitmap (bit 0 = top row)
  - warnings: data past column 39, data while in row mode, a transaction whose
    commands never clear C, an out-of-range load-X address
  - timing row: poweron_start (capture start) and poweron_ready (first
    transaction after power-on), checked by conformance/display/pcf8578_conformance.py

Supports both I2C addresses: 0x3C (SA0 = VSS) and 0x3D (SA0 = VDD).
"""

from .pd import Decoder
