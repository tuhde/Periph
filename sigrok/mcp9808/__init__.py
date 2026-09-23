"""
MCP9808 ±0.5°C maximum accuracy digital temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x18-0x1F (the eight
A0/A1/A2 strap combinations). Registers are 16-bit big-endian (RESOLUTION is
8-bit) behind a non-incrementing Register Pointer: the decoder remembers the
last pointer written so a later pointer-less read is attributed correctly.

Annotates, on the data row, TUPPER/TLOWER/TCRIT reads/writes decoded to °C
(0.25 °C steps), TA reads decoded to °C (1/16 °C), RESOLUTION decoded to its
°C step, and MANUFACTURER_ID / DEVICE_ID_REV; on the status row, CONFIG
reads/writes decoded to THYST/SHDN/CRIT_LOCK/WIN_LOCK/INT_CLEAR/ALERT_STAT/
ALERT_CNT/ALERT_SEL/ALERT_POL/ALERT_MOD, and TA's three live boundary flags.
Warns on a write to the reserved pointer 0x00 or an undefined pointer 0x09+,
a write that would alter a locked register or locked CONFIG bit (lock state
tracked from observed CONFIG traffic), and unexpected data lengths.

Also emits the register_access timing-marker pair used by
conformance/temperature/mcp9808_conformance.py (see
specs/temperature/mcp9808.md, "Sigrok Decoder" and "Timing Constraints", and
specs/temperature/mcp9808_timing.conf): register_access_start on the START of
any transaction addressed to the MCP9808, register_access_done on its STOP.
"""

from .pd import Decoder
