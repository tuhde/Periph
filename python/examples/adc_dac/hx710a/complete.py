from periph.connection.hx711_auto import HX711Connection
from periph.chips.adc_dac.hx710a import HX710AFull

connection = HX711Connection(5, 6)        # Create HX711 transport connection, (dout, pd_sck)
chip = HX710AFull(connection)                    # Create HX710A driver — discards first conversion, (connection)

ready = chip.is_ready()                         # Check if conversion is ready (non-blocking), () → bool
                                                 # returns True when DOUT is LOW
raw = chip.read_raw()                           # Read signed 24-bit differential-input value, () → int
                                                 # blocks until DOUT goes LOW, then clocks out 24 bits

chip.set_rate(40)                               # Select differential-input output rate, (rate: 10|40) → None
                                                 # takes effect after next read; issues dummy read to apply
chip.set_rate(10)                               # (restores default 10 SPS)

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

temp_raw = chip.read_temperature_raw()          # Read raw on-chip temperature code, () → int
                                                 # uncalibrated ADC code (~20.4 LSB/°C), not a °C value
print(temp_raw)

chip.power_down()                               # Enter power-down mode, () → None
                                                 # holds PD_SCK HIGH for >60 µs
chip.power_up()                                 # Exit power-down, reset chip, discard settling conversion, () → None
                                                 # resets to differential input, gain 128, 10 SPS
