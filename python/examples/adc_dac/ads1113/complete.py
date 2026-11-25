"""Complete example for the ADS1113 — exercise every method in the public API.

Constructs the Full driver, reads the input, changes the data rate, runs
continuous conversion, reads the status and raw CONFIG, and finally
resets the chip.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1113 import ADS1113Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1113Full(connection)                                # Create ADS1113 Full driver, (connection)
                                                         # no I²C traffic; Config word is rebuilt per conversion
print('raw', adc.read_raw())                           # Read raw code, () → int
                                                         # single-shot conversion, signed 16-bit
print('volts', adc.read_voltage())                     # Read voltage, () → float V
                                                         # raw × lsb()
print('fsr', adc.full_scale(), 'lsb', adc.lsb())         # Full scale / LSB, () → float V
                                                         # fixed ±2.048 V, 62.5 µV
adc.set_data_rate(ADS1113Full.DR_250)                       # Set data rate, (dr 0–7) → None
                                                         # DR field of the Config word
print('rate', adc.get_data_rate(), adc.data_rate_sps())  # Get data rate, () → int / int SPS
                                                         # code and samples per second
adc.start_continuous()                                   # Start continuous, () → None
                                                         # MODE ← 0
time.sleep(0.05)
print('cont raw', adc.read_continuous_raw())             # Read continuous raw, () → int
                                                         # CONVERSION without touching Config
print('cont', adc.read_continuous())                     # Read continuous, () → float V
                                                         # read_continuous_raw() × lsb()
adc.stop_continuous()                                    # Stop continuous, () → None
                                                         # MODE ← 1
print('converting', adc.is_converting())                 # Check conversion, () → bool
                                                         # CONFIG.OS == 0
print('config 0x{:04X}'.format(adc.read_config()))       # Read CONFIG, () → int
                                                         # raw 16-bit register

adc.reset()                                              # Reset, () → None
                                                         # writes the reset values back
