"""
AD5251 dual 64-position I2C digital potentiometer (with EEMEM) sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes the addresses 0x2C-0x2F. A write is an
instruction byte followed by optional data bytes. Register-access
instructions (bit 7 = 0) are annotated with the RDAC / EEMEM / tolerance
register they select; data bytes are decoded as wiper codes with their
code/64 ratio (consecutive bytes after RDAC1 go to RDAC3), user EEMEM
values or tolerance bytes. Quick commands (bit 7 = 1) are annotated by name
(NOP, store, restore, +/-1 step, +/-6 dB, reset). A read returns the register
pointed at by the last register-access instruction, the pointer advancing for
consecutive bytes. A NACK on the address phase is annotated as the EEMEM write
cycle (ACK polling).

Warns on a write to another address, a nonzero instruction bit 6 or quick
command A2, data bytes after a quick command, and a read before any register
instruction. The EEMEM times are typical values completed by ACK polling, so
no timing annotation pair is emitted (see specs/adc_dac/ad5251.md, "Sigrok
Decoder" and specs/adc_dac/ad5251_timing.conf).
"""

from .pd import Decoder
