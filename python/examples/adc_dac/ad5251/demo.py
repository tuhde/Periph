"""Demo for the AD5251 — a voltage-divider level setter.

Steps channel 0 as a divider (A at VDD, B at GND) through several ratios,
printing the expected output voltage at VDD = 3.3 V; then sets channel 1 as a
20 kΩ rheostat and reports the quantisation error, steps the wiper with the
quick commands, stores and restores the wipers, reads and writes the user
EEMEM, prints the stored RAB tolerance, and finally resets from EEMEM.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5251 import AD5251Full, I2C_ADDRESS

VDD = 3.3

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = AD5251Full(connection)                             # Create AD5251 Full driver, (connection, r_ab=50000.0 Ω, r_wiper=75.0 Ω)

# --- Step channel 0 through divider ratios ---
# With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
# round(ratio × 64), so 0.999 lands on the top code 63.
for ratio in (0.0, 0.25, 0.5, 0.75, 0.999):
    pot.set_ratio(0, ratio)                              # Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
    code = pot.get_wiper(0)                              # Read wiper position, (channel 0–1) → int 0–63
    actual = pot.get_ratio(0)                            # Read divider ratio, (channel 0–1) → float
    print('ratio {:.3f} -> code {:3d} ({:.4f}) -> {:.3f} V'.format(ratio, code, actual, actual * VDD))
    time.sleep(0.5)

# --- Use channel 1 as a 20 kΩ rheostat ---
# The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
# the readback differs slightly from the request.
pot.set_resistance(1, 20000.0)                            # Set W–B resistance, (channel 0–1, ohms Ω) → None
r = pot.get_resistance(1)                                # Read W–B resistance, (channel 0–1) → float Ω
print('rheostat {:.1f} Ω, error {:.1f} Ω'.format(r, r - 20000.0))

# --- Step and scale the wiper with the quick commands ---
# increment/decrement move one code, double/halve change the level by 6 dB.
pot.set_wiper(0, 16)                                     # Set wiper position, (channel 0–1, code 0–63) → None
for name, call in (('increment', pot.increment), ('decrement', pot.decrement),
                   ('double', pot.double), ('halve', pot.halve)):
    call(0)                                              # Quick command, (channel=None 0–1) → None
    print(name, '->', pot.get_wiper(0))

# --- Make the settings persistent ---
# store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
# restore() brings the stored values back.
pot.store()                                              # Store wipers in EEMEM, (channel=None 0–1) → None
pot.set_both(1, 2)                                       # Write both wipers, (code0 0–63, code1 0–63) → None
pot.restore()                                            # Restore wipers from EEMEM, (channel=None 0–1) → None
print('restored:', pot.get_wiper(0), pot.get_wiper(1))

# --- User EEMEM bytes and the factory tolerance ---
for index in (4, 5, 6):
    pot.write_eemem(index, 0x10 * index)                 # Write user EEMEM byte, (index 4–15, value 0–63) → None
    print('eemem', index, pot.read_eemem(index))         # Read user EEMEM byte, (index 4–15) → int 0–63
for channel in (0, 1):
    print('channel', channel, 'tolerance {:.3f} %'.format(pot.read_tolerance(channel)),   # (channel 0–1) → float %
          'RAB {:.1f} Ω'.format(pot.rab_actual(channel)))                                  # (channel 0–1) → float Ω

# --- Restore the stored state ---
# reset() restores both wipers from EEMEM and waits for the write cycle.
pot.reset()                                              # Reset from EEMEM, () → None
