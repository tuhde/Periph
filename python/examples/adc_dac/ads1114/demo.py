"""Demo for the ADS1114 — a real-world scenario.

Single-channel supply monitor with window alert: read the input once per
second at ±4.096 V / 250 SPS, then run continuously with a latching window
comparator (0.5 V … 3.0 V) and report out-of-range events.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1114 import ADS1114Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1114Full(connection)                                # Create ADS1114 Full driver, (connection)

# --- Configure the converter ---
# ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
# reads short while still averaging out most noise.
adc.set_gain(adc.PGA_4_096)                              # Set gain, (pga 0–5) → None
adc.set_data_rate(adc.DR_250)                            # Set data rate, (dr 0–7) → None

# --- Read the inputs once per second ---
# Single-shot mode powers the chip down between conversions.
for _ in range(3):
    raw = adc.read_raw()                                 # Read raw code, () → int
    print('raw {:6d} {:.5f} V'.format(raw, raw * adc.lsb()))
    time.sleep(1)

# --- Arm a window comparator ---
# ALERT/RDY asserts when the input leaves 0.5 V … 3.0 V for two
# consecutive conversions; the latch holds it until clear_alert().
adc.set_thresholds(0.5, 3.0)                             # Set thresholds, (low V, high V) → None
adc.configure_comparator(adc.COMP_WINDOW, adc.ACTIVE_LOW,
                         True, adc.QUEUE_2)              # Configure comparator, (mode, polarity, latch=False, queue=QUEUE_1) → None
adc.start_continuous()                                   # Start continuous, () → None


def on_alert(status):
    print('OUT OF RANGE')
    adc.clear_alert()                                    # Clear alert, () → None


adc.on_interrupt(on_alert)                               # Subscribe, (callback) → None
time.sleep(10)
adc.off_interrupt()                                      # Unsubscribe, () → None
adc.disable_comparator()                                 # Disable comparator, () → None
adc.stop_continuous()                                    # Stop continuous, () → None
