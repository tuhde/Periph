"""
LPS28DFW sigrok protocol decoder.

Decodes STMicroelectronics LPS28DFW dual full-scale digital barometer
I²C register transactions on top of the sigrok 'i2c' decoder. Annotates
all named registers (WHO_AM_I, CTRL_REG1, CTRL_REG2, CTRL_REG3, CTRL_REG4,
INTERRUPT_CFG, THS_P_L/H, FIFO_CTRL, FIFO_WTM, REF_P_L/H, RPDS_L/H,
STATUS, INT_SOURCE, PRESSURE_OUT_XL/L/H, TEMP_OUT_L/H, FIFO_STATUS1/2,
FIFO_DATA_OUT_PRESS_XL/L/H) and decodes configuration bit fields.
"""

from .pd import Decoder

__all__ = ['Decoder']