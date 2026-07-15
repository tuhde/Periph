from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.light.apds9960 import APDS9960Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x39)
apds = APDS9960Full(transport)                             # Create APDS9960 driver, (transport) → APDS9960Full

print(hex(apds.chip_id()))                                 # Read device ID, () → int
c, r, g, b = apds.color()                                  # Read all RGBC channels, () → tuple(int, int, int, int)
                                                           # burst read 0x94-0x9B latches all channels atomically
print(apds.color_clear())                                  # Read clear channel, () → int 0-65535
print(apds.color_red())                                    # Read red channel, () → int 0-65535
print(apds.color_green())                                  # Read green channel, () → int 0-65535
print(apds.color_blue())                                   # Read blue channel, () → int 0-65535

apds.configure_als(0xB6, 1)                                # Configure ALS, (atime 0-255, again 0-3) → None
                                                           # sets integration time and gain for the ALS/color engine
apds.configure_wait(0xFF, long=False)                      # Configure wait, (wtime 0-255, long=False) → None
                                                           # sets idle period between measurement cycles
apds.enable_wait(True)                                     # Enable wait engine, (enabled) → None

apds.enable_proximity(True)                                # Enable proximity engine, (enabled) → None
apds.configure_proximity_led(0, 0, 0, 1)                   # Configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → None
                                                           # sets LED drive strength, gain, pulse count and length
apds.set_led_boost(0)                                      # Set LED boost, (boost 0-3) → None
                                                           # multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
print(apds.proximity())                                    # Read proximity count, () → int 0-255

apds.als_threshold(100, 60000)                             # Set ALS thresholds, (low 0-65535, high 0-65535) → None
apds.proximity_threshold(10, 200)                          # Set proximity thresholds, (low 0-255, high 0-255) → None
apds.set_persistence(0, 1)                                 # Set persistence, (ppers 0-15, apers 0-15) → None

apds.enable_als_interrupt(True)                            # Enable ALS interrupt, (enabled) → None
apds.enable_proximity_interrupt(True)                      # Enable proximity interrupt, (enabled) → None
apds.clear_als_interrupt()                                 # Clear ALS interrupt, () → None
apds.clear_proximity_interrupt()                           # Clear proximity interrupt, () → None
apds.clear_all_interrupts()                                # Clear all interrupts, () → None

apds.set_proximity_offset(10, -5)                          # Set proximity offset, (ur -127..127, dl -127..127) → None
                                                           # sign-magnitude encoding compensates for optical crosstalk
apds.set_proximity_mask(False, False, False, False)        # Set proximity mask, (u, d, l, r) → None

apds.enable_gesture(True)                                  # Enable gesture engine, (enabled) → None
apds.configure_gesture(1, 0, 0, 1, 1, 50, 20)             # Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → None
                                                           # sets gain, LED drive, pulse, wait time, entry/exit thresholds
print(apds.gesture_available())                            # Check gesture data, () → bool
print(apds.gesture_fifo_level())                           # Read FIFO level, () → int
print(apds.read_gesture_fifo())                            # Read gesture FIFO, () → list[(U,D,L,R)]
apds.clear_gesture_fifo()                                  # Clear gesture FIFO, () → None
apds.enable_gesture_interrupt(False)                       # Enable gesture interrupt, (enabled) → None
apds.enable_gesture(False)                                 # Disable gesture engine, (enabled) → None

print(apds.status())                                       # Read STATUS register, () → int
print(apds.is_als_valid())                                 # Check ALS data valid, () → bool
print(apds.is_proximity_valid())                           # Check proximity valid, () → bool
print(apds.is_als_saturated())                             # Check ALS saturated, () → bool
print(apds.is_proximity_saturated())                       # Check proximity saturated, () → bool
