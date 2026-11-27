"""
PN532 NFC/RFID controller sigrok protocol decoder.

Stacks on the i2c decoder and matches the fixed address 0x24. The PN532 has no register map;
the decoder follows the frame protocol of specs/rfid/pn532.md:

  - write transactions as command frames (preamble, LEN/LCS and DCS validation, TFI 0xD4, the
    command name and decoded request fields: InListPassiveTarget MaxTg / baud / InitiatorData,
    InDataExchange MIFARE / NTAG operations (auth key A/B, READ, WRITE), RFConfiguration RF
    field and MaxRetries, SAMConfiguration, Read/WriteRegister addresses, PowerDown, Diagnose),
    and the host's ACK (abort) and NACK (resend) frames
  - read transactions as the status byte (ready / not ready) plus the ACK frame, the 6-byte
    response header the driver reads before its NACK, the response frame (response name,
    Status error text, firmware version, general status, the parsed target list with UID) or
    the application-level error frame

Warnings are raised for bad LCS / DCS checksums, a frame read without a preceding command, a
response frame without an ACK, a response code that does not match the pending command, a
non-zero Status, error frames and TFI values other than 0xD4 / 0xD5 / 0x7F. The PN532 has no
bus-checked timing constraint, so no conformance annotation pair is emitted (see
specs/rfid/pn532_timing.conf). OUTPUT_PYTHON emits ('COMMAND', (code, params)),
('RESPONSE', (code, data)), ('ACK' | 'ABORT' | 'NACK' | 'HEADER' | 'STATUS' | 'ERROR_FRAME', value).
"""

from .pd import Decoder
