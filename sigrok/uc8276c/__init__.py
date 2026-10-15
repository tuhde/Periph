"""
UC8276C (UltraChip black/white/red e-paper controller) sigrok protocol decoder.

Decodes the command stream of a UC8276C panel controller, as used on the
Waveshare 4.2inch e-Paper (B). It stacks on the `display_spi` connection decoder,
which frames the SPI (4-wire or 3-wire), applies the BUSY polarity and reports
CS#, RES# and BUSY; wire it with `-P display_spi:clk=..:mosi=..:cs=..:dc=..,uc8276c`
(BUSY_N is active low: the polarity is built into this decoder, the connection
decoder reports the raw level). This decoder has no channels or options of its own.

Consumes `display_spi` packets BYTE / CS / BUSY / RESET / IDLE. After a read
command the response byte is taken from the connection decoder's DOUT value when
that channel is wired, otherwise from the (shared) DIN line.

Annotations:
  - Data:     one annotation per command with decoded parameters (resolution,
              power settings, PLL frame rate, partial window, voltages in
              volts, ...). Read commands (FLG, TSC, REV, LPD, VV, ROTP, ...)
              also show the decoded response bytes
  - Status:   `Busy for <duration>` spans (BUSY_N low) and RES# low spans
  - Warnings: BUSY_N not already low at the CS# rising edge that completes a
              command which must drive it (NOBUSY for commands both datasheets
              confirm, NOBUSY? for chip-datasheet-only ones; skipped when BUSY
              is not probed), unknown command, wrong parameter count, command
              sent while BUSY_N is low (other than FLG 0x71, REV 0x70, VV 0x81), FLG within
              200 us of BUSY_N falling, pixel write larger than the frame, bad
              deep-sleep / auto-sequence check code

Register layouts follow the panel datasheet where it differs from the chip
datasheet (PWR, PLL, VCOM_DC/VV, LPSEL); a PWR write that does not fit the
panel layout is also shown in the chip-datasheet reading. AUTO and the LUT
commands exist only in the chip datasheet.

Read data: SDA is bidirectional. The bytes of a read response are the chip's
output only when the probe sits on the shared DIN/DOUT line of a half-duplex
wiring; on a write-only board they are meaningless. In 4-wire mode every read
byte is its own CS# window.

BUSY_N is low-active. CS# may be released between a command and its parameters
(the command is processed on the stop condition); no warning is raised for that.
Pending commands are reported on the next command or on the connection decoder's
IDLE packet (10 ms of bus idle).

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (command_byte, parameter_bytes))
"""

from .pd import Decoder
