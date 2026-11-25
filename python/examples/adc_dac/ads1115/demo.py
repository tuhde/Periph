"""Demo for the ADS1115 — a real-world scenario.

Four-channel analog monitor with over-voltage alert: read AIN0–AIN3 once per
second at ±4.096 V / 250 SPS, then run AIN0 continuously with a latching
window comparator and report out-of-range events, and finish with a
differential AIN2 − AIN3 reading at ±0.256 V.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1115 import ADS1115Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1115Full(connection)                                # Create ADS1115 Full driver, (connection)

# --- Configure the converter ---
# ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
# reads short while still averaging out most noise.
adc.set_gain(adc.PGA_4_096)                              # Set gain, (pga 0–5) → None
adc.set_data_rate(adc.DR_250)                            # Set data rate, (dr 0–7) → None

# --- Read the inputs once per second ---
# Single-shot mode powers the chip down between conversions.
for _ in range(3):
    for ch in range(4):
        raw = adc.read_raw(ch)                           # Read raw code, (channel=0) → int
        print('AIN{} raw {:6d} {:.5f} V'.format(ch, raw, raw * adc.lsb()))
    time.sleep(1)

# --- Arm a window comparator ---
# ALERT/RDY asserts when the input leaves 0.5 V … 3.0 V for two
# consecutive conversions; the latch holds it until clear_alert().
adc.set_thresholds(0.5, 3.0)                             # Set thresholds, (low V, high V) → None
adc.configure_comparator(adc.COMP_WINDOW, adc.ACTIVE_LOW,
                         True, adc.QUEUE_2)              # Configure comparator, (mode, polarity, latch=False, queue=QUEUE_1) → None
adc.start_continuous(adc.MUX_AIN0_GND)                      # Start continuous, (mux=MUX_AIN0_GND 0–7) → None


def on_alert(status):
    print('OUT OF RANGE')
    adc.clear_alert()                                    # Clear alert, () → None


adc.on_interrupt(on_alert)                               # Subscribe, (callback) → None
time.sleep(10)
adc.off_interrupt()                                      # Unsubscribe, () → None
adc.disable_comparator()                                 # Disable comparator, () → None
adc.stop_continuous()                                    # Stop continuous, () → None

# --- Bridge-sensor style differential read ---
# The smallest range gives 7.8 µV per code for millivolt-level signals.
adc.set_gain(adc.PGA_0_256)                              # Set gain, (pga 0–5) → None
print('diff {:.6f} V'.format(adc.read_differential(adc.DIFF_2_3)))  # Read differential, (pair 0–3) → float V
