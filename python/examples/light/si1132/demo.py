from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1132 import Si1132Full
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = Si1132Full(connection)                                          # Create Si1132 driver, (connection) → None

# --- Identify the chip and show the factory calibration ---
# The calibration bytes belong to the Silicon Labs Si114x calibration
# structure; they let you correct part-to-part variation of the UV index.
print('calibration', sensor.read_calibration())                          # Read factory calibration, () → list of 12 int

# --- UV and light monitor: 20 readings, 1 s apart ---
# Index, visible and infrared counts side by side show whether the
# light is sunlight (high infrared share) or artificial.
for _ in range(20):
    m = sensor.read()                                                    # Read UV index and ALS counts, () → dict
    print('UV %.2f  vis %d  ir %d' % (m['uv_index'], m['visible'], m['infrared']))
    print('uv %.2f vis %d ir %d' % (sensor.read_uv_index(), sensor.read_visible(), sensor.read_infrared()))  # Read single channels, () → float / int
    print('raw', sensor.read_raw())                                      # Read data registers, () → dict
    time.sleep(1)

# --- Effect of gain and of the high-range bits on the same light ---
# Each gain step doubles the integration time (and the count); the high
# range divides the sensitivity by 14.5 for direct sunlight.
for gain in (0, 2, 4):
    sensor.set_gain(visible=gain, infrared=gain)                         # Set ADC gain, (visible 0–7, infrared 0–7) → None
    print('gain', gain, sensor.read())                                   # Read UV index and ALS counts, () → dict
sensor.set_gain(0, 0)                                                    # Set ADC gain, (visible 0–7, infrared 0–7) → None
sensor.set_high_range(True, True)                                        # Set high signal range, (visible, infrared) → None
print('high range', sensor.read())                                       # Read UV index and ALS counts, () → dict
sensor.set_high_range(False, False)                                      # Set high signal range, (visible, infrared) → None
sensor.set_alignment(True, True)                                         # Set data alignment, (visible_low, infrared_low) → None
sensor.set_alignment(False, False)                                       # Set data alignment, (visible_low, infrared_low) → None
sensor.set_uv_coefficients((0x7B, 0x6B, 0x01, 0x00))                     # Set UV coefficients, (ucoef 4 bytes) → None

# --- Auxiliary channels ---
# The temperature value is relative only; the VDD count tracks the supply.
print('temperature raw', sensor.read_temperature_raw())                  # Read die temperature, () → int
print('vdd raw', sensor.read_vdd_raw())                                  # Read supply voltage, () → int

# --- Autonomous mode at 0.5 s with the interrupt ---
# Every completed measurement raises INT; the callback prints the status
# and then reads the fresh data registers.
def on_measurement(status):
    print('INT 0x%02X' % status, sensor.read_raw())                      # Read data registers, () → dict

sensor.start_autonomous(0.5)                                             # Start autonomous mode, (interval 0–2.05 s) → None
sensor.on_interrupt(on_measurement)                                      # Subscribe to measurements, (callback, int_pin=None) → None
time.sleep(5)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.pause()                                                           # Pause autonomous mode, () → None
print('ready', sensor.measurement_ready(), 'response', sensor.read_response())  # Check measurement done / read RESPONSE, () → bool / (counter, error)
sensor.enable_interrupt()                                                # Enable the INT pin, () → None
sensor.force()                                                           # Start a forced measurement, () → None
time.sleep(0.05)
print('poll', sensor.poll_interrupt())                                   # Poll interrupt status, () → int
sensor.disable_interrupt()                                               # Disable the INT pin, () → None

# --- UV only, then shut down ---
# Dropping the ALS channels shortens each measurement.
sensor.set_channels(True, False, False)                                  # Select channels, (uv, visible, infrared, aux=False) → None
print('UV only %.2f' % sensor.read_uv_index())                           # Read UV index, () → float
sensor.set_i2c_address(0x60)                                             # Set I²C address, (address 7-bit) → None
sensor.reset()                                                           # Reset the chip, () → None
