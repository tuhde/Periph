import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.ht16k33 import HT16K33Full

connection = I2CConnection(0x70)

# --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
# A single lit pixel visits all 16 x 8 positions, the brightness is swept from
# 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
# for 20 s via the INT output before standby and wake.
matrix = HT16K33Full(connection)                          # Create HT16K33 driver, (connection)

for com in range(HT16K33Full.COMMONS):
    for row in range(HT16K33Full.ROWS):
        matrix.clear()                                    # Blank the display, () → None
        matrix.set_pixel(com, row)                        # Set one LED, (com 0–7, row 0–15, on) → None
        matrix.show()                                     # Write the shadow RAM, () → None
        time.sleep(0.05)

matrix.write_raw(bytes([0xFF] * 16), 0)                   # All LEDs on, (data, address) → None
for level in range(16):
    matrix.set_brightness(level)                          # Sweep dimming, (level 0–15) → None
    time.sleep(0.1)

for f in (2, 1, 0.5, 0):
    matrix.set_blink(f)                                   # Set blink frequency, (0, 0.5, 1, 2 Hz) → None
    time.sleep(3)

matrix.set_int_output(True)                               # INT output, active low, (enable, active_high) → None
deadline = time.time() + 20
while time.time() < deadline:
    keys = matrix.poll_interrupt()                        # Read keys if pending, () → int bitmask
    if keys:
        print('keys: 0x{:x}'.format(keys))
    time.sleep(0.02)

matrix.standby()                                          # Oscillator off, () → None
time.sleep(1)
matrix.wake()                                             # Oscillator on, state restored, () → None
print('===DONE: 0 passed, 0 failed===')
