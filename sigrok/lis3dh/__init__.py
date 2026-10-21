"""
LIS3DH sigrok protocol decoder.

Decodes I²C transactions on the STMicroelectronics LIS3DH 3-axis MEMS
accelerometer (slave addresses 0x18 with SA0 = GND and 0x19 with SA0 =
VDD_IO). The decoder sits on top of the sigrok `i2c` decoder and follows
the chip's register-pointer framing: an 8-bit sub-address (7 register
address bits plus an MSb that enables address auto-increment), a repeated
START and the read phase.

Annotated content: every register of the chip's register map by name with
its decoded bit fields (CTRL_REG0..CTRL_REG6, TEMP_CFG_REG, STATUS_REG,
STATUS_REG_AUX, FIFO_CTRL_REG, FIFO_SRC_REG, INTx_CFG, INTx_SRC,
CLICK_CFG, CLICK_SRC, CLICK_THS, ...), the human-readable values of ODR,
FS (±2/±4/±8/±16 g), the operating mode (LPen/HR: low-power 8-bit /
normal 10-bit / high-resolution 12-bit), HPM, the FIFO mode and the
self-test selection, the 0x28-0x2D output burst as signed counts and in
*g*, the auxiliary ADC channels in counts and V, the relative temperature
in °C, and a warning when a WHO_AM_I read does not return 0x33. The
`odr_start` / `odr_ready` annotation pair marks the chip's first-data-ready
timing constraint.
"""

from .pd import Decoder
