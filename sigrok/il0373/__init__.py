"""
IL0373 (black/white e-paper controller) sigrok protocol decoder.

Decodes the command stream of an IL0373 panel controller, as used on the
Waveshare 2.13inch e-Paper (D). It stacks on the `display_spi` connection decoder,
which frames the SPI (4-wire or 3-wire), applies the BUSY polarity and reports
CS#, RES# and BUSY; wire it with `-P display_spi:clk=..:mosi=..:cs=..:dc=..,il0373`
(BUSY is active low: the polarity is built into this decoder, the connection
decoder reports the raw level). This decoder has no channels or options of its own.

Annotations:
  - Data:     one annotation per command with decoded parameters (resolution,
              power settings in volts, PLL frame rate, partial window, CDI
              fields, ...). Read commands (FLG, TSC, REV, LPD, VV, ROTP) also
              show the decoded response bytes
  - Status:   `Busy for <duration>` spans (BUSY low) and RES# low spans
  - Warnings: BUSY not already low at the CS# rising edge that completes DRF /
              POF / TSC / APG, unknown command, wrong parameter count, command
              sent while a refresh is running (DRF / POF / DSLP issued, BUSY not yet
              high again; FLG 0x71, REV 0x70 and VV 0x81 are allowed),
              the first FLG within 200 us of BUSY falling, pixel write larger
              than the frame, bad deep-sleep check code

Register layouts follow the panel datasheet (PWR 6.4-15.0 V and VGH 17-20 V,
VDCS to -3.00 V, 3-byte TRES, 7-byte PTL). LUTs are accepted with the chip
datasheet's 44/42 or the panel datasheet's 60/36 byte counts.

Module quirk (measured on the Waveshare 2.13inch (D)): BUSY goes low at the
first command after reset (the driver's PWR) and does not give a clean "ready"
after PON; it stays low or toggles until the first refresh, which
is why PON is not in the list of commands checked for BUSY, and why BUSY-low spans
shorter than 20 us are not annotated.

Outputs OUTPUT_PYTHON packets:
  ('COMMAND', (command_byte, parameter_bytes))
"""

from .pd import Decoder
