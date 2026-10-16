"""
ST7036 (character LCD controller/driver) sigrok protocol decoder.

Decodes the instruction and data stream of a Sitronix ST7036, as used on the Electronic
Assembly / Display Visions DOG series (EA DOGM081 / DOGM162 / DOGM163) in 4-wire serial
mode. It stacks on the `display_spi` connection decoder, which frames the SPI, RS (= C/D,
probed as D/C#) and RESET; wire it with
`-P display_spi:clk=..:mosi=..:cs=..:dc=..:res=..,st7036`. The chip has no BUSY output and
no read-back in serial mode, so the host must wait fixed times: the decoder checks them.

Every instruction is a single byte sent with RS low; which instruction a byte is depends on
the table selected by Function Set (IS[2:1]) and the decoder tracks it. Data bytes (RS high)
go to DDRAM, CGRAM or ICON RAM depending on the last address instruction; runs of data bytes
are shown as text (with the DDRAM address, row and column) or as 5x8 glyph patterns.

Options: `lines` (1, 2 or 3; default 2) and `columns` (default 16) select the DDRAM layout
used to place text and to flag writes outside the visible window.

Annotations:
  - Data:     one annotation per instruction with decoded fields (function set, contrast,
              follower, bias, entry mode, display control, ...) and one per run of data
              bytes (text, CGRAM glyphs, ICON RAM)
  - Status:   RESET low spans
  - Warnings: byte sent less than 26.3 us after the previous one, Clear / Return Home
              followed by a byte within 1.08 ms, first byte within 40 ms of RESET or of
              the capture start after a RESET, extension instruction while the matching
              table is not selected, reserved instruction, DDRAM write outside the
              visible window

Outputs OUTPUT_PYTHON packets:
  ('INSTR', (byte, text)) and ('DATA', (ram, address, data_bytes))
"""

from .pd import Decoder
