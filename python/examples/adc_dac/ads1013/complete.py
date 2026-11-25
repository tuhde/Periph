"""Complete example for the ADS1013 — exercise every method in the public API.

Constructs the Full driver, reads in single-shot mode at several data rates,
runs continuous conversion and resets the chip.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1013 import ADS1013Full, I2C_ADDRESS, DR_250

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1013Full(connection)                            # Create ADS1013 Full driver, (connection)
                                                         # no I2C traffic; fixed ±2.048 V, 1600 SPS

raw = adc.read_raw()                                     # Read raw code, () → int (−2048…2047)
                                                         # single-shot AIN0 − AIN1, polls OS until idle
v = adc.read_voltage()                                   # Read voltage, () → float V
                                                         # raw × 0.001
print('raw', raw, 'voltage {:.4f} V'.format(v))
print('full scale', adc.full_scale(), 'V')               # Read full-scale range, () → float V
                                                         # always 2.048
print('lsb', adc.lsb(), 'V')                             # Read code size, () → float V
                                                         # always 0.001

adc.set_data_rate(DR_250)                                # Set data rate, (dr 0–6) → None
                                                         # DR_250 = 250 SPS
print('dr code', adc.get_data_rate())                    # Read data-rate code, () → int
                                                         # cached DR[2:0]
print('sps', adc.data_rate_sps())                        # Read data rate, () → int SPS
                                                         # table lookup of the DR code

adc.start_continuous()                                   # Start continuous conversion, () → None
                                                         # MODE = 0 at the current data rate
time.sleep(0.02)
print('continuous raw', adc.read_continuous_raw())       # Read latest code, () → int
                                                         # reads CONVERSION without touching Config
print('continuous {:.4f} V'.format(adc.read_continuous()))  # Read latest voltage, () → float V
                                                         # raw × lsb()
adc.stop_continuous()                                    # Stop continuous conversion, () → None
                                                         # MODE = 1, back to single-shot
print('converting', adc.is_converting())                 # Check conversion in progress, () → bool
                                                         # CONFIG.OS = 0 (single-shot only)
print('config 0x{:04X}'.format(adc.read_config()))       # Read CONFIG register, () → int
                                                         # raw 16-bit register
adc.reset()                                              # Reset to power-up defaults, () → None
                                                         # rewrites CONFIG = 0x8583
