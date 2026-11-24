from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl3115a2 import MPL3115A2Full
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = MPL3115A2Full(connection)                                       # Create MPL3115A2 driver, (connection) → None
sensor.init()                                                            # Reset and apply defaults, () → None

print('who_am_i 0x%02X' % sensor.who_am_i())                             # Read WHO_AM_I, () → int (0xC4)
sensor.set_oversample(32)                                                # Set oversample ratio, (ratio 1/2/4/…/128) → None
print('oversample', sensor.get_oversample())                             # Get oversample ratio, () → int
sensor.set_sea_level_pressure(101325)                                    # Set sea-level pressure, (pa float) → None
sensor.set_offsets(0.0, 0.0, 0)                                          # Trim offsets, (pressure_pa float, temperature_c float, altitude_m int) → None

print(sensor.read_pressure(), sensor.read_temperature())                 # One-shot values, () → float Pa / float °C
print(sensor.read())                                                     # Both together, () → dict {pressure_pa, temperature_c}
print(sensor.read_altitude())                                            # Altimeter reading, () → float m
sensor.set_mode(False)                                                   # Back to barometer, (altimeter bool) → None

sensor.start_continuous(0)                                               # Periodic acquisition every 1 s, (step_exp 0-15) → None
print('active', sensor.is_active())                                      # Check ACTIVE mode, () → bool
print(sensor.read_continuous())                                          # Wait for PTDR and read, () → dict {value, temperature_c}
print(sensor.data_ready(), sensor.read_delta())                          # Data ready / change since previous, () → bool / (float, float)
sensor.stop_continuous()                                                 # Back to STANDBY, () → None

sensor.set_target(100000, 200, -5, 3)                                    # Threshold targets, (value, window, temperature_c int, temperature_window int) → None
sensor.load_current_as_target()                                          # Use the current output as target, () → None
print(sensor.read_min_max())                                             # Captured extremes, () → dict {p_min, p_max, t_min, t_max}
sensor.clear_min_max()                                                   # Clear extremes, () → None

sensor.fifo_setup(MPL3115A2Full.FIFO_CIRCULAR, 16)                       # Configure FIFO, (mode 0/1/2, watermark 0-63) → None
print(sensor.fifo_status())                                              # FIFO state, () → (overflow bool, watermark bool, count int)
print(sensor.fifo_read())                                                # Drain FIFO, () → list of (value, temperature_c)
sensor.fifo_setup(MPL3115A2Full.FIFO_OFF)                                # FIFO off, (mode, watermark=0) → None

sensor.set_interrupt_pins(False, False, False, False)                    # INT pin polarity / drive, (polarity1, open_drain1, polarity2, open_drain2) → None
sensor.enable_interrupt(MPL3115A2Full.SOURCE_DRDY, 1)                    # Route data-ready to INT1, (source, pin 1/2) → None
print('INT_SOURCE 0x%02X' % sensor.poll_interrupt())                     # Read INT_SOURCE, () → int
sensor.on_interrupt(lambda status: print('INT 0x%02X' % status), 1)      # Subscribe, (callback, pin=1, int_pin=None) → None
time.sleep(1)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.disable_interrupt(MPL3115A2Full.SOURCE_DRDY)                      # Disable the source, (source) → None
sensor.reset()                                                           # Software reset and defaults, () → None
print('===DONE: 0 passed, 0 failed===')
