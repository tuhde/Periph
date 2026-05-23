from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.magnetometer.as5600 import AS5600Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x36)
as5600 = AS5600Full(transport)                             # Create AS5600 driver, (transport) → AS5600Full

# --- Status and magnet checks ---
print(as5600.is_magnet_detected())                       # Check magnet present, () → bool
print(as5600.is_magnet_too_strong())                     # Check magnet too strong, () → bool
print(as5600.is_magnet_too_weak())                       # Check magnet too weak, () → bool
print(hex(as5600.status_byte()))                         # Read raw status, () → int

# --- Angle readings ---
print(as5600.angle())                                    # Read absolute angle, () → float degrees
print(as5600.angle_raw())                                # Read scaled angle count, () → int 0-4095
print(as5600.raw_angle())                                # Read raw unscaled angle, () → int 0-4095
print(as5600.raw_angle_degrees())                        # Read raw angle in degrees, () → float degrees

# --- Diagnostics ---
print(as5600.agc())                                      # Read AGC value, () → int
print(as5600.magnitude())                                # Read CORDIC magnitude, () → int

# --- Position configuration (volatile) ---
print(as5600.zero_position())                            # Read ZPOS, () → int 0-4095
print(as5600.max_position())                             # Read MPOS, () → int 0-4095
print(as5600.max_angle())                                # Read MANG, () → int 0-4095

as5600.set_zero_position(0)                              # Set zero position, (pos 0-4095) → None
                                                         # writes ZPOS_H/L to volatile RAM
as5600.set_max_position(4095)                            # Set max position, (pos 0-4095) → None
                                                         # writes MPOS_H/L to volatile RAM
as5600.set_max_angle(2048)                               # Set max angle span, (span 0-4095) → None
                                                         # writes MANG_H/L to volatile RAM (≥18° required)

# --- Configure power mode and output ---
as5600.configure(pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=False)  # Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → None
                                                         # preserves reserved CONF_H[7:6] bits

# --- Burn count ---
print(as5600.burn_count())                               # Read burn count, () → int 0-3
