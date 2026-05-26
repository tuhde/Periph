"""
HX711 24-bit ADC sigrok protocol decoder.

Decodes the custom 2-wire bit-bang protocol (DOUT + PD_SCK) used
exclusively by the HX711 into annotated conversions:

  - Ready: DOUT LOW period while waiting for the first clock pulse
  - Bits: each DOUT sample on a PD_SCK falling edge (bits 23–0, MSB first)
  - Conversion: full 24-bit signed value with channel and gain label
  - Power-down: PD_SCK HIGH for >60 µs

Pulse count → channel/gain for the NEXT conversion:
  25 pulses → Channel A, Gain 128
  26 pulses → Channel B, Gain  32
  27 pulses → Channel A, Gain  64

Outputs OUTPUT_PYTHON packets:
  ('CONVERSION', (signed_value, channel, gain))
  ('POWERDOWN',  None)
"""

from .pd import Decoder
