"""
SSD1683 (Solomon Systech red/black/white e-paper controller) sigrok protocol decoder.

Decodes the SPI command stream of an SSD1683 panel controller, as used on the
Waveshare 4.2inch e-Paper (B) V2. It stacks on the `display_spi` connection
decoder, which frames the SPI (4-wire or 3-wire) and reports CS#, RES# and the
raw BUSY level; wire it with
`-P display_spi:clk=..:mosi=..:cs=..:dc=..:busy=..:res=..,ssd1683`.
The BUSY polarity is built into this decoder (SSD1683 BUSY is active high); the
decoder has no channels of its own. Option: `warn_cs_split` warns when CS# is
released between a command and its parameters.

Consumes `display_spi` packets BYTE / CS / BUSY / RESET / IDLE. After a read
command (0x1B, 0x27, 0x2D, 0x2E, 0x2F, 0x35) the response byte is taken from the
connection decoder's DOUT value when that channel is wired, otherwise from the
(shared) DIN line; it is meaningful only with half-duplex wiring.

Annotations:
  - Data:     one annotation per command with decoded parameters (window,
              gate lines, voltages in volts, update sequence name, ...)
  - Status:   `Busy for <duration>` spans (BUSY high) and RES# low spans
  - Warnings: BUSY not already high at the CS# rising edge that completes a
              command which must drive it (NOBUSY; the datasheet lists 0x08, 0x10, 0x12, 0x14-0x17, 0x1C, 0x20, 0x28, 0x2A,
              0x30, 0x31, 0x34, 0x36, 0x46, 0x47), BUSY dropping in deep sleep
              (BUSY-DROP), unknown command, wrong parameter count, command sent
              while BUSY is high, RAM write larger than the window, CS# split

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (command_byte, parameter_bytes))
"""

from .pd import Decoder
