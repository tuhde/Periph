from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx710a import HX710AMinimal

connection = HX711Connection(5, 6)        # Create HX711 transport connection, (dout, pd_sck)
chip = HX710AMinimal(connection)                 # Create HX710A driver — discards first conversion, (connection)

ready = chip.is_ready()                         # Check if conversion is ready (non-blocking), () → bool
raw = chip.read_raw()                           # Read signed 24-bit differential-input value, () → int
print(raw)
