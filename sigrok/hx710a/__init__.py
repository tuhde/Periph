"""
HX710A 24-bit ADC sigrok protocol decoder.

Decodes the custom 2-wire bit-bang protocol (DOUT + PD_SCK) — the same
wire-level format as the HX711 — into annotated conversions. The HX710A
has a single, fixed-gain (128) differential input; unlike the HX711,
there is no channel or software gain selection, only a reading type
(differential input or on-chip temperature) and an output rate:

  - Ready: DOUT LOW period while waiting for the first clock pulse
  - Bits: each DOUT sample on a PD_SCK falling edge (bits 23-0, MSB first)
  - Conversion: full 24-bit signed value with reading type and rate label
  - Power-down: PD_SCK HIGH for >60 us
  - Wake-up: PD_SCK LOW -> DOUT LOW after a power-down

Pulse count -> reading type/rate for the NEXT conversion:
  25 pulses -> Differential input, 10 Hz
  26 pulses -> Temperature,        40 Hz
  27 pulses -> Differential input, 40 Hz

Outputs OUTPUT_PYTHON packets:
  ('CONVERSION', (signed_value, reading_type, rate))
  ('POWERDOWN',  None)
"""

from .pd import Decoder
