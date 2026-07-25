from periph.transport.hx711_auto import HX711Transport
from periph.chips.adc_dac.hx711 import HX711Minimal

transport = HX711Transport(5, 6)        # Create HX711 transport, (dout, pd_sck)
chip = HX711Minimal(transport)                  # Create HX711 driver — discards first conversion, (transport)

ready = chip.is_ready()                         # Check if conversion is ready (non-blocking), () → bool
raw = chip.read_raw()                           # Read signed 24-bit ADC value, () → int
print(raw)
