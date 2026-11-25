"""Complete example for the PCA9541 — exercise every method in the public API.

Reads the registers, takes the bus (with and without bus initialization),
disconnects and reconnects the downstream bus, programs the interrupt masks,
runs the INT functional test and subscribes to this master's interrupt.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.other.pca9541 import PCA9541Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
selector = PCA9541Full(connection)                       # Create PCA9541 Full driver, (connection)
                                                         # reads CONTROL and ISTAT; writes nothing

print(selector.get_control())                            # Read the CONTROL byte, () → int
print(selector.status())                                 # Decode CONTROL, () → dict
                                                         # {has_control, connected, bus_init_requested, own_test, other_test}
print(selector.read_all())                               # Read IE, CONTROL, ISTAT, () → (int, int, int)
                                                         # one auto-increment read; clears the ISTAT flags

print(selector.acquire())                                # Take the downstream bus, (bus_init=False, timeout_ms=20) → bool
                                                         # reads CONTROL, writes MYBUS/BUSON per the datasheet rule
print(selector.recover_bus())                            # Recover and take the bus, (timeout_ms=20) → bool
                                                         # nine clocks, NACK and STOP on the downstream bus, then connect
print(selector.has_control())                            # Report control, () → bool
print(selector.is_connected())                           # Report the connection, () → bool
                                                         # BUSON != NBUSON: some master is connected

selector.disconnect()                                    # Isolate the downstream bus, () → None
                                                         # BUSON = NBUSON; ownership bits are left
selector.connect()                                       # Reconnect without changing ownership, () → None

selector.set_interrupt_mask(bus_lost=False, bus_not_idle=True)   # Write IE, (bus_lost, bus_not_idle, bus_init, int_in) → None
                                                         # True = masked, no interrupt
print(selector.get_interrupt_mask())                     # Read IE, () → int
selector.disable_interrupt(PCA9541Full.SOURCE_INT_IN)    # Mask one source, (source) → None
selector.enable_interrupt(PCA9541Full.SOURCE_INT_IN)     # Unmask one source, (source) → None

selector.on_interrupt(lambda status: print('interrupt', hex(status)))   # Subscribe, (callback(status), int_pin=None) → None
                                                         # falling edge on this master's INT, or a 100 ms poll
selector.test_interrupt(own=True)                        # Drive this master's INT low, (own=False, other=False) → None
                                                         # the bus bits are written back unchanged
time.sleep(0.2)
selector.test_interrupt()                                # Clear the test, () → None
print(selector.poll_interrupt())                         # Read and clear ISTAT, () → int
selector.off_interrupt()                                 # Unsubscribe, () → None

selector.release()                                       # Give the bus to the other master, () → None
