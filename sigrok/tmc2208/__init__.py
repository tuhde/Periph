"""
TMC2208 (TMC2202 / TMC2208 / TMC2224) stepper driver sigrok protocol decoder.

Stacks on the uart decoder (8N1, any baud >= 9 kBd). The chip's single-wire
PDN_UART line carries the host datagram and the chip reply on the same wire,
so a capture of that one channel (or a merged RX/TX pair) shows both. The
decoder reassembles three datagram kinds -- 8-byte write datagrams
(0x05, node, reg|0x80, data[4], CRC8), 4-byte read requests
(0x05, node, reg, CRC8) and 8-byte read replies (0x05, 0xFF, reg, data[4],
CRC8) -- checks the sync byte and the CRC8 (a failure is flagged on the
warnings row), skips a byte-identical immediate repeat of a host datagram (the
host echo when RX and TX are merged), names the register and decodes the bit
fields of GCONF, GSTAT, IHOLD_IRUN, CHOPCONF, DRV_STATUS and PWMCONF plus
computed values (microstep count from MRES, current scale fraction, signed
VACTUAL).

Also emits the reply_delay timing-marker pair used by
conformance/motor/tmc2208_conformance.py (see specs/motor/tmc2208.md,
"Sigrok Decoder" and "Timing Constraints", and
specs/motor/tmc2208_timing.conf): read_request_end on the last byte of a read
request, read_reply_start on the first byte of the reply. OUTPUT_PYTHON carries
('REG_WRITE' | 'REG_READ', (register, value)) tuples.
"""

from .pd import Decoder
