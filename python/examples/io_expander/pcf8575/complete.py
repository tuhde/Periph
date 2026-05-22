from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.pcf8575 import Pcf8575Minimal, Pcf8575Full
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)           # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Pcf8575Full(transport)                                  # Create PCF8575 full driver, (transport, addr=0x20)
                                                               # initialises all 16 pins as inputs; shadow = [0xFF, 0xFF]

# --- Direction and value via pin proxy ---
p0 = chip.pin(0)                                                # Get pin proxy, (n=0) → Pin
                                                               # returns proxy bound to P00; no bus transaction yet
p0.init(Pcf8575Minimal.OUT)                                     # Set direction output, (mode=OUT) → None
                                                               # drives P00 low (safe initial state for output)
p0.on()                                                         # Set high (release to input mode), () → None
                                                               # writes shadow[0] | (1 << 0) — quasi-high, not strong drive
p0.off()                                                        # Drive low, () → None
                                                               # writes shadow[0] & ~(1 << 0) — strong pull-down, up to 25 mA
p0.toggle()                                                     # Invert shadow bit, () → None
                                                               # flips the bit in the shadow register and writes both ports

v = p0.value()                                                  # Read actual pin level, () → int
                                                               # reads both port bytes, masks bit 0 of Port 0

p0.value(1)                                                     # Write pin, (x=0|1) → None
                                                               # equivalent to on(); updates shadow and writes both ports

# --- Port-level bulk read / write ---
mask0 = chip.read_port(0)                                       # Read Port 0, (port=0) → int bitmask
                                                               # P00 in bit 0, P07 in bit 7; reads actual bus states
mask1 = chip.read_port(1)                                       # Read Port 1, (port=1) → int bitmask
chip.write_port(0, mask=0b00001111)                             # Write Port 0, (port=0, mask=int) → None
                                                               # sets P00–P03 low (output), P04–P07 high (input)
chip.write_port(1, mask=0b00001111)                             # Write Port 1, (port=1, mask=int) → None

# --- Input pin ---
p8 = chip.pin(8)                                                # Get pin proxy, (n=8) → Pin
p8.init(Pcf8575Minimal.IN)                                      # Set direction input, (mode=IN) → None
                                                               # writes 1 to shadow bit 0 of Port 1 — enables internal pull-up
state = p8.value()                                              # Read actual level, () → int
                                                               # returns 0 if button pulls P10 low, 1 if floating high

# --- Interrupt-on-change (Full) ---
int_hw = Pin(5, Pin.IN, Pin.PULL_UP)                           # Hardware INT pin, (pin=5, mode=IN, pull=PULL_UP)

def on_change(changed_mask):                                    # Interrupt callback, (changed_mask: int) → None
    print("changed:", bin(changed_mask))

chip.configure_interrupt(int_hw, on_change)                   # Attach interrupt, (int_pin, callback) → None
                                                               # hooks IRQ_FALLING on int_hw; fires callback on any input change

changed = chip.clear_interrupt()                               # Read port and return 16-bit changed bitmask, () → int
                                                               # bits 0–7 = Port 0 changed, bits 8–15 = Port 1 changed

time.sleep(1)