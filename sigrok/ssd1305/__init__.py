"""
SSD1305 (132 x 64 OLED/PLED segment/common driver with controller) sigrok protocol decoder.

Decodes the I2C traffic of a Solomon Systech SSD1305 as used on the common 2.23" 128 x 32 and 1.3" 128 x 64 OLED modules. It
stacks on the `i2c` decoder (7-bit addresses 0x3C / 0x3D, SA0 = low / high). A companion decoder
`ssd1305_spi` decodes the 4-wire SPI interface on top of `display_spi` with the same command table.

Every write is split into control bytes (Co, D/C) and payload. Command payloads are decoded into named
commands with their arguments (column nibbles combined into a column number, page, display start
line, contrast, brightness, segment re-map, normal / reverse, multiplex ratio, master configuration,
display on / off / dim, COM scan direction, display offset, clock divide / oscillator, pre-charge,
COM pins, VCOMH, addressing mode, column / page ranges, scroll setup / activate / deactivate / area,
area colour, LUT, bank colours, dim mode, read-modify-write / end); multi-byte commands consume their
data bytes. Data payloads are shown with the tracked page and column (auto-incrementing per the
active addressing mode, clamped at column 131 in page mode; each byte is 8 vertical pixels, bit 0 on
top) and a byte count. Reads are shown as the status byte (display on / off) or as RAM data with the
first byte marked as the dummy read.

Annotations:
  - Data:     one annotation per control byte, command, data-write run and read
  - Status:   display state (on / off) taken from commands and status reads
  - Warnings: command byte not in the SSD1305 table (including the SSD1306-only charge pump 0x8D),
              data write that crosses column 131 in page mode, scroll setup while scrolling is
              active, a command stream with no command bytes; addresses outside 0x3C / 0x3D are ignored

No timing annotation pair is emitted: the datasheet delays (display-on 100 ms) are
recommended waits, not measured events.

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (byte, text)), ('DATA', (page, column, data_bytes)), ('STATUS', status_byte),
  ('RAM_READ', (page, column, data_bytes))
"""

from .pd import Decoder
