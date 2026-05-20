from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.pcf8574 import Pcf8574Minimal, Pcf8574Full
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)           # Create I2C bus, (id, sda, scl, freq=100000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Pcf8574Full(transport)                                   # Create PCF8574 full driver, (transport, addr=0x20)
                                                               # initialises all 8 pins as inputs; shadow = 0xFF

# --- Direction and value via pin proxy ---
p0 = chip.pin(0)                                               # Get pin proxy, (n) → Pin
                                                               # returns proxy bound to P0; no bus transaction yet
p0.init(Pcf8574Minimal.OUT)                                    # Set direction output, (mode=OUT) → None
                                                               # drives P0 low (safe initial state for output)
p0.on()                                                        # Set high (release to input mode), () → None
                                                               # writes shadow | (1 << 0) — quasi-high, not strong drive
p0.off()                                                       # Drive low, () → None
                                                               # writes shadow & ~(1 << 0) — strong pull-down, up to 25 mA
p0.toggle()                                                    # Invert shadow bit, () → None
                                                               # flips the bit in the shadow register and writes

v = p0.value()                                                 # Read actual pin level, () → int
                                                               # reads the full port byte, masks bit 0

p0.value(1)                                                    # Write pin, (x=0|1) → None
                                                               # equivalent to on(); updates shadow and writes port

# --- Port-level bulk read / write ---
mask = chip.read_port()                                        # Read all 8 pins, (port=0) → int bitmask
                                                               # P0 in bit 0, P7 in bit 7; reads actual bus states
chip.write_port(mask=0b00001111)                               # Write all 8 pins, (port=0, mask=int) → None
                                                               # sets P0–P3 low (output), P4–P7 high (input)

# --- Input pin ---
p4 = chip.pin(4)                                               # Get pin proxy, (n) → Pin
p4.init(Pcf8574Minimal.IN)                                     # Set direction input, (mode=IN) → None
                                                               # writes 1 to shadow bit 4 — enables internal pull-up
state = p4.value()                                             # Read actual level, () → int
                                                               # returns 0 if button pulls P4 low, 1 if floating high

# --- Interrupt-on-change (Full) ---
int_hw = Pin(5, Pin.IN, Pin.PULL_UP)                          # Hardware INT pin, (pin=5, mode=IN, pull=PULL_UP)

def on_change(changed_mask):                                   # Interrupt callback, (changed_mask: int) → None
    print("changed:", bin(changed_mask))

chip.configure_interrupt(int_hw, on_change)                    # Attach interrupt, (int_pin, callback) → None
                                                               # hooks IRQ_FALLING on int_hw; fires callback on any input change

changed = chip.clear_interrupt()                               # Read port and return changed bitmask, () → int
                                                               # compares current byte to previous read; clears INT line

time.sleep(1)
