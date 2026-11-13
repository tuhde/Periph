"""Demo for the DS3502 — a VCOM-style voltage setter.

With RH = 15.0 V and RL = 0 V (V+ = 15.5 V) prints the wiper loaded from the
IVR at power-up, sets 6.0 V, sweeps the wiper in 16 steps, steps up and down
with increment, shows the MODE bit and finally persists the chosen code in
the EEPROM (consuming one EEPROM write).
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds3502 import DS3502Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = DS3502Full(connection, v_high=15.0, v_low=0.0)     # Create DS3502 Full driver, (connection, v_high=1.0 V, v_low=0.0 V)

# --- Power-up position ---
# init leaves the wiper where the IVR put it (factory default 0x40, midscale).
print('power-up wiper', pot.get_wiper(), '->', pot.get_voltage(), 'V')  # Read wiper position, () → int 0–127

# --- Set 6.0 V ---
pot.set_voltage(6.0)                                     # Set RW output voltage, (volts V) → None
print('code', pot.get_wiper(), 'ratio {:.3f}'.format(pot.get_ratio()), 'voltage {:.3f} V'.format(pot.get_voltage()))  # Read ratio, () → float

# --- Sweep in 16 steps ---
# The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
for code in list(range(0, 128, 8)) + [127]:
    pot.set_wiper(code)                                  # Set wiper position, (code 0–127) → None
    print('code {:3d} -> {:.3f} V'.format(code, pot.get_voltage()))  # Read unloaded RW voltage, () → float V
    time.sleep(0.2)

# --- Step up and down ---
pot.set_voltage(6.0)
print('+4 ->', pot.increment(4))                         # Step the wiper, (steps=1) → int 0–127
print('-4 ->', pot.increment(-4))

# --- MODE ---
# MODE = 1 (the working mode) keeps wiper writes in SRAM; store_wiper switches
# to MODE = 0 only for the duration of the EEPROM write.
print('eeprom mode:', pot.get_mode())                    # Read MODE, () → bool
pot.set_mode(False)                                      # Select SRAM-only writes, (eeprom bool) → None

# --- Persist the chosen code ---
code = pot.get_wiper()
print('storing code', code, '(consumes one EEPROM write)')
pot.store_wiper(code)                                    # Store power-up position, (code=None) → None
pot.wait_ready()                                         # Acknowledge polling, (timeout_ms=30) → None
print('read back', pot.get_wiper())                      # Read wiper position, () → int 0–127
