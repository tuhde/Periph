from machine import Pin
from periph.transport.hx711_micropython import HX711Transport
from periph.chips.adc_dac.hx711 import HX711Full

dout   = Pin(5, Pin.IN)                         # DOUT input pin from HX711
pd_sck = Pin(6, Pin.OUT)                        # PD_SCK clock / power-down output pin
transport = HX711Transport(dout, pd_sck)        # Create HX711 transport, (dout, pd_sck)
chip = HX711Full(transport)                     # Create HX711 driver — discards first conversion, (transport)

ready = chip.is_ready()                         # Check if conversion is ready (non-blocking), () → bool
                                                 # returns True when DOUT is LOW
raw = chip.read_raw()                           # Read signed 24-bit ADC value at current gain, () → int
                                                 # blocks until DOUT goes LOW, then clocks out 24 bits

chip.set_gain(64)                               # Select channel and gain, (gain: 128|64|32) → None
                                                 # 128 → Channel A, 64 → Channel A, 32 → Channel B; issues dummy read to apply
chip.set_gain(32)                               # (gain=32 selects Channel B)
chip.set_gain(128)                              # (restores Channel A Gain 128)

avg = chip.read_average(10)                     # Average multiple raw readings, (times=10) → int
                                                 # blocks for `times` complete conversions

chip.tare(10)                                   # Capture zero offset from 10-reading average, (times=10) → None
                                                 # stores result in internal _offset; call with nothing on the scale
offset = chip.get_offset()                      # Return stored tare offset, () → int
                                                 # value captured by the last tare() call

chip.set_scale(420.0)                           # Set calibration scale factor, (factor: float) → None
                                                 # factor = (read_average() - offset) / known_weight_in_target_unit
scale = chip.get_scale()                        # Return current scale factor, () → float

weight = chip.read_weight(5)                    # Return calibrated weight, (times=1) → float
                                                 # computes (read_average(times) - offset) / scale
print(weight)

chip.power_down()                               # Enter power-down mode, () → None
                                                 # holds PD_SCK HIGH for >60 µs
chip.power_up()                                 # Exit power-down, reset chip, discard settling conversion, () → None
                                                 # resets to Channel A Gain 128; pulse count and first reading are handled internally
