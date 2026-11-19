"""
AS7262 6-channel visible spectral sensor (ams OSRAM) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x49. The chip exposes only three hardware
registers (STATUS 0x00, WRITE 0x01, READ 0x02); every sensor register is a *virtual register*
reached through them. This decoder reassembles the virtual-register accesses from the STATUS
polling, the WRITE address/data bytes and the READ data bytes, and annotates each access with the
virtual register name: CONTROL_SETUP (RST, INT, GAIN, BANK, DATA_RDY), INT_T (integration time in
ms), LED_CONTROL (LED enables and currents), DEVICE_TEMP (degrees Celsius), the version
registers, the six raw 16-bit channel values and the six calibrated IEEE-754 floats.

Warnings: a write while TX_VALID was set (the previous STATUS poll showed the WRITE register
occupied), a read data byte without a preceding virtual read address, and a write of data without
an address. The conformance annotation pair conversion_start (virtual write of CONTROL_SETUP with
BANK = 11, one-shot) and conversion_done (first virtual read of CONTROL_SETUP with DATA_RDY = 1)
backs the one-shot conversion time check in specs/color/as7262_timing.conf. A machine-readable
copy of every access is mirrored on OUTPUT_PYTHON as ('VREG_READ' | 'VREG_WRITE', (register, value)).
"""

from .pd import Decoder
