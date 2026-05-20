"""PCF8574 demo — button-controlled LED mirror.

Hardware:
  P0–P3: LEDs (anode → VCC, cathode → pin; active-low)
  P4–P7: push buttons (pin → GND when pressed; internal pull-up keeps pin high)
  INT:   connected to GPIO 5 (active-low open-drain)

Every 200 ms the demo reads the full port byte, extracts the button nibble
(P4–P7, bits 4–7), inverts it (pressed = 0 → LED on = 0), and writes the
result to the output nibble (P0–P3). Prints the raw port byte and the decoded
states so the quasi-bidirectional read-back behavior is visible.
"""
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.pcf8574 import Pcf8574Full
import time

# --- Initialise bus and driver ---
# PCF8574 is I²C only, 100 kHz max; start with all pins as inputs.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)           # Create I2C bus, (id, sda, scl, freq=100000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Pcf8574Full(transport)                                   # Create PCF8574 full driver, (transport, addr=0x20)

# --- Configure output pins for LEDs (P0–P3 → drive low) ---
# Writing 0 to a pin drives it low via the 25 mA sink; that turns the LED on
# (active-low). Writing 1 releases the pin to quasi-input (LED off).
chip.write_port(mask=0xF0)                                      # Write all 8 pins, (port=0, mask=int) → None

# --- Wire interrupt for instant button response ---
# The chip's INT goes low as soon as any input changes, so the callback fires
# within one I²C bit time (~10 µs) instead of waiting for the polling interval.
int_hw = Pin(5, Pin.IN, Pin.PULL_UP)                          # Hardware INT pin, (pin=5, mode=IN)

def _on_change(changed_mask):                                  # Interrupt callback, (changed_mask: int) → None
    pass  # changes are picked up in the main loop via read_port

chip.configure_interrupt(int_hw, _on_change)                   # Attach interrupt, (int_pin, callback) → None

# --- Main loop: read buttons → mirror to LEDs ---
# The full port byte contains both the LED shadow (bits 0–3) and the actual
# button state (bits 4–7). Bits written as 1 are released to input mode, so
# a read returns the button's true logic level even though 1 was written there.
print("Running — press buttons on P4–P7 to mirror to LEDs on P0–P3")
while True:
    port = chip.read_port()                                     # Read all 8 pins, () → int bitmask

    buttons = (port >> 4) & 0x0F        # extract P4–P7 (pressed = 0)
    led_bits = (~buttons) & 0x0F        # invert: pressed → LED on (0)
    chip.write_port(mask=(0xF0 | led_bits))                     # Write all 8 pins, (port=0, mask=int) → None

    btn_str = ''.join('X' if not (buttons >> i) & 1 else '.' for i in range(4))
    led_str = ''.join('*' if (led_bits >> i) & 1 else ' ' for i in range(4))
    print("port=0x{:02X}  buttons=[{}]  LEDs=[{}]".format(port, btn_str, led_str))

    time.sleep(0.2)
