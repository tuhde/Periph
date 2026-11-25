"""Demo for the ADS1014 — a shunt-current monitor with over-current alert.

A 0.1 Ω shunt in the load's supply line is read on the ±0.256 V range at
250 SPS. The current is printed every second; the traditional comparator
(high 0.1 V, low 0.08 V, latching, queue of 4) reports when the load current
exceeds 1 A, and the alert is cleared with clear_alert().
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1014 import (
    ADS1014Full, I2C_ADDRESS, PGA_0_256, DR_250, COMP_TRADITIONAL, ACTIVE_LOW, QUEUE_4,
    SOURCE_COMPARATOR)

SHUNT_OHMS = 0.1
SAMPLES = 20

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1014Full(connection)                            # Create ADS1014 Full driver, (connection)

# --- Pick the range and speed for a small shunt voltage ---
# ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
# for a once-per-second report.
adc.set_gain(PGA_0_256)                                  # Set PGA, (pga 0–5) → None
adc.set_data_rate(DR_250)                                # Set data rate, (dr 0–6) → None

# --- Arm the over-current comparator ---
# 0.1 V across 0.1 Ω is 1 A. The window is a traditional comparator with
# hysteresis (clears again below 0.08 V), latched so a brief spike is not
# missed, and needs 4 consecutive conversions to assert.
adc.set_thresholds(0.08, 0.1)                            # Set both thresholds, (low V, high V) → None
adc.configure_comparator(COMP_TRADITIONAL, ACTIVE_LOW, True, QUEUE_4)  # Configure comparator, (mode, polarity, latch, queue) → None
adc.enable_interrupt(SOURCE_COMPARATOR)                  # Route comparator to ALERT/RDY, (source) → None

# --- Monitor the current ---
# The pin level is not wired in this demo, so alert state is inferred from
# the measured current; clear_alert() releases the latch afterwards.
for _ in range(SAMPLES):
    v = adc.read_voltage()                               # Read voltage, () → float V
    amps = v / SHUNT_OHMS
    print('{:.3f} V -> {:.2f} A'.format(v, amps))
    if amps > 1.0:
        print('over-current!')
        adc.clear_alert()                                # Release latched alert, () → None
    time.sleep(1)

# --- Release the alert pin when finished ---
adc.disable_interrupt(SOURCE_COMPARATOR)                 # Release ALERT/RDY, (source) → None
