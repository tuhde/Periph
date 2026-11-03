"""
SSD1331 (96 x 64 RGB OLED controller) sigrok protocol decoder.

Decodes the command and pixel stream of an SSD1331, as used on the Waveshare 0.95inch
RGB OLED. It stacks on the `display_spi` connection decoder, which frames the SPI,
D/C# and RES#; wire it with `-P display_spi:clk=..:mosi=..:cs=..:dc=..:res=..,ssd1331`.
The chip has no BUSY output and no read-back.

The SSD1331 sends command *parameters* with D/C# low as well, so a parameter is told
from the next command only by the count the command expects; only D/C# high is pixel
data. The decoder knows the parameter count of every command it annotates.

Annotations:
  - Data:     one annotation per command with decoded parameters (addresses, contrast,
              remap and colour depth, clocks, graphics-accelerator operands) and one per
              run of pixel data (bytes, pixels, window, dominant colours)
  - Status:   RES# low spans
  - Warnings: unknown command, command with too few parameter bytes, address window
              outside 96 x 64 or start > end, graphics operands outside the panel, pixel
              data that is not a whole number of pixels or larger than the window

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (command_byte, parameter_bytes)) and ('PIXELS', data_bytes)
"""

from .pd import Decoder
