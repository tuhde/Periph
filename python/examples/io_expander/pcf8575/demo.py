"""PCF8575 demo — button-controlled LED mirror.

Hardware:
  P00–P07 (Port 0): LEDs (anode → VCC, cathode → pin; active-low)
  P10–P17 (Port 1): push buttons (pin → GND when pressed; internal pull-up keeps pin high)
  INT: connected to GPIO 5 (active-low open-drain)

Every 200 ms the demo reads both port bytes, extracts the button nibble
from Port 1 (P10–P17, bits 8–15 of the full 16-bit value), inverts it
(pressed = 0 → LED on = 0), and writes the result to Port 0 (P00–P07).
Prints both the raw port bytes in hex and the decoded button/LED states
so the quasi-bidirectional read-back behavior and 2-byte protocol are visible.
"""
from periph.connection.i2c_auto import I2CConnection
from periph.connection.input_pin import MicroPythonPin
from periph.chips.io_expander.pcf8575 import Pcf8575Full
from machine import Pin
import time

# --- Wire interrupt for instant button response ---
# The chip's INT goes low as soon as any input changes, so the callback fires
# within 4 µs (t_iv) instead of waiting for the polling interval.
int_hw = Pin(5, Pin.IN, Pin.PULL_UP)                           # Hardware INT pin, (pin=5, mode=IN)
int_pin = MicroPythonPin(int_hw)                              # Wrap as InputPin, (pin) → InputPin

# --- Initialise bus and driver ---
# PCF8575 is I²C only, 400 kHz Fast mode; start with all pins as inputs.
connection = I2CConnection(0x20, int_pin=int_pin)           # Create I2C connection, (i2c, addr=0x20, int_pin=None, en_pin=None)
chip = Pcf8575Full(connection)                                   # Create PCF8575 full driver, (connection, addr=0x20)

# --- Configure output pins for LEDs (P00–P07 → drive low) ---
# Writing 0 to a pin drives it low via the 25 mA sink; that turns the LED on
# (active-low). Writing 1 releases the pin to quasi-input (LED off).
chip.write_port(0, 0xFF)                                       # Write Port 0, (port=0, mask=int) → None
chip.write_port(1, 0xFF)                                       # Write Port 1, (port=1, mask=int) → None

def _on_change(changed_mask):                                   # Interrupt callback, (changed_mask: int) → None
    pass  # changes are picked up in the main loop via read_port

chip.on_interrupt(_on_change)                                   # Subscribe to interrupts, (callback) → None

# --- Main loop: read buttons → mirror to LEDs ---
# Port 1 (P10–P17) buttons: press pulls low; internal 100 µA pull-up keeps floating high
# Port 0 (P00–P07) LEDs: active-low (cathode to pin), writing 0 turns LED on
print("Running — press buttons on P10–P17 to mirror to LEDs on P00–P07")
while True:
    port0 = chip.read_port(0)                                    # Read Port 0, (port=0) → int bitmask
    port1 = chip.read_port(1)                                   # Read Port 1, (port=1) → int bitmask

    buttons = port1 & 0xFF        # extract P10–P17 (pressed = 0)
    led_bits = (~buttons) & 0xFF  # invert: pressed → LED on (0)
    chip.write_port(0, led_bits)  # Write Port 0, (port=0, mask=int) → None

    btn_str = ''.join('X' if not (buttons >> i) & 1 else '.' for i in range(8))
    led_str = ''.join('*' if not (led_bits >> i) & 1 else ' ' for i in range(8))
    print("P0=0x{:02X}  P1=0x{:02X}  buttons=[{}]  LEDs=[{}]".format(port0, port1, btn_str, led_str))

    time.sleep(0.2)