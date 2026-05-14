from machine import Pin
from periph.transport.hx711_micropython import HX711Transport
from periph.chips.adc_dac.hx711 import HX711Minimal

dout   = Pin(5, Pin.IN)                         # DOUT input pin from HX711
pd_sck = Pin(6, Pin.OUT)                        # PD_SCK clock / power-down output pin
transport = HX711Transport(dout, pd_sck)        # Create HX711 transport, (dout, pd_sck)
chip = HX711Minimal(transport)                  # Create HX711 driver — discards first conversion, (transport)

ready = chip.is_ready()                         # Check if conversion is ready (non-blocking), () → bool
raw = chip.read_raw()                           # Read signed 24-bit ADC value, () → int
print(raw)
