"""
HS30xx humidity and temperature sensor (Renesas HS3001 / HS3002 / HS3003) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x44. The chip has no register
pointer: an address-only write is a measurement request (MR), and a read of two
or four bytes is the data fetch (DF) whose two MSBs are a status field
(0 valid, 1 stale). The decoder annotates the measurement request, decodes data
fetches into %RH and degrees Celsius (14-bit values; 2-byte fetches carry
humidity only) and warns about stale data. It also annotates the programming
mode (enter 0xA0 / leave 0x80, each followed by two zero bytes), NVM register
reads (resolution registers 0x06 / 0x11, ID words 0x1E / 0x1F, status byte 0x81)
and NVM writes (0x46 / 0x51) with the decoded resolution bits, and warns when an
NVM write changes bits other than 11:10 of the register value last read.

No conformance timing pair is emitted: the HS3xxx datasheet gives only typical
conversion times (see specs/humidity/hs30xx_timing.conf). A machine-readable
copy of every transaction is mirrored on OUTPUT_PYTHON as ('I2C_READ' |
'I2C_WRITE', (address, bytes)) or ('REG_READ' | 'REG_WRITE', (register, bytes)).
"""

from .pd import Decoder
