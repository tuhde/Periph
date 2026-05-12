from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Full
import time

transport = I2CTransport(bus=1, addr=0x60)          # Create I2C transport, (bus=1, addr=0x60)
dac = MCP4725Full(transport)                       # Create MCP4725 driver, (transport)

# --- Configure DAC output in normal mode, no EEPROM writes ---
# This is a demo scenario: generate a triangle wave on the DAC output
# to observe on an oscilloscope. The MCP4725's 12-bit resolution gives
# 4096 discrete levels, so step size of 1/20 of full scale (~205 codes)
# provides visible steps while staying within a reasonable update rate.

STEP = 1.0 / 20.0
DELAY = 0.1

for n in range(21):
    fraction = (n * STEP) if n <= 20 else (2.0 - n * STEP)
    if n > 20:
        fraction = max(0.0, 2.0 - n * STEP)

# Up sweep: 0 to full scale in 21 steps
for n in range(21):
    fraction = n * STEP
    dac.set_voltage(fraction)                      # Set output as fraction of V_DD, (fraction=0.0–1.0) → None
    code = round(fraction * 4095)
    approx_v = code * 3.3 / 4096 if fraction > 0 else 0
    print('n={:2d} fraction={:.2f} code={:4d} approx_v={:.3f}V'.format(n, fraction, code, approx_v))
    time.sleep(DELAY)

# Down sweep: full scale back to 0 in 20 steps
for n in range(20, -1, -1):
    fraction = n * STEP
    dac.set_voltage(fraction)                      # Set output as fraction of V_DD, (fraction=0.0–1.0) → None
    code = round(fraction * 4095)
    approx_v = code * 3.3 / 4096 if fraction > 0 else 0
    print('n={:2d} fraction={:.2f} code={:4d} approx_v={:.3f}V'.format(n, fraction, code, approx_v))
    time.sleep(DELAY)