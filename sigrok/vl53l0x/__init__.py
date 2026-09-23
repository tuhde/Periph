"""
VL53L0X Time-of-Flight laser-ranging sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches address 0x29 by default (option
`address` for a sensor moved with I2C_SLAVE_DEVICE_ADDRESS). Registers are
addressed with an 8-bit index that auto-increments across multi-byte
accesses; 16/32-bit registers are big-endian. The decoder tracks the index,
the page-select register 0xFF and the 0x80 power-force state, so page-1/NVM
accesses are labelled as private-bank accesses rather than as page-0
registers (in particular 0x00 written with 0xFF = 0x01 is not
SYSRANGE_START).

Annotates, on the data row, named page-0 register reads/writes:
SYSRANGE_START mode (single/back-to-back/timed/stop, VHV flag),
SYSTEM_SEQUENCE_CONFIG step flags, the interrupt-config source name,
thresholds in mm, VCSEL periods in PCLKs and step timeouts in MCLKs, the
signal-rate limit in MCPS, the offset in mm, crosstalk in MCPS,
I2C_SLAVE_DEVICE_ADDRESS changes, and result-block reads decoded to distance
mm, range-status name and signal/ambient rate MCPS. The status row carries
RESULT_INTERRUPT_STATUS reads, SYSTEM_INTERRUPT_CLEAR writes and the
model/revision ID reads; the private row summarises each 0x80/0xFF
private-bank sequence (stop variable, SPAD info, tuning table) in one
annotation. Warns on a model ID other than 0xEE, a ranging start without a
preceding stop-variable (0x91) write since the last start/stop, and a
continuous-ranging start value (0x02/0x04) written to 0x00 while the
transaction is still on a private page (0xFF != 0) or the 0x80 power-force
bank.

Also emits the single_ranging timing-marker pair used by
conformance/tof/vl53l0x_conformance.py (see specs/tof/vl53l0x.md, "Sigrok
Decoder" and "Timing Constraints", and specs/tof/vl53l0x_timing.conf):
single_ranging_start at a page-0 SYSRANGE_START = 0x01 write while the last
SYSTEM_SEQUENCE_CONFIG write was not 0x01/0x02 (i.e. not a reference
calibration) and no continuous ranging is running, single_ranging_done at
the STOP of the next read transaction starting at register 0x14.
"""

from .pd import Decoder
