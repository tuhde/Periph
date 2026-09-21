"""
AD7706 sigrok protocol decoder.

Decodes AD7706 3-channel, 16-bit sigma-delta ADC SPI transactions into
register-level reads and writes. The chip uses a two-phase protocol: every
register access is preceded by an 8-bit write to the Communication Register
that selects the target register and read/write direction, then the
selected register's data is transferred in the same CS-held transaction.
Stacks on the `spi` decoder.

The AD7706 differs from the AD7705 only in its channel-select bit pattern
— `CH1:CH0 = 11` selects the real third channel AIN3 (referenced to
COMMON) rather than a factory test mode as on the AD7705.
"""

from .pd import Decoder
