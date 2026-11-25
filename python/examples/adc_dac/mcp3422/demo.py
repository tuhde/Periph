"""Demo for the MCP3422 — a two-channel bridge / thermocouple monitor.

Reads CH1 (a thermistor divider) at 16 bit / x1, then switches to 18 bit / x8 and
reads CH2 (a bridge sensor) with read_scan, then runs continuous conversion on CH1
at 12 bit / 240 SPS for 20 samples (min / max / mean and the measured sample
rate) and finishes with a general call reset.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.mcp3422 import MCP3422Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = MCP3422Full(connection)                            # Create MCP3422 Full driver, (connection)

# --- CH1: thermistor divider at 16 bit / x1 ---
for _ in range(3):
    raw = adc.read_raw(0)                                # One-shot code, (channel 0–1) → int
    print('CH1 raw {} = {:.5f} V'.format(raw, adc.read_voltage(0)))   # One-shot voltage, (channel 0–1) → float V
    time.sleep(1)

# --- CH2: bridge sensor at 18 bit / x8 ---
adc.set_resolution(18)                                   # Select 18 bit / 3.75 SPS, (bits) → None
adc.set_gain(8)                                          # Select PGA x8, (gain) → None
volts = adc.read_scan([1])[0]                            # Scan channels, (channels) → [float V]
print('CH2 {:.1f} uV, LSB {:.3f} uV'.format(volts * 1e6, adc.lsb() / adc.get_gain() * 1e6))   # LSB, () → float V

# --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
adc.set_resolution(12)
adc.set_gain(1)
adc.start_continuous(0)                                  # Start continuous conversion, (channel=None) → None
samples = []
t0 = time.time()
for _ in range(20):
    samples.append(adc.read_continuous())                # Next fresh voltage, () → float V
elapsed = time.time() - t0
adc.stop_continuous()                                    # Back to one-shot / standby, () → None
print('min {:.5f} max {:.5f} mean {:.5f} V, {:.0f} samples/s'.format(
    min(samples), max(samples), sum(samples) / len(samples), len(samples) / elapsed))

adc.general_call_reset()                                 # Reset to power-on defaults, () → None
