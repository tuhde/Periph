"""PCA9539 demo — LED scanner with interrupt-driven button input.

Eight active-low LEDs (anode to VCC through 220 Ω) on P00–P07 and eight
push buttons to GND on P10–P17 (with external pull-ups: the chip has none).
The scanner runs a one-LED pattern; when a button interrupt fires the LED
matching the pressed button (P10→P00, …) is held lit for one second. Prints
the scanner position and the 16-bit port state each iteration.
"""
from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca9539 import Pca9539Full
import time

connection = I2CConnection(0x74)                            # Create I2C connection, (i2c, addr=0x74)
chip = Pca9539Full(connection)                             # Create PCA9539 driver, (connection, addr=0x74)

# --- Configure LEDs and buttons ---
# Port 0 holds the LED outputs (active low: 0 lights the LED); port 1 stays
# input and relies on external pull-ups, so a pressed button (to GND) reads 0.
# Polarity inversion makes a pressed button read as 1.
chip.write_all(0xFFFF)                                      # All LEDs off, (value=0xFFFF) → None
chip.set_direction(0xFF00)                                  # Port 0 outputs, port 1 inputs, (mask=0xFF00) → None
chip.configure_polarity(0xFF00)                             # Invert port 1 inputs, (mask=0xFF00) → None

# --- Subscribe to input changes ---
# Any button edge raises INT; the callback receives the 16-bit changed-pin mask
# and holds the matching LED for one second.
hold_until = [0.0, 0xFF]

def on_button(changed):
    print("button changed=%04X all=%04X" % (changed, chip.read_all()))  # Read all 16 pins, () → int 0–65535
    hold_until[0] = time.time() + 1.0
    hold_until[1] = ~((changed >> 8) & 0xFF) & 0xFF

chip.on_interrupt(on_button)                                # Subscribe to INT, (callback, int_pin=None) → None

# --- Scanner loop ---
print("=== LED scanner with button interrupt ===")
for step in range(80):
    pos = step % 8
    leds = ~(1 << pos) & 0xFF
    if time.time() < hold_until[0]:
        leds = hold_until[1]
    chip.write_all(0xFF00 | leds)                           # Write LED outputs, (value) → None
    print("  pos=%d  all=%04X" % (pos, chip.read_all()))    # Read all 16 pins, () → int 0–65535
    time.sleep(0.1)

# --- Shut down ---
chip.off_interrupt()                                        # Unsubscribe, () → None
chip.write_all(0xFFFF)                                      # LEDs off, (value=0xFFFF) → None
print("=== Done ===")
