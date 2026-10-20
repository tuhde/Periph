"""
UC1611s (160 COM x 256 SEG graphic LCD controller-driver) sigrok protocol decoder.

Decodes the I2C traffic of an UltraChip UC1611s as used on the Electronic Assembly EA DOGXL240-7
(240 x 128, 1 bit per pixel). It stacks on the `i2c` decoder. The chip carries the command/data
bit in its I2C address (`0 1 1 1 A3 A2 CD`), so it answers on two consecutive 7-bit addresses,
`0x38 + 2n` (command) and `0x39 + 2n` (data) for n = 0..3; the decoder accepts 0x38..0x3F.

Writes to the command address are decoded into named commands with their fields (column and
page address nibbles combined, potentiometer, mapping MX/MY, display enable, display pattern,
window registers, System Reset ...); double- and triple-byte commands consume their argument
bytes. Writes to the data address are shown per transaction with the tracked page, column and
RAM pattern and the bytes written (each byte is 8 vertical pixels, bit 0 on top). Reads are
shown as the three status bytes (Ver, MX, MY, WA, DE, ID, PMO, product code, ESD) or as RAM
data with the first byte marked as the dummy read.

Options: `width` (columns, default 240) for the column wrap of the tracked RAM address.

Annotations:
  - Data:     one annotation per command, per data-write transaction and per read
  - Status:   display state (on / sleep) and mirror mapping, taken from commands and status reads
  - Warnings: unknown or reserved command (APC, MTP, test control, serial Get Status), missing
              argument at STOP, bytes after System Reset in the same transaction, data write past
              the last column with wrap-around off, address outside 0x38..0x3F is ignored

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (byte, text)), ('DATA', (pattern, page, column, data_bytes)),
  ('STATUS', (status1, status2, status3)), ('RAM_READ', (pattern, page, column, data_bytes))
"""

from .pd import Decoder
