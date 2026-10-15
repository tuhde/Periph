"""
UC8159 (seven-colour e-paper controller) sigrok protocol decoder.

Decodes the command stream of the controller on the Waveshare 5.65inch e-Paper (F):
600 x 448 pixels, 4 bits per pixel, waveform from an external flash. It stacks on the
`display_spi` connection decoder; wire it with
`-P display_spi:clk=..:mosi=..:cs=..:dc=..:busy=..:res=..,uc8159`. BUSY is active low
(the polarity is built into this decoder).

Annotations:
  - Data:     one annotation per command with decoded parameters; DTM1 shows the byte
              count against the 134 400-byte frame and the dominant colours
  - Status:   `Busy for <duration>` spans and RES# low spans
  - Warnings: BUSY not low at the CS# rising edge of PON / POF / DRF, unknown command,
              wrong parameter count, command sent while a refresh is running, DTM1 of
              the wrong size, bad deep-sleep check code

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (command_byte, parameter_bytes))
"""

from .pd import Decoder
