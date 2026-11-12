"""
SSD1306 (128 x 64 OLED/PLED segment/common driver with controller) sigrok protocol decoder.

Decodes the I2C traffic of a Solomon Systech SSD1306 as used on the common 0.96" 128 x 64 and 128 x 32 OLED
modules. It stacks on the `i2c` decoder (7-bit addresses 0x3C / 0x3D, SA0 = low / high). A companion decoder
`ssd1306_spi` decodes the 4-wire SPI interface on top of `display_spi` with the same command table.

Every write is split into control bytes (Co, D/C) and payload. Command payloads are decoded into named
commands with their arguments (column nibbles combined into a column number, page, start line, contrast,
charge pump enable, segment re-map, normal / reverse, multiplex ratio, display on / off, COM scan direction,
display offset, clock divide / oscillator, pre-charge, COM pins, VCOMH, addressing mode, column / page ranges,
scroll setup / activate / deactivate / area); multi-byte commands consume their data bytes. Data payloads are
shown with the tracked page and column (auto-incrementing per the active addressing mode, wrapping at column
127 in page mode; each byte is 8 vertical pixels, bit 0 on top) and a byte count.

Annotations:
  - Data:     one annotation per control byte, command and data-write run
  - Status:   display state (on / off) taken from commands
  - Warnings: command byte not in the SSD1306 table (including the SH1106 / SSD1305-only commands 0xAD, 0xAB,
              0xAC, 0xD8, 0xE0, 0xEE), RAM data written while a scroll is active, scroll setup while scrolling is
              active, display on (0xAF) without a preceding charge pump enable (0x8D 0x14), read attempts, a
              command stream with no command bytes; addresses outside 0x3C / 0x3D are ignored

No timing annotation pair is emitted: the datasheet delays (display-on 100 ms) are recommended waits, not
measured events.

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (byte, text)), ('DATA', (page, column, data_bytes))
"""

from .pd import Decoder
