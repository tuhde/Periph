from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hts221 import HTS221Full
import time

connection = I2CConnection(0x5F)                                         # Create I²C connection, (addr=0x5F) → Connection
sensor = HTS221Full(connection)                                          # Create HTS221 driver, (connection) → None

# --- Identify the chip and show the factory calibration ---
# Every HTS221 stores its own two-point calibration; the driver read it
# during construction and uses it for every conversion.
print('who am i 0x%02X' % sensor.who_am_i())                             # Read WHO_AM_I, () → int (0xBC)
print('calibration', sensor.read_calibration())                          # Read calibration, () → dict

# --- One-shot hygrometer: 10 readings, 2 s apart ---
# Each read() starts a conversion; the dew point shows what the pair means
# for condensation risk.
for _ in range(10):
    m = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %rH}
    print('%.2f C  %.1f %%rH  dew %.2f C' % (m['temperature_c'], m['humidity_pct'], sensor.dew_point()))  # Compute dew point, () → float °C
    print('raw', sensor.read_raw_humidity(), sensor.read_raw_temperature())  # Read raw outputs, () → int
    print('h %.1f t %.2f' % (sensor.read_humidity(), sensor.read_temperature()))  # Read single values, () → float
    time.sleep(2)

# --- Continuous sampling at 1 Hz and 12.5 Hz ---
# data_ready() paces the loop; the sample count per second follows the rate.
sensor.set_block_data_update(True)                                       # Set block data update, (enabled) → None
for hz in (1, 12.5):
    sensor.set_output_data_rate(hz)                                      # Set output data rate, (hz 0|1|7|12.5) → None
    print('rate', sensor.get_output_data_rate(), 'Hz')                   # Get output data rate, () → float Hz
    count = 0
    end = time.time() + 3
    while time.time() < end:
        if sensor.data_ready():                                          # Check data available, () → bool
            sensor.read_continuous()                                     # Read latest sample, () → dict
            count += 1
    print('%.1f samples/s' % (count / 3.0))
sensor.set_output_data_rate(0)                                           # Set output data rate, (hz 0|1|7|12.5) → None

# --- Noise at lowest and highest averaging ---
# Ten one-shot samples each; the spread of the raw humidity shrinks as the
# averaging grows.
for t_avg, h_avg in ((2, 4), (256, 512)):
    sensor.set_averaging(t_avg, h_avg)                                   # Set averaging, (temperature_samples, humidity_samples) → None
    print('averaging', sensor.get_averaging())                           # Get averaging, () → (int, int)
    raws = [sensor.read_raw_humidity() for _ in range(10)]               # Read raw humidity, () → int
    print('raw humidity spread', max(raws) - min(raws))
sensor.set_averaging(16, 32)                                             # Set averaging, (temperature_samples, humidity_samples) → None

# --- Heater: drive off condensation for 10 s ---
# Humidity falls and temperature rises while the heater runs; measure
# before and after, never during, then let the sensor cool.
before = sensor.read()                                                   # Read temperature and humidity, () → dict
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
print('heater on:', sensor.is_heater_on())                               # Heater state, () → bool
time.sleep(10)
sensor.set_heater(False)                                                 # Switch the heater, (on) → None
after = sensor.read()                                                    # Read temperature and humidity, () → dict
print('before', before, 'after', after)

# --- Data-ready notification for 5 s ---
# Uses the DRDY pin when the connection has one, otherwise a polling thread.
sensor.configure_drdy(True, active_high=True, open_drain=False)          # Configure DRDY pin, (enabled, active_high=True, open_drain=False) → None
sensor.set_output_data_rate(1)                                           # Set output data rate, (hz 0|1|7|12.5) → None
sensor.on_interrupt(lambda status: print('DRDY status 0x%02X' % status)) # Subscribe to data-ready, (callback, int_pin=None) → None
time.sleep(5)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
print('poll', sensor.poll_interrupt(), 'status', sensor.status())        # Poll interrupt status, () → int
sensor.set_output_data_rate(0)

# --- Power cycle and reload ---
sensor.power_down()                                                      # Power down, () → None
sensor.power_up()                                                        # Power up, () → None
sensor.reboot()                                                          # Reload trimming, () → None
print('done: %.2f C' % sensor.read()['temperature_c'])
