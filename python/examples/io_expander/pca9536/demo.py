"""PCA9536 demo — blinking LEDs with polled button input.

Two LEDs (anode to VCC through 220 Ω, cathode to P0–P1; the outputs sink up
to 25 mA) and two push buttons to GND on P2–P3 — the chip's built-in weak
pull-up holds an open button input high, so no external resistor is needed.
The LEDs blink alternately; when a button changes, the callback prints the
changed mask and the INPUT value and the LED matching the pressed button
(P2→P0, P3→P1) is held lit for one second.
"""
from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca9536 import Pca9536Full
import time

connection = I2CConnection(0x41)                            # Create I2C connection, (i2c, addr=0x41)
chip = Pca9536Full(connection)                              # Create PCA9536 driver, (connection, addr=0x41)

# --- Configure LEDs and buttons ---
# P0–P1 are LED outputs (active low: 0 lights the LED); P2–P3 stay inputs and
# rely on the built-in weak pull-up, so a pressed button (to GND) reads 0.
chip.write_port(0, 0x0F)                                    # All LEDs off, (port=0, mask=0x0F) → None
chip.set_direction(0x0C)                                    # P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → None

# --- Watch for button changes ---
# The chip has no INT pin, so a 5 ms polling thread compares INPUT with the
# previous value and calls back with the changed-pin mask.
hold_until = [0.0, 0x03]

def on_button(changed):
    print("button changed=%X port=%X" % (changed, chip.read_port()))  # Read all pins, (port=0) → int 0–15
    hold_until[0] = time.time() + 1.0
    hold_until[1] = ~((changed >> 2) & 0x03) & 0x03

chip.watch_changes(on_button, 0.005)                        # Start polling for changes, (callback, interval_s=0.005) → None

# --- Blink loop ---
print("=== Alternating LEDs with button hold ===")
for step in range(40):
    leds = 0x02 if step % 2 == 0 else 0x01
    if time.time() < hold_until[0]:
        leds = hold_until[1]
    chip.write_port(0, 0x0C | leds)                         # Write LED outputs, (port=0, mask) → None
    time.sleep(0.2)

# --- Shut down ---
chip.unwatch_changes()                                      # Stop polling, () → None
chip.write_port(0, 0x0F)                                    # LEDs off, (port=0, mask=0x0F) → None
print("=== Done ===")
