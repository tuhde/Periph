"""Demo for the CAT5171 — a programmable level trimmer.

Sweeps the wiper in steps of 16 printing the W–B resistance, sets a 22 kΩ
rheostat and reports the quantisation error, demonstrates shutdown/resume,
midscale and reset, and finally steps the wiper as a voltage divider
(A at VDD, B at GND) printing the expected output voltage at VDD = 3.3 V.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.cat5171 import CAT5171Full, I2C_ADDRESS

VDD = 3.3

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = CAT5171Full(connection)                            # Create CAT5171 Full driver, (connection, r_ab=100000.0 Ω, r_wiper=50.0 Ω)

# --- Sweep the wiper across the full range ---
# Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
# LSB below the RAB end.
for code in range(0, 256, 16):
    pot.set_wiper(code)                                  # Set wiper position, (code 0–255) → None
    print('code {:3d} -> {:.1f} Ω'.format(code, pot.get_resistance()))  # Read W–B resistance, () → float Ω
    time.sleep(0.2)

# --- Set a 22 kΩ rheostat ---
# The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
# so the readback differs slightly from the request.
pot.set_resistance(22000.0)                              # Set W–B resistance, (ohms Ω) → None
r = pot.get_resistance()                                 # Read W–B resistance, () → float Ω
print('rheostat {:.1f} Ω, error {:.1f} Ω'.format(r, r - 22000.0))

# --- Shutdown, resume, midscale and reset ---
# Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
# so resume restores the setting; midscale and reset both return to 0x80.
code = pot.get_wiper()                                   # Read wiper position, () → int 0–255
pot.shutdown()                                           # Shut down, () → None
print('shutdown:', pot.is_shutdown(), 'kept', pot.get_wiper() == code)  # Query shutdown state, () → bool
time.sleep(1)
pot.resume()                                             # Leave shutdown, () → None
pot.midscale()                                           # Move wiper to midscale, () → None
pot.reset()                                              # Midscale reset, () → None

# --- Use the wiper as a voltage divider ---
# With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
# round(ratio × 256), so 0.999 lands on the top code 255.
for ratio in (0.0, 0.25, 0.5, 0.75, 0.999):
    pot.set_ratio(ratio)                                 # Set divider ratio, (ratio 0.0–1.0) → None
    actual = pot.get_ratio()                             # Read divider ratio, () → float
    print('ratio {:.3f} -> {:.4f} -> {:.3f} V'.format(ratio, actual, actual * VDD))
    time.sleep(0.5)
