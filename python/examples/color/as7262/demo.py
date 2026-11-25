from periph.connection.i2c_auto import I2CConnection
from periph.chips.color.as7262 import AS7262Full
import time

connection = I2CConnection(0x49)                                         # Create I²C connection, (addr=0x49) → Connection
sensor = AS7262Full(connection)                                          # Create AS7262 driver, (connection) → None

# --- Spectrometer ---
# Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
print(hex(sensor.device_type()), sensor.hardware_version(), sensor.firmware_version())  # Identity, () → int / tuple
print('chip %.0f C' % sensor.temperature())                              # Read device temperature, () → float °C

for gain in (1, 3.7, 16, 64):
    sensor.set_gain(gain)                                                # Set gain, (gain) → None
    print('gain', sensor.get_gain())                                     # Get gain, () → float
    print(' cal', sensor.read())                                         # Read calibrated, () → dict
    print(' raw', sensor.read_raw())                                     # Read raw, () → dict
sensor.set_gain(16)

for ms in (50, 500):
    sensor.set_integration_time(ms)                                      # Set integration time, (ms) → None
    t0 = time.time()
    sensor.read()
    print('integration', sensor.get_integration_time(), 'ms ->', round(time.time() - t0, 2), 's')
sensor.set_integration_time(165.2)

sensor.set_mode(3)                                                       # Set bank mode, (mode) → None
base = sensor.read()
sensor.set_indicator_led(True, 4)                                        # Indicator LED, (on, current_ma) → None
sensor.set_led(True, 25)                                                 # Driver LED, (on, current_ma) → None
lit = sensor.read()
sensor.set_led(False)
sensor.set_indicator_led(False)
diff = dict((k, lit[k] - base[k]) for k in lit)
print('reflectance', diff, 'dominant', max(diff, key=diff.get))

sensor.measure()                                                         # Start one-shot, () → None
while not sensor.data_ready():                                           # Data ready, () → bool
    time.sleep(0.05)
print(sensor.read_calibrated(), sensor.read_raw_last(), sensor.read_channel(0))  # Last results / one channel


def on_data(status):
    print(sensor.read_calibrated())


sensor.set_continuous(2)                                                 # Continuous mode, (mode=2) → None
sensor.set_interrupt(True)                                               # Enable INT, (enabled) → None
sensor.on_interrupt(on_data)                                             # Subscribe to INT, (callback, int_pin=None) → None
time.sleep(5)
print(sensor.poll_interrupt())                                           # Poll DATA_RDY, () → int
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.write_virtual(0x05, 0x3B)                                         # Write virtual register, (reg, value) → None
print(sensor.read_virtual(0x05))                                         # Read virtual register, (reg) → int
sensor.soft_reset()                                                      # Soft reset, () → None
