"""Complete example for the ADS1014 — exercise every method in the public API.

Constructs the Full driver, reads in single-shot mode at several gains and
data rates, runs continuous conversion, programs the comparator and its
thresholds, routes the conversion-ready and comparator sources to ALERT/RDY,
and resets the chip.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1014 import (
    ADS1014Full, I2C_ADDRESS, PGA_1_024, DR_250, COMP_WINDOW, ACTIVE_LOW, QUEUE_2,
    SOURCE_CONVERSION_READY, SOURCE_COMPARATOR)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1014Full(connection)                            # Create ADS1014 Full driver, (connection)
                                                         # no I2C traffic; defaults ±2.048 V, 1600 SPS

raw = adc.read_raw()                                     # Read raw code, () → int (−2048…2047)
                                                         # single-shot AIN0 − AIN1, polls OS until idle
v = adc.read_voltage()                                   # Read voltage, () → float V
                                                         # raw × full-scale / 2048
print('raw', raw, 'voltage {:.4f} V'.format(v))

adc.set_gain(PGA_1_024)                                  # Set PGA, (pga 0–5) → None
                                                         # ±1.024 V range, 0.5 mV per code
print('gain', adc.get_gain())                            # Read PGA code, () → int
                                                         # cached PGA[2:0]
print('full scale', adc.full_scale(), 'V')               # Read full-scale range, () → float V
                                                         # ±FSR of the current gain
print('lsb', adc.lsb(), 'V')                             # Read code size, () → float V
                                                         # full_scale() / 2048

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

adc.set_thresholds(-0.2, 0.5)                            # Set both thresholds, (low V, high V) → None
                                                         # converted with lsb(), clamped to the code range
adc.set_low_threshold_raw(-200)                          # Set low threshold, (raw −2048…2047) → None
                                                         # LO_THRESH = raw << 4
adc.set_high_threshold_raw(500)                          # Set high threshold, (raw −2048…2047) → None
                                                         # HI_THRESH = raw << 4
print('thresholds', adc.get_thresholds_raw())            # Read thresholds, () → (int, int)
                                                         # (low, high) sign-extended codes

adc.configure_comparator(COMP_WINDOW, ACTIVE_LOW, True, QUEUE_2)  # Configure comparator, (mode, polarity, latch, queue) → None
                                                         # window mode, latching, 2 consecutive conversions
adc.clear_alert()                                        # Release latched alert, () → None
                                                         # reads CONVERSION
adc.disable_comparator()                                 # Disable comparator, () → None
                                                         # COMP_QUE = 11, ALERT/RDY high-impedance

adc.enable_interrupt(SOURCE_CONVERSION_READY)            # Route conversion-ready to ALERT/RDY, (source) → None
                                                         # HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
print('status', adc.poll_interrupt())                    # Poll ALERT/RDY, () → int source mask
                                                         # level of the INT pin, else CONFIG.OS fallback
adc.disable_interrupt(SOURCE_CONVERSION_READY)           # Release ALERT/RDY, (source) → None
                                                         # COMP_QUE = 11

def on_event(status):
    print('alert source', status)

adc.enable_interrupt(SOURCE_COMPARATOR)                  # Route comparator to ALERT/RDY, (source) → None
                                                         # restores the thresholds set above
adc.on_interrupt(on_event)                               # Subscribe to ALERT/RDY, (callback, int_pin=None) → None
                                                         # pin edge, else a 5 ms polling thread
time.sleep(1)
adc.off_interrupt()                                      # Unsubscribe, () → None
                                                         # stops delivery
adc.reset()                                              # Reset to power-up defaults, () → None
                                                         # rewrites CONFIG and both thresholds
