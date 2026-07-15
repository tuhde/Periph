"""
APDS-9960 digital proximity, ambient light, RGB and gesture sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes ENABLE bits (PON, AEN, PEN, WEN, GEN, AIEN, PIEN),
ATIME to integration time and max count, CONTROL to AGAIN/PGAIN/LDRIVE, STATUS flags
(AVALID, PVALID, GINT, AINT, PINT, PGSAT, CPSAT), RGBC burst reads (8 bytes from 0x94),
PDATA proximity count, GFLVL dataset count, GFIFO burst reads (U,D,L,R per dataset),
PPULSE/GPULSE pulse count and length, CONFIG1.WLONG wait multiplier, and special
address-only clear transactions (PICLEAR, CICLEAR, AICLEAR).

Supported address: 0x39 (fixed).
"""

from .pd import Decoder
