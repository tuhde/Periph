"""
DRV2605 haptic driver for ERM and LRA actuators (I2C) sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes the device address 0x5A and the TI
haptic broadcast address 0x58 (writes only). The chip uses a plain register
pointer with auto-increment: a write is the register address byte followed by
one or more data bytes (the eight sequencer slots are written in one
transaction), a read is a register-address write, repeated START and one or
more data bytes.

Every register in the Register Map is annotated by name. STATUS reads go on
the status row (DEVICE_ID, DIAG_RESULT, FB_STS, OVER_TEMP, OC_DETECT) and are
checked against DEVICE_ID = 3. MODE (DEV_RESET, STANDBY and the MODE name),
LIBRARY_SELECTION, the eight WAV_FRM_SEQ slots (effect id or wait time), GO,
RTP_INPUT (percentage of full scale), ODT/SPT/SNT/BRT (ms), ATH_* (V / %),
RATED_VOLTAGE / OD_CLAMP (volts at the last-seen actuator type),
FEEDBACK_CONTROL, CONTROL1-CONTROL4, VBAT (V) and LRA_PERIOD (us / Hz) are
decoded on the data row. Warnings flag a wrong DEVICE_ID, accesses to
undocumented registers and a reserved library or mode value.

Also emits the calibration timing-marker pair used by
conformance/motor/drv2605_conformance.py (see specs/motor/drv2605.md,
"Sigrok Decoder" and "Timing Constraints", and
specs/motor/drv2605_timing.conf): cal_start on the write of 1 to GO (0x0C)
while the last-written MODE is 7 (auto-calibration), cal_done on the first
read of GO returning 0.
"""

from .pd import Decoder
