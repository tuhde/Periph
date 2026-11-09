"""
TMC2130 stepper driver sigrok protocol decoder.

Stacks on the spi decoder (Mode 3, 8 bits per word, MSB first). Every CSN
low period carries one 40-bit datagram: an address byte (bit 7 = write) plus
four data bytes on MOSI, and the previous datagram's answer on MISO (reply
status byte followed by four data bytes). The decoder groups the five bytes
between CSN edges into one datagram and annotates:

- the host request: R/W bit, register name and the 32-bit value;
- the chip reply, which belongs to the *previous* datagram: "reply to
  <register> read" or "mirror of write <value>", with the status byte decoded
  to reset_flag, driver_error, sg2 and standstill;
- bit fields for GCONF, GSTAT, IOIN, IHOLD_IRUN, COOLCONF (signed sgt),
  CHOPCONF (microstep count from MRES), DRV_STATUS and PWMCONF, plus computed
  values (current scale fraction, signed XDIRECT coil currents, signed
  MSCURACT, SG_RESULT).

Also emits the datagram_start / datagram_end timing-marker pair used by
conformance/motor/tmc2130_conformance.py (see specs/motor/tmc2130.md,
"Sigrok Decoder" and "Timing Constraints", and
specs/motor/tmc2130_timing.conf): datagram_start at the CSN falling edge,
datagram_end at the rising edge. OUTPUT_PYTHON carries
('REG_WRITE' | 'REG_READ', (register, value)) tuples; a read is reported
when its answer arrives in the following datagram.
"""

from .pd import Decoder

__all__ = ['Decoder']
