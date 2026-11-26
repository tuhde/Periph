from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.spl06 import SPL06Full
import time

connection = I2CConnection(0x77)                                         # Create I²C connection, (addr=0x77) → Connection
# Bind the connection to the chip's default address (SDO high or open).
sensor = SPL06Full(connection)                                           # Create SPL06 driver, (connection) → None
# Constructor stores the connection only; no bus traffic yet.
sensor.init()                                                            # Check ID, read coefficients, apply defaults, () → None
# Verifies ID 0x10, waits for COEF_RDY, sets 16× pressure / 1× external temperature.

print(sensor.coefficients())                                             # Decoded calibration, () → dict {c0, c1, c00, c10, c01, c11, c20, c21, c30}
# The nine factory coefficients used by every compensation.
print(sensor.read())                                                     # Temperature and pressure, () → dict {temperature_c °C, pressure_pa Pa}
# Command mode: one temperature, then one pressure measurement.
print(sensor.read_pressure())                                            # Pressure, () → float Pa
# Measures temperature first, because the compensation needs it.
print(sensor.read_temperature())                                         # Temperature, () → float °C
# One temperature measurement only.
praw, traw = sensor.read_raw()                                           # Raw results, () → (praw int, traw int)
# Signed 24-bit values straight from the result registers.
print(sensor.compensate(praw, traw, 16, 1))                              # Compensate raw values, (praw, traw, p_oversampling, t_oversampling) → dict {temperature_c, pressure_pa}
# Pure computation with the stored coefficients; no bus access.
print(sensor.altitude(101325.0))                                         # Altitude, (sea_level_pa=101325.0) → float m
# International barometric formula against the given sea-level pressure.

sensor.set_pressure_config(64, 4)                                        # Pressure oversampling and rate, (oversampling=16, rate=1) → None
# 64× oversampling, 4 background readings per second; sets P_SHIFT.
sensor.set_temperature_config(2, 4)                                      # Temperature oversampling and rate, (oversampling=1, rate=1) → None
# 2× oversampling, 4 per second; always keeps the external sensor.
sensor.start_continuous(True, True)                                      # Background mode, (pressure=True, temperature=True) → None
# MEAS_CTRL = 111: the chip measures both on its own.
print(sensor.read_continuous())                                          # Latest background result, () → dict {temperature_c, pressure_pa}
# Waits for both ready flags; does not start a measurement.
print(sensor.data_ready())                                               # Ready flags, () → (pressure_ready bool, temperature_ready bool)
# MEAS_CFG.PRS_RDY and TMP_RDY.
sensor.stop()                                                            # Standby, () → None
# MEAS_CTRL = 000.

sensor.enable_fifo()                                                     # FIFO on, () → None
# Background results now queue in the 32-entry FIFO.
sensor.start_continuous()                                                # Background mode, (pressure=True, temperature=True) → None
# Fill the FIFO for a second.
time.sleep(1)
sensor.stop()                                                            # Standby, () → None
# Stop adding entries.
print(sensor.fifo_status())                                              # FIFO state, () → (empty bool, full bool)
# FIFO_STS flags.
print(sensor.read_fifo())                                                # Drain FIFO, () → list of (kind str, value float)
# "pressure" in Pa or "temperature" in °C, in measured order.
sensor.flush_fifo()                                                      # Flush FIFO, () → None
# Discards any remaining entries.
sensor.disable_fifo()                                                    # FIFO off, () → None
# Results go to the result registers again.

sensor.enable_interrupt(SPL06Full.SOURCE_PRESSURE)                       # Enable interrupt source, (source) → None
# Pressure-ready interrupt on SDO (active low).
print(sensor.poll_interrupt())                                           # Read and clear INT_STS, () → int
# Bitmask of SOURCE_PRESSURE / SOURCE_TEMPERATURE / SOURCE_FIFO_FULL.
sensor.on_interrupt(lambda status: print('INT 0x%02X' % status))         # Subscribe, (callback, int_pin=None) → None
# Uses the connection's INT pin, or a polling thread without one.
sensor.read()                                                            # Trigger a measurement, () → dict
# The pressure result raises the interrupt.
time.sleep(0.1)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
# Removes this driver's handler only.
sensor.disable_interrupt(SPL06Full.SOURCE_PRESSURE)                      # Disable interrupt source, (source) → None
# Clears CFG_REG.INT_PRS.

sensor.reset()                                                           # Soft reset, () → None
# RESET = 0x09, re-reads the coefficients and restores the init defaults.
