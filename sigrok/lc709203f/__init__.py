"""
LC709203F 1-cell Li-ion fuel gauge sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every write-word and read-word (command name plus the
16-bit value decoded per register: voltage mV, RSOC %, ITE, temperature, power mode,
thermistor / I2C mode, battery profile, alarm thresholds, 0xAA55 init commands) and verifies
the CRC-8 (poly 0x07, init 0x00, over the address bytes 0x16/0x17 as well), flagging a warning
on a mismatch or a missing CRC byte. Unknown command codes are flagged. No timing annotation
pair is emitted (no bus-decodable completion events).

Supported address: 0x0B.
"""

from .pd import Decoder
