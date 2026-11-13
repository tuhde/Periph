"""Complete example for the MCP3422 — exercise every method in the public API.

Reads one-shot conversions, changes resolution and gain, runs continuous
conversion, polls the configuration byte and uses the general calls.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.mcp3422 import MCP3422Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = MCP3422Full(connection)                            # Create MCP3422 Full driver, (connection)
                                                         # writes the one-shot, 16 bit, x1 default (RDY = 0)

print(adc.read_raw(0))                                   # One-shot code, (channel 0–1) → int
                                                         # writes RDY = 1, O/C = 0, waits, polls RDY
print(adc.read_voltage(1))                               # One-shot voltage, (channel 0–1) → float V
print(adc.read_all())                                    # Both channels, () → [float V, float V]
print(adc.read_scan([0, 1, 0]))                          # Scan a list of channels, (channels) → [float V, ...]

adc.set_resolution(18)                                   # Select 18 bit / 3.75 SPS, (bits 12|14|16|18) → None
                                                         # raises ValueError for other values
adc.set_gain(8)                                          # Select PGA x8, (gain 1|2|4|8) → None
print(adc.get_resolution(), adc.get_gain())              # Read back the selection, () → int, int
print(adc.lsb(), adc.full_scale())                       # LSB and full scale, () → float V, float V
                                                         # 2·2.048/2^bits and 2.048/gain

adc.set_resolution(12)                                   # Back to 12 bit / 240 SPS, (bits) → None
adc.set_gain(1)                                          # Back to x1, (gain) → None
adc.set_channel(0)                                       # Channel for continuous conversion, (channel 0–1) → None
adc.start_continuous()                                   # Start continuous conversion, (channel=None) → None
                                                         # O/C = 1; the first sample after a change is discarded
print(adc.is_continuous())                               # Report the mode, () → bool
print(adc.read_continuous_raw())                         # Next fresh code, () → int
print(adc.read_continuous())                             # Next fresh voltage, () → float V
print(adc.read_config(), adc.is_ready())                 # Config byte and RDY, () → int, bool
adc.stop_continuous()                                    # Back to one-shot / standby, () → None

adc.general_call_convert()                               # Start a conversion on every device, () → None
                                                         # sends 0x00 0x08
time.sleep(0.1)
print(adc.read_pending_raw())                            # Result of that conversion, () → int
adc.general_call_reset()                                 # Reset to power-on defaults, () → None
                                                         # sends 0x00 0x06; the driver state follows
