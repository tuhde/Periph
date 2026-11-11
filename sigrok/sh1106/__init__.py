"""
SH1106 (132 x 64 OLED/PLED segment/common driver with controller) sigrok protocol decoder.

Decodes the I2C traffic of a Sino Wealth SH1106 as used on the common 1.3" 128 x 64 OLED modules. It
stacks on the `i2c` decoder (7-bit addresses 0x3C / 0x3D, SA0 = VSS / VDD1). A companion decoder
`sh1106_spi` decodes the 4-wire SPI interface on top of `display_spi` with the same command table.

Every write is split into control bytes (Co, D/C) and payload. Command payloads are decoded into named
commands with their arguments (column nibbles combined into a column number, page, display start
line, contrast, segment re-map, normal / reverse, multiplex ratio, DC-DC, display on / off, COM scan
direction, display offset, clock divide / oscillator, pre-charge / discharge, COM pins, VCOM, pump
voltage, read-modify-write / end); double-byte commands consume their argument byte. Data payloads
are shown with the tracked page and column (the column auto-increments and stops at 131; each byte
is 8 vertical pixels, bit 0 on top) and a byte count. Reads are shown as the status byte (BUSY,
ON/OFF) or as RAM data with the first byte marked as the dummy read.

Annotations:
  - Data:     one annotation per control byte, command, data-write run and read
  - Status:   display state (on / off) taken from commands and status reads
  - Warnings: command byte not in the SH1106 table (including SSD1306-only commands such as 0x20,
              0x21, 0x22 and 0x8D), data write that crosses column 131, a command stream with
              no command bytes, address outside 0x3C / 0x3D is ignored

No timing annotation pair is emitted: the datasheet delays (DC-DC 100 ms, display-on 150 ms) are
recommended waits, not measured events.

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (byte, text)), ('DATA', (page, column, data_bytes)), ('STATUS', status_byte),
  ('RAM_READ', (page, column, data_bytes))
"""

from .pd import Decoder
