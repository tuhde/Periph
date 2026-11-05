"""Demo for the AD5243 — a voltage-divider level setter.

Steps channel 0 as a divider (A at VDD, B at GND) through several ratios,
printing the expected output voltage at VDD = 3.3 V; then sets channel 1 as a
2.2 kΩ rheostat and reports the quantisation error, demonstrates shutdown and
resume, and finally resets both channels.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5243 import AD5243Full, I2C_ADDRESS

VDD = 3.3

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = AD5243Full(connection)                             # Create AD5243 Full driver, (connection, r_ab=10000.0 Ω, r_wiper=160.0 Ω)

# --- Step channel 0 through divider ratios ---
# With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
# round(ratio × 256), so 0.999 lands on the top code 255.
for ratio in (0.0, 0.25, 0.5, 0.75, 0.999):
    pot.set_ratio(0, ratio)                              # Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
    code = pot.get_wiper(0)                              # Read wiper position, (channel 0–1) → int 0–255
    actual = pot.get_ratio(0)                            # Read divider ratio, (channel 0–1) → float
    print('ratio {:.3f} -> code {:3d} ({:.4f}) -> {:.3f} V'.format(ratio, code, actual, actual * VDD))
    time.sleep(0.5)

# --- Use channel 1 as a 2.2 kΩ rheostat ---
# The 256 steps quantise the resistance (~39 Ω per code on a 10 kΩ part), so
# the readback differs slightly from the request.
pot.set_resistance(1, 2200.0)                            # Set W–B resistance, (channel 0–1, ohms Ω) → None
r = pot.get_resistance(1)                                # Read W–B resistance, (channel 0–1) → float Ω
print('rheostat {:.1f} Ω, error {:.1f} Ω'.format(r, r - 2200.0))

# --- Shut channel 1 down and bring it back ---
# Shutdown opens A and shorts the wiper to B (about 0 Ω) while the RDAC keeps
# its value, so resume restores the 2.2 kΩ setting.
pot.shutdown(1)                                          # Shut channel down, (channel 0–1) → None
print('shutdown:', pot.is_shutdown(1))                   # Query shutdown state, (channel 0–1) → bool
time.sleep(1)
pot.resume(1)                                            # Leave shutdown, (channel 0–1) → None

# --- Restore the power-up state ---
# The chip has no reset pin, so reset() rewrites midscale to both channels.
pot.reset()                                              # Restore power-up preset, () → None
