from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx711 import HX711Minimal

connection = HX711Connection(5, 6)        # Create HX711 connection, (dout, pd_sck)
chip = HX711Minimal(connection)                  # Create HX711 driver — discards first conversion, (connection)

ready = chip.is_ready()                         # Check if conversion is ready (non-blocking), () → bool
raw = chip.read_raw()                           # Read signed 24-bit ADC value, () → int
print(raw)
