"""
LTC4151 7-80 V high-side current and voltage monitor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes the 12-bit SENSE (20 uV/LSB), VIN (25 mV/LSB) and
ADIN (0.5 mV/LSB) results (with a current estimate for a 20 mohm sense resistor) and the
CONTROL register (SNAPSHOT / CHANNEL / TEST / PAGE_RW / STUCK_BUS), including block reads
that span several registers. Warns when test mode is enabled or a data register is written.
Emits the conversion_start / conversion_done timing annotations for snapshot conversions.

Supported addresses: 0x67-0x6F (ADR1/ADR0 pins) plus the 0x66 mass-write address.
"""

from .pd import Decoder
