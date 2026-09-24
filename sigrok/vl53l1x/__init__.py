"""
VL53L1X long-distance Time-of-Flight ranging sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches address 0x29 by default (option
`address` for a sensor moved with I2C_SLAVE__DEVICE_ADDRESS). Registers are
addressed with a 16-bit index (MSB first) that auto-increments across
multi-byte accesses; 16/32-bit registers are big-endian. The decoder
assembles the two index bytes of each write transaction and follows
auto-increment, so result-block bursts are split into named fields. It does
not share code with the vl53l0x decoder (different index width and register
map).

Annotates, on the data row, named register reads/writes: SYSTEM__MODE_START
(stop / single-shot / timed), the interrupt-config source name, thresholds in
mm, the timing-budget A/B registers decoded to ms and distance mode, the
distance-mode register set (short/long), the inter-measurement period (raw
ticks), the signal-rate limit in MCPS, the sigma threshold in mm, ROI size
and centre, the offset in mm, crosstalk in MCPS, I2C_SLAVE__DEVICE_ADDRESS
changes, and result-block reads decoded to distance mm, range-status name,
signal/ambient MCPS and SPAD count. The status row carries
GPIO__TIO_HV_STATUS reads (data ready yes/no), SYSTEM__INTERRUPT_CLEAR
writes, FIRMWARE__SYSTEM_STATUS reads and the model/module/revision ID reads.
The config row summarises the 91-byte default-configuration write run
(0x002D-0x0087) as one annotation. Warns on a sensor ID other than 0xEACC, a
single-byte register index (VL53L0X-style access), a ranging start without
an interrupt clear since the last result, and GPIO_HV_MUX__CTRL bits 3:0
other than 0x1.

Also emits the single_ranging timing-marker pair used by
conformance/tof/vl53l1x_conformance.py (see specs/tof/vl53l1x.md, "Sigrok
Decoder" and "Timing Constraints", and specs/tof/vl53l1x_timing.conf):
single_ranging_start at the SYSTEM__MODE_START (0x0087) = 0x10 write,
single_ranging_done at the STOP of the next read transaction starting at
index 0x0089.
"""

from .pd import Decoder
