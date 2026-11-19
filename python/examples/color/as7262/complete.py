from periph.connection.i2c_auto import I2CConnection
from periph.chips.color.as7262 import AS7262Full
import time

connection = I2CConnection(0x49)                                         # Create I²C connection, (addr=0x49) → Connection
sensor = AS7262Full(connection)                                          # Create AS7262 driver, (connection) → None

print(sensor.read())                                                     # Read calibrated channels, () → dict {violet, blue, green, yellow, orange, red}
                                                                         # one one-shot conversion
print(sensor.read_raw())                                                 # Read raw counts, () → dict {violet, ..., red}
                                                                         # 16-bit counts
print(sensor.temperature())                                              # Read device temperature, () → float °C
sensor.measure()                                                         # Start a one-shot conversion, () → None
                                                                         # does not wait
while not sensor.data_ready():                                           # Check data ready, () → bool
    time.sleep(0.05)
print(sensor.read_calibrated())                                          # Read last calibrated results, () → dict
print(sensor.read_raw_last())                                            # Read last raw counts, () → dict
print(sensor.read_channel(5, True))                                      # Read one channel, (index, calibrated=True) → float | int
                                                                         # 0..5 = V, B, G, Y, O, R
sensor.set_gain(16)                                                      # Set gain, (gain) → None
                                                                         # 0-3 index or 1, 3.7, 16, 64
print(sensor.get_gain())                                                 # Get gain, () → float
sensor.set_integration_time(165.2)                                       # Set integration time, (ms) → None
                                                                         # 2.8 - 714 ms
print(sensor.get_integration_time())                                     # Get integration time, () → float ms
sensor.set_mode(3)                                                       # Set bank mode, (mode) → None
                                                                         # 0, 1, 2 continuous; 3 one-shot
print(sensor.get_mode())                                                 # Get bank mode, () → int
sensor.set_continuous(2)                                                 # Continuous conversions, (mode=2) → None
sensor.set_continuous(0)
sensor.set_mode(3)
sensor.set_interrupt(False)                                              # Enable/disable INT pin, (enabled) → None
sensor.set_led(True, 25)                                                 # Driver LED, (on, current_ma=12.5) → None
                                                                         # 12.5, 25, 50 or 100 mA
sensor.set_led(False)
sensor.set_indicator_led(True, 4)                                        # Indicator LED, (on, current_ma=1) → None
                                                                         # 1, 2, 4 or 8 mA
sensor.set_indicator_led(False)
print(sensor.hardware_version())                                         # Hardware version, () → (type, version)
print(sensor.firmware_version())                                         # Firmware version, () → (major, minor, sub)
print(hex(sensor.device_type()))                                         # Device type, () → int (0x40)
print(sensor.read_virtual(0x05))                                         # Read a virtual register, (reg) → int
sensor.write_virtual(0x05, 0x3B)                                         # Write a virtual register, (reg, value) → None


def on_data(status):
    print('data ready', sensor.read_calibrated())


sensor.set_continuous(2)
sensor.on_interrupt(on_data)                                             # Subscribe to INT, (callback, int_pin=None) → None
time.sleep(3)
print(sensor.poll_interrupt())                                           # Poll DATA_RDY, () → int
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.soft_reset()                                                      # Soft reset, () → None
                                                                         # restores defaults
