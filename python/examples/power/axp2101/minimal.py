"""Minimal example for the AXP2101 — battery and power-source monitoring.

Constructs the driver (ADC channels and fuel gauge are switched on, charger and
rails keep their power-on configuration) and prints the primary values once a second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.axp2101 import AXP2101Minimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x34)
chip = AXP2101Minimal(connection)                                 # Create AXP2101 driver, (connection)

for _ in range(10):
    v = chip.battery_voltage()                                 # Read battery voltage, () → float V
    pct = chip.battery_percent()                               # Read state of charge, () → int %
    state = chip.charge_state()                                # Read charge state, () → int (0 trickle … 5 none)
    vbus = chip.vbus_present()                                 # Check input source, () → bool
    print('{:.3f} V  {} %  state={}  vbus={}'.format(v, pct, state, vbus))
    time.sleep(1)
