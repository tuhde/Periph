"""
MCP4XXX (MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X) I2C digital
potentiometer sigrok protocol decoder.

Stacks on the i2c decoder. Decodes the command byte (memory address, operation
write / increment / decrement / read, D9:D8) and the data bytes: wiper writes
as the 9-bit code with its fraction of RAB, EEPROM wiper and general-purpose
EEPROM writes, TCON writes with the decoded GCEN / R1HW..R0B bits, runs of
increment / decrement commands, the high-voltage WiperLock / write-protect
commands, and reads (using the pointer of the preceding command), including the
STATUS register. I2C General Call (address 0x00) commands are annotated as such.
The part cannot be identified from the bus, so the options select the resolution
(7 or 8 bit), the number of networks and whether the part has EEPROM; warnings
flag reserved or invalid command combinations, wiper values above full scale,
wiper 1 on a single-network part, EEPROM access on a volatile part, continuous
writes to non-volatile memory and a NACK followed by traffic without a START. No
timing annotation pair is emitted (no conformance-checked constraint).

Supported addresses: 0x28-0x2F and 0x58-0x5B (plus General Call 0x00).
"""

from .pd import Decoder
