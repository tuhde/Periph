"""MCP23017 demo — Knight Rider scanner with button input.

Seven LEDs (active-high, anode through 220Ω to GPA0–GPA6, cathodes to GND)
and seven push buttons (GPB0–GPB6, pull-ups enabled via GPPU, active-low).
The scanner runs a Knight Rider pattern on the LEDs; button presses override
the pattern so pressing button N lights LED N. Prints scanner position,
button mask, and output mask each iteration.
"""
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.mcp23017 import Mcp23017Full
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)           # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Mcp23017Full(transport)                                 # Create MCP23017 driver, (transport, addr=0x20)

# GPA7 is output-only; set it low as the rightmost scanner LED position
# GPA0–GPA6 are inputs (button state mirrors to output)
# GPB0–GPB6 are inputs with pull-ups for buttons
chip.configure_pullup(1, 0x7F)                                  # Enable pull-ups on GPB0–GPB6, (port=1, mask=0x7F) → None

print("=== Knight Rider scanner with button override ===")
print("  pos  btn_msk  out_mask")
for step in range(40):
    port_b = chip.read_port(1)                                # Read GPB0–GPB6 buttons, (port=1) → int 0–127
    btn_mask = (~port_b) & 0x7F                               # Invert: active-low buttons → active-high mask

    # Scanner position cycles GPA0→GPA6, then back
    direction = 1 if (step // 7) % 2 == 0 else -1
    pos = step % 7
    if direction == -1:
        pos = 6 - pos

    scanner = 1 << pos
    output = btn_mask | scanner                                # Button takes priority over scanner

    chip.write_port(0, output)                                # Write PORTA outputs, (port=0, mask) → None

    print("  %d     %02X        %02X" % (pos, btn_mask, output))

    if step < 39:
        time.sleep(0.1)

chip.write_port(0, 0x00)                                      # Clear all outputs, (port=0, mask=0x00) → None
print("=== Done ===")