"""
AM2315 humidity and temperature sensor (Aosong) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x5C. Annotates the Modbus-style
frames: the wake-up (an address write the sensor does not acknowledge), read
commands (function 0x03, start register, count), write commands (function 0x10,
start, count, data, CRC) and the responses (function code, byte count, data,
CRC low/high). Registers 0x00-0x03 are decoded as humidity in %RH and
temperature in degrees Celsius (sign-magnitude), 0x08-0x0E as model, version and
device id, 0x10-0x13 as the user registers. The CRC-16 (init 0xFFFF, polynomial
0xA001, low byte first, address excluded) is verified and mismatches are
flagged as warnings; response function codes with bit 7 set (0x80-0x84) are
annotated as sensor error responses.

For the conformance-checked command-to-read delay of specs/environmental/am2315.md
it emits the named annotation pair read_delay_start (at the STOP that ends a 0x03
read-command write) and read_delay_done (on the following read address byte). A
machine-readable copy of every transaction is mirrored on OUTPUT_PYTHON as
('I2C_READ' | 'I2C_WRITE', (address, bytes)).
"""

from .pd import Decoder
