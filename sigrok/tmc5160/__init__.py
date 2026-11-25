"""
TMC5160 stepper controller/driver sigrok protocol decoder.

Stacks on either the spi decoder (Mode 3, 8 bits per word, MSB first) or the
uart decoder (8N1, single-wire half-duplex capture). The transport is detected
from the stacked decoder's output: the spi decoder emits (ptype, (mosi, miso))
pairs, the uart decoder (ptype, rxtx, (byte, ok)) triples.

SPI: every CSN low period carries one 40-bit datagram, an address byte (bit 7 =
write) plus four data bytes on MOSI and the previous datagram's answer on MISO
(SPI_STATUS byte followed by four data bytes). The decoder annotates the host
request (R/W bit, register name, 32-bit value), the chip reply -- "reply to
<register> read" or "mirror of write <value>" -- and the status byte decoded to
status_stop_r, status_stop_l, position_reached, velocity_reached, standstill,
sg2, driver_error and reset_flag.

UART: reassembles 4-byte read requests, 8-byte write datagrams and 8-byte read
replies (skipping the host echo), checks the sync nibble and the CRC8, and
shows the node address (0xFF for replies).

Both: names every register of the register map and decodes bit fields for
GCONF, GSTAT, IOIN (incl. VERSION), SLAVECONF, SHORT_CONF, DRV_CONF,
GLOBALSCALER, IHOLD_IRUN, RAMPMODE, SW_MODE, RAMP_STAT, ENCMODE, ENC_STATUS,
COOLCONF (signed sgt), CHOPCONF (microstep count from MRES), DRV_STATUS and
PWMCONF, plus computed values (current scale fraction, signed XACTUAL /
XTARGET / VACTUAL, signed MSCURACT, SG_RESULT).

Also emits the conformance timing-marker pairs (see specs/motor/tmc5160.md,
"Sigrok Decoder" and "Timing Constraints", and specs/motor/tmc5160_timing.conf):
datagram_start / datagram_end on SPI (CSN falling / rising edge) and
read_request_end / read_reply_start on UART. OUTPUT_PYTHON carries
('REG_WRITE' | 'REG_READ', (register, value)) tuples; on SPI a read is reported
when its answer arrives in the following datagram.
"""

from .pd import Decoder

__all__ = ['Decoder']
