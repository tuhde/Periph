from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4728 import MCP4728Full

transport = I2CTransport(bus=1, addr=0x60)          # Create I2C transport, (bus=1, addr=0x60)
dac = MCP4728Full(transport)                          # Create MCP4728 driver, (transport)

# --- Apply four-point calibration voltages to channels A–D ---
# A 4-channel DAC is the canonical way to bias a 4-point sensor bridge
# (load cell, RTD conditioning, strain gauge). Each channel gets a
# different fraction of full scale to demonstrate independent outputs.
# A common supply is 3.3 V; voltages printed below assume that rail.
VDD = 3.3
dac.set_all([0.0, 1.0 / 3, 2.0 / 3, 1.0])            # Update all four channels simultaneously, (fractions[4]) → None
for ch, frac in enumerate([0.0, 1.0 / 3, 2.0 / 3, 1.0]):
    code = round(frac * 4095)
    print("ch={} fraction={:.4f} code={:4d} approx_v={:.3f}V"
          .format(ch, frac, code, code * VDD / 4096))

# --- Synchronous staircase from 0 to full scale on all four channels ---
# Using set_all with the same fraction across channels keeps them in lock-step
# and demonstrates simultaneous V_OUT update via Fast Write. A 50 ms pause
# between steps lets the host controller observe each level on the scope.
import time
STEP = 1.0 / 16
for n in range(17):
    f = n * STEP
    dac.set_all([f, f, f, f])                         # Update all four channels simultaneously, (fractions[4]) → None
    code = round(f * 4095)
    print("step={:2d} fraction={:.4f} code={:4d} approx_v={:.3f}V"
          .format(n, f, code, code * VDD / 4096))
    time.sleep_ms(50)

# --- Reset all channels to 0 V before exit ---
dac.set_all([0.0, 0.0, 0.0, 0.0])                     # Update all four channels simultaneously, (fractions[4]) → None
