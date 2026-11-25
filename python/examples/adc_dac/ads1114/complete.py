"""Complete example for the ADS1114 — exercise every method in the public API.

Constructs the Full driver, reads the input, changes the data rate, gain, runs
continuous conversion, reads the status and raw CONFIG, programs the comparator and thresholds, drives the ALERT/RDY interrupt API, and finally
resets the chip.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1114 import ADS1114Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1114Full(connection)                                # Create ADS1114 Full driver, (connection)
                                                         # no I²C traffic; Config word is rebuilt per conversion
print('raw', adc.read_raw())                           # Read raw code, () → int
                                                         # single-shot conversion, signed 16-bit
print('volts', adc.read_voltage())                     # Read voltage, () → float V
                                                         # raw × lsb()
adc.set_gain(ADS1114Full.PGA_4_096)                         # Set gain, (pga 0–5) → None
                                                         # ±4.096 V full scale
print('gain', adc.get_gain())                            # Get gain, () → int
                                                         # cached PGA code
print('fsr', adc.full_scale(), 'lsb', adc.lsb())         # Full scale / LSB, () → float V
                                                         # lsb = full_scale / 32768
adc.set_data_rate(ADS1114Full.DR_250)                       # Set data rate, (dr 0–7) → None
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

adc.set_thresholds(0.5, 3.0)                             # Set thresholds, (low V, high V) → None
                                                         # converted with the current lsb(), clamped
adc.set_low_threshold_raw(4000)                          # Set low threshold, (raw) → None
                                                         # LO_THRESH ← raw & 0xFFFF
adc.set_high_threshold_raw(20000)                        # Set high threshold, (raw) → None
                                                         # HI_THRESH ← raw & 0xFFFF
print('thresholds', adc.get_thresholds_raw())            # Get thresholds, () → (int, int)
                                                         # read back, sign-extended
adc.configure_comparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW,
                         True, ADS1114Full.QUEUE_2)          # Configure comparator, (mode, polarity, latch=False, queue=QUEUE_1) → None
                                                         # window mode, latching, 2-sample queue
adc.clear_alert()                                        # Clear alert, () → None
                                                         # reads CONVERSION to release the latch
adc.disable_comparator()                                 # Disable comparator, () → None
                                                         # COMP_QUE ← 11
adc.enable_interrupt(ADS1114Full.SOURCE_CONVERSION_READY)     # Enable interrupt, (source) → None
                                                         # ALERT/RDY signals conversion-ready
print('poll', adc.poll_interrupt())                      # Poll interrupt, () → int
                                                         # enabled source if ALERT/RDY is asserted
adc.on_interrupt(lambda s: print('alert', s))            # Subscribe, (callback) → None
                                                         # edge on ALERT/RDY, or a polling thread on Linux
time.sleep(1)
adc.off_interrupt()                                      # Unsubscribe, () → None
                                                         # stops delivery
adc.disable_interrupt(ADS1114Full.SOURCE_CONVERSION_READY)    # Disable interrupt, (source) → None
                                                         # releases ALERT/RDY

adc.reset()                                              # Reset, () → None
                                                         # writes the reset values back
