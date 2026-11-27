"""
RRH46410 indoor air quality gas sensor module sigrok protocol decoder.

Stacks on the i2c decoder and matches the fixed address 0x38. The RRH46410 has no register map;
the decoder follows the request/response command protocol of specs/gas/rrh46410.md:

  - write transactions as requests: the command name (Get Product ID, Get Tracking Number,
    Get/Set Operation Mode, Set Humidity, Get Measurement Results, Config/Get/Set/Clear GPIO,
    Read Debug Data, flash shadow / flash commands, Reset), the decoded parameters (operation
    mode name, humidity in %RH, GPIO masks) and checksum validity
  - read transactions as responses: the status byte decoded bit by bit (invalid command, request
    checksum, sensor NACK, not complete, mode error code with 0xA = sensor damaged), the decoded
    parameters (product ID and firmware version, operation mode, GPIO levels and Get Measurement
    Results as IAQ, TVOC, ethanol equivalent, eCO2 and relative IAQ using the mode last seen,
    or TVOC and ethanol in PBAQ mode) and checksum validity

Warnings are raised for request and response checksum errors, a read without a preceding
request, and error bits in the status byte (a "not complete" result before the first sample is
annotated as a warning too). The RRH46410 has no bus-checked timing constraint, so no
conformance annotation pair is emitted (see specs/gas/rrh46410_timing.conf). OUTPUT_PYTHON emits
('REQUEST', (command, params)), ('RESPONSE', (command, status, params)) and ('RESET', None).
"""

from .pd import Decoder
