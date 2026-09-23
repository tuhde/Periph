"""
TMP117 ±0.1°C high-accuracy digital temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x48-0x4B (the four
ADD0 strap positions). Registers are 16-bit big-endian behind a
non-incrementing Register Pointer: the decoder remembers the last pointer
written so a later pointer-less read is attributed correctly.

Annotates, on the data row, TEMP_RESULT / THIGH_LIMIT / TLOW_LIMIT /
TEMP_OFFSET reads/writes decoded to °C (0.0078125 °C per LSB), EEPROM1 /
EEPROM2 / EEPROM3 as raw hex, and DEVICE_ID; on the status row,
CONFIGURATION decoded to HIGH_Alert/LOW_Alert/Data_Ready/EEPROM_Busy/MOD/
CONV/AVG/T-nA/POL/DR-Alert/Soft_Reset, and EEPROM_UL decoded to EUN/
EEPROM_Busy. Warns on a write to EEPROM1/EEPROM3 while the EEPROM is
unlocked (destroys the factory NIST-traceability unique ID), access to an
undefined pointer (0x09-0x0E, 0x10+), writes to read-only registers, and
unexpected data lengths.

Also emits the eeprom_write_ready timing-marker pair used by
conformance/temperature/tmp117_conformance.py (see
specs/temperature/tmp117.md, "Sigrok Decoder" and "Timing Constraints", and
specs/temperature/tmp117_timing.conf): eeprom_write_ready_start at a write
to an EEPROM-backed register while EUN=1, eeprom_write_ready_done at the
first subsequent read showing EEPROM_Busy=0 (EEPROM_UL bit 14 or
CONFIGURATION bit 12).
"""

from .pd import Decoder
