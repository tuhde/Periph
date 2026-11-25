"""Minimal example for the MCP3422 — read both differential inputs.

Constructs the driver with its defaults (one-shot, 16 bit / 15 SPS, gain x1)
and prints the raw code and the voltage of each channel.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.mcp3422 import MCP3422Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = MCP3422Minimal(connection)                         # Create MCP3422 driver, (connection)

while True:
    for channel in (0, 1):
        raw = adc.read_raw(channel)                      # Read a signed code, (channel 0–1) → int
        volts = adc.read_voltage(channel)                # Read the differential voltage, (channel 0–1) → float V
        print('CH{}: {} -> {:.6f} V'.format(channel + 1, raw, volts))
    print(adc.read_all())                                # Read both channels, () → [float V, float V]
    time.sleep(1)
