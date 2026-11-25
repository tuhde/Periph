"""
DS3502 7-bit nonvolatile high-voltage I2C digital potentiometer sigrok protocol decoder.

Stacks on the i2c decoder. Matches the addresses 0x28-0x2B (A1, A0). A write
is a memory address (0x00 = wiper / initial value register, 0x02 = control
register) followed by data. Writes to 0x00 are shown as the wiper code with
code/127 as a ratio and, based on the last CR write seen (MODE = 0 after
power-up until the first CR write), annotated "WR+IVR (EEPROM write follows
STOP, tW 10-20 ms)" or "WR only"; writes to 0x02 are shown as CR with the
decoded MODE; reads of 0x00 / 0x02 are shown as the returned WR / CR value (a
read uses the address loaded by the preceding write, with or without a repeated
START). A transaction whose address byte is NACKed right after a MODE = 0 write
is annotated "busy (EEPROM write)".

Warnings: data byte with bit 7 set, writes to addresses other than 0x00 / 0x02,
and a write to 0x00 while MODE = 0 (EEPROM wear notice). No timing annotation
pair is emitted, since no constraint is conformance-checked.
"""

from .pd import Decoder
