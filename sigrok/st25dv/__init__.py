"""
ST25DV04K/16K/64K dynamic NFC/RFID tag sigrok protocol decoder.

Stacks on the i2c decoder. Matches the chip's two I2C addresses, both with a
16-bit (high, low) register address: 0x53 (user memory, dynamic registers and
the fast-transfer-mode mailbox at 0x2008-0x2107) and 0x57 (system
configuration area). Annotates every access with its address and name (user
memory offset, dynamic register, mailbox byte, system register), decodes the
GPO / GPO_CTRL_Dyn, IT_STS_Dyn, EH_CTRL_Dyn, MB_CTRL_Dyn and I2C_SSO_Dyn bits,
MEM_SIZE / BLK_SIZE / IC_REF / UID, IT_TIME, MB_WDG and ENDAn, and the I2C
present-password and write-password commands (0x0900 with the 0x09 / 0x07
validation code; the password bytes are masked as sensitive). ACK polling after
an EEPROM write is shown as such.

Warnings: user-memory writes crossing a 256-byte page, mailbox writes not
starting at 0x2008, a NACKed device select outside a write cycle (RF access or
low-power down), system-area writes without a preceding password presentation,
and password commands whose two copies differ or that carry a wrong length.
The spec defines no conformance-checked timing constraint, so no timing
annotation pair is emitted. A machine-readable copy is mirrored on
OUTPUT_PYTHON: ('REG_READ' | 'REG_WRITE', (address, register, bytes)),
('MAILBOX_WRITE', bytes), ('PASSWORD_PRESENT' | 'PASSWORD_WRITE', None) and
('FIELD', (name, value, text)).
"""

from .pd import Decoder
