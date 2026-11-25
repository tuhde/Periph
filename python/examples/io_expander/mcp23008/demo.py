"""MCP23008 demo — LED scanner with interrupt-driven button input.

Four LEDs (active-high, through 220 Ω) on GP0–GP3 and four push buttons to
GND on GP4–GP7 (pull-ups enabled). The scanner runs a pattern on the LEDs;
when a button interrupt fires the LED matching the pressed button
(GP4→GP0, …) is held lit for one second. Prints the scanner position and the
port state each iteration.
"""
from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.mcp23008 import Mcp23008Full
import time

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Mcp23008Full(connection)                             # Create MCP23008 driver, (connection, addr=0x20)

# --- Configure LEDs and buttons ---
# GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups on,
# so a pressed button (to GND) reads 0.
for n in range(4):
    chip.pin(n, Mcp23008Full.OUT)                           # Get LED pin as output, (n, mode=OUT)
chip.configure_pullup(0xF0)                                 # Enable pull-ups on GP4–GP7, (mask=0xF0) → None

# --- Arm interrupt-on-change on the buttons ---
# Any button edge raises INT; the callback reports INTF and the INTCAP
# snapshot, then holds the matching LED for one second.
hold_until = [0.0, 0]

def on_button(status):
    captured = chip.read_capture()                          # Read port snapshot, () → int 0–255
    print("button INTF=%02X INTCAP=%02X" % (status, captured))
    hold_until[0] = time.time() + 1.0
    hold_until[1] = (status >> 4) & 0x0F

chip.enable_pin_interrupt(0xF0)                             # Arm GP4–GP7, (mask=0xF0) → None
chip.on_interrupt(on_button)                                # Subscribe to INT, (callback, int_pin=None, mode='change') → None

# --- Scanner loop ---
print("=== LED scanner with button interrupt ===")
for step in range(40):
    pos = step % 4
    leds = 1 << pos
    if time.time() < hold_until[0]:
        leds = hold_until[1]
    chip.write_port(0, leds)                                # Write LED outputs, (port=0, mask) → None
    print("  pos=%d  port=%02X" % (pos, chip.read_port()))  # Read all pins, (port=0) → int 0–255
    time.sleep(0.1)

# --- Shut down ---
chip.off_interrupt()                                        # Unsubscribe, () → None
chip.write_port(0, 0x00)                                    # Clear all outputs, (port=0, mask=0x00) → None
print("=== Done ===")
