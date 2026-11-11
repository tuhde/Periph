"""PCA6408A demo — LED scanner with interrupt-driven button input.

Four active-low LEDs (anode to VDD(P) through 220 Ω) on P0–P3 and four push
buttons to GND on P4–P7 (with external pull-ups: the chip has none). The
scanner runs a one-LED pattern; when a button interrupt fires the LED matching
the pressed button (P4→P0, …) is held lit for one second. Prints the scanner
position and the port state each iteration.
"""
from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca6408a import Pca6408aFull
import time

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Pca6408aFull(connection)                             # Create PCA6408A driver, (connection, addr=0x20)

# --- Configure LEDs and buttons ---
# P0–P3 are LED outputs (active low: 0 lights the LED); P4–P7 stay inputs and
# rely on external pull-ups, so a pressed button (to GND) reads 0.
chip.write_port(0, 0xFF)                                    # All LEDs off, (port=0, mask=0xFF) → None
chip.set_direction(0xF0)                                    # P0–P3 outputs, P4–P7 inputs, (mask=0xF0) → None

# --- Subscribe to input changes ---
# Any button edge raises INT; the callback receives the changed-pin mask and
# holds the matching LED for one second.
hold_until = [0.0, 0x0F]

def on_button(changed):
    print("button changed=%02X port=%02X" % (changed, chip.read_port()))  # Read all pins, (port=0) → int 0–255
    hold_until[0] = time.time() + 1.0
    hold_until[1] = ~((changed >> 4) & 0x0F) & 0x0F

chip.on_interrupt(on_button)                                # Subscribe to INT, (callback, int_pin=None) → None

# --- Scanner loop ---
print("=== LED scanner with button interrupt ===")
for step in range(40):
    pos = step % 4
    leds = ~(1 << pos) & 0x0F
    if time.time() < hold_until[0]:
        leds = hold_until[1]
    chip.write_port(0, 0xF0 | leds)                         # Write LED outputs, (port=0, mask) → None
    print("  pos=%d  port=%02X" % (pos, chip.read_port()))  # Read all pins, (port=0) → int 0–255
    time.sleep(0.1)

# --- Shut down ---
chip.off_interrupt()                                        # Unsubscribe, () → None
chip.write_port(0, 0xFF)                                    # LEDs off, (port=0, mask=0xFF) → None
print("=== Done ===")
