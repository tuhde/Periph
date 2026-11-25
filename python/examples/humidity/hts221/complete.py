from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hts221 import HTS221Full
import time

connection = I2CConnection(0x5F)                                         # Create I²C connection, (addr=0x5F) → Connection
sensor = HTS221Full(connection)                                          # Create HTS221 driver, (connection) → None
                                                                         # reads the factory calibration and starts one-shot mode

print('who am i 0x%02X' % sensor.who_am_i())                             # Read WHO_AM_I, () → int (0xBC)
                                                                         # identifies the chip on the bus
m = sensor.read()                                                        # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %rH}
                                                                         # one-shot conversion, converted with the calibration
print('%.2f C %.1f %%rH' % (m['temperature_c'], m['humidity_pct']))
print('humidity %.1f %%rH' % sensor.read_humidity())                     # Read humidity only, () → float %rH
                                                                         # one-shot conversion, clamped to 0–100
print('temperature %.2f C' % sensor.read_temperature())                  # Read temperature only, () → float °C
                                                                         # one-shot conversion
print('raw humidity', sensor.read_raw_humidity())                        # Read raw humidity, () → int
                                                                         # signed 16-bit ADC output
print('raw temperature', sensor.read_raw_temperature())                  # Read raw temperature, () → int
                                                                         # signed 16-bit ADC output
print('calibration', sensor.read_calibration())                          # Read calibration, () → dict
                                                                         # decoded two-point coefficients
print('dew point %.2f C' % sensor.dew_point())                           # Compute dew point, () → float °C
                                                                         # Magnus formula over one measurement
print('status 0x%02X' % sensor.status())                                 # Read STATUS_REG, () → int
                                                                         # H_DA = 0x02, T_DA = 0x01
print('data ready:', sensor.data_ready())                                # Check data available, () → bool
                                                                         # both H_DA and T_DA set

sensor.set_output_data_rate(1)                                           # Set output data rate, (hz 0|1|7|12.5) → None
                                                                         # continuous sampling at 1 Hz
print('rate', sensor.get_output_data_rate(), 'Hz')                       # Get output data rate, () → float Hz
                                                                         # decodes CTRL_REG1.ODR
time.sleep(1.2)
c = sensor.read_continuous()                                             # Read latest sample, () → dict {temperature_c °C, humidity_pct %rH}
                                                                         # no conversion is started
print('continuous %.2f C %.1f %%rH' % (c['temperature_c'], c['humidity_pct']))
sensor.set_output_data_rate(0)                                           # Set output data rate, (hz 0|1|7|12.5) → None
                                                                         # back to one-shot mode

sensor.set_averaging(32, 64)                                             # Set averaging, (temperature_samples 2–256, humidity_samples 4–512) → None
                                                                         # more samples lower the noise and lengthen the conversion
print('averaging', sensor.get_averaging())                               # Get averaging, () → (int, int)
                                                                         # (temperature_samples, humidity_samples)
sensor.set_averaging(16, 32)
sensor.set_block_data_update(True)                                       # Set block data update, (enabled) → None
                                                                         # keeps LSB/MSB of one sample consistent

sensor.set_heater(True)                                                  # Switch the heater, (on) → None
                                                                         # drives off condensation; do not measure while on
print('heater on:', sensor.is_heater_on())                               # Heater state, () → bool
                                                                         # reads CTRL_REG2.HEATER
sensor.set_heater(False)

sensor.configure_drdy(True, active_high=True, open_drain=False)          # Configure DRDY pin, (enabled, active_high=True, open_drain=False) → None
                                                                         # routes data-ready to the DRDY pin
print('poll', sensor.poll_interrupt())                                   # Poll interrupt status, () → int
                                                                         # STATUS_REG; flags clear when data is read
sensor.configure_drdy(False)

sensor.power_down()                                                      # Power down, () → None
                                                                         # clears CTRL_REG1.PD
sensor.power_up()                                                        # Power up, () → None
                                                                         # sets CTRL_REG1.PD
sensor.on_interrupt(lambda status: print('DRDY status 0x%02X' % status)) # Subscribe to data-ready, (callback, int_pin=None) → None
                                                                         # edge on the pin or a polling thread
time.sleep(2)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
                                                                         # disables the DRDY output
sensor.reboot()                                                          # Reload trimming, () → None
                                                                         # sets BOOT, waits, re-reads the calibration
