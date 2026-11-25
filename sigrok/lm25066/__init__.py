"""
LM25066 hot-swap controller / PMBus power monitor sigrok protocol decoder.

Stacks on the i2c decoder; matches the addresses 0x10-0x17, 0x40-0x47 and 0x50-0x5A. Annotates
every PMBus command by name (send byte, write byte/word, read byte/word via repeated START, block
reads with the count byte) and decodes the payloads: READ_VIN/VOUT/IIN/PIN/AVG_* as raw counts and
- with the default coefficients (RS = 1 mohm, CL = GND; the decoder cannot see the pins) - as
V/A/W, temperature in degrees Celsius, VAUX in V, STATUS_* bit names, READ_DIAGNOSTIC_WORD and
ALERT_MASK flag names, DEVICE_SETUP fields, SAMPLES_FOR_AVG as 2^n, OPERATION as ON/OFF, the
three block-read layouts field by field and the manufacturer ID/model/revision ASCII. Warns on
writes to read-only commands, on a block-read count byte that differs from the command's fixed
length, on writes of OPERATION 0x00 and of GATE_MASK (control operations) and on unknown command
codes. No timing annotation pair is emitted (no bus-decodable completion events).
"""

from .pd import Decoder
