"""Demo for the PCA9541 — master failover for one of two redundant masters.

Prints the power-up registers (which reveal the /01 or /03 version), unmasks
all interrupts, takes the downstream bus with a bus initialization, checks the
INT wire with the functional test, hands the bus over, takes it back, toggles
the connection and then prints the ISTAT flags every 500 ms for 10 s (a
BUS_LOST callback means the other master took over).
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.other.pca9541 import PCA9541Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
selector = PCA9541Full(connection)                       # Create PCA9541 Full driver, (connection)

# --- Power-up state ---
# CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
print('IE, CONTROL, ISTAT:', selector.read_all())        # Read IE, CONTROL, ISTAT, () → (int, int, int)
print('status:', selector.status())                      # Decode CONTROL, () → dict

# --- Interrupts ---
selector.set_interrupt_mask()                            # Unmask all interrupts, (...) → None


def on_interrupt(status):
    if status & PCA9541Full.SOURCE_BUS_LOST:
        print('BUS_LOST: the other master took the bus')
    if status & PCA9541Full.SOURCE_TEST:
        print('MYTEST: the INT wire works')
    print('ISTAT', hex(status))


selector.on_interrupt(on_interrupt)                      # Subscribe, (callback(status), int_pin=None) → None

# --- Take the bus with a bus initialization ---
# The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
# completes a transfer the previous master left half-done.
print('recover_bus:', selector.recover_bus())            # Recover and take the bus, (timeout_ms=20) → bool
time.sleep(0.01)                                         # tBUF: bus-free time before talking downstream
# ... talk to a downstream device through a second Connection on the same upstream bus here ...

# --- INT wire check ---
selector.test_interrupt(own=True)                        # Drive this master's INT low, (own=False, other=False) → None
time.sleep(0.1)
selector.test_interrupt()                                # Clear the test, () → None

# --- Hand over and take back ---
selector.release()                                       # Give the bus to the other master, () → None
print('has_control after release:', selector.has_control())   # Report control, () → bool
print('acquire:', selector.acquire())                    # Take the downstream bus, (bus_init=False, timeout_ms=20) → bool
selector.disconnect()                                    # Isolate the downstream bus, () → None
selector.connect()                                       # Reconnect, () → None

# --- Watch the flags for 10 s ---
for _ in range(20):
    print('ISTAT', hex(selector.poll_interrupt()))       # Read and clear ISTAT, () → int
    time.sleep(0.5)

selector.off_interrupt()                                 # Unsubscribe, () → None
