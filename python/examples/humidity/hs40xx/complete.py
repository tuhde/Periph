from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs40xx import HS40xxFull
import time

connection = I2CConnection(0x54)                                         # Create I²C connection, (addr=0x54) → Connection
sensor = HS40xxFull(connection)                                          # Create HS40xx driver, (connection) → None

d = sensor.read()                                                        # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
                                                                         # no-hold 0xF5, 5 bytes, CRC verified, one retry on mismatch
print(d['temperature_c'], d['humidity_pct'])
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
                                                                         # no-hold 0xF3, 3 bytes
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
print(sensor.read_hold())                                                # Read with clock stretching, () → dict {temperature_c, humidity_pct}
                                                                         # 0xE5; needs a stretching controller and SCL >= 200 kHz
print(sensor.read_raw())                                                 # Read raw values, () → (int, int)
                                                                         # (humidity, temperature); 14-bit
sensor.set_resolution(12, 12)                                            # Set resolution, (humidity_bits=None, temperature_bits=None) → None
                                                                         # 8, 10, 12 or 14 bit; None leaves a channel unchanged
print(sensor.get_resolution())                                           # Get resolution, () → (int, int)
                                                                         # (humidity_bits, temperature_bits)
print(sensor.read_register(0x00))                                        # Read a register, (reg) → int
sensor.write_register(0x00, 0x0F)                                        # Write a register, (reg, value) → None
                                                                         # 0x0F = 14 bit for both channels (default)
sensor.set_alert_thresholds(rh_high=60.0, rh_high_reset=55.0,            # Set alert thresholds, (rh_high, rh_high_reset, rh_low, rh_low_reset, temp_high, temp_high_reset, temp_low, temp_low_reset) → None
                            temp_high=40.0, temp_high_reset=38.0)        # values in %RH / °C; None leaves a threshold unchanged
sensor.set_alert_polarity(True)                                          # Set ALERT polarity, (active_high) → None
sensor.enable_interrupt(sensor.SOURCE_RH_HIGH | sensor.SOURCE_TEMP_HIGH) # Enable alert sources, (source) → None
sensor.start_periodic(1)                                                 # Start periodic mode, (period_s) → None
                                                                         # 0.5, 1 or 2.5 s
time.sleep(1.5)
print(sensor.read_periodic())                                            # Fetch the latest periodic result, () → dict {temperature_c, humidity_pct, status}
print(sensor.poll_interrupt())                                           # Read alert status, () → int
                                                                         # SOURCE_* bitmask


def on_alert(status):
    print('alert', status)


sensor.on_interrupt(on_alert)                                            # Subscribe to the ALERT pin, (callback, int_pin=None) → None
time.sleep(5)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.disable_interrupt(sensor.SOURCE_RH_HIGH | sensor.SOURCE_TEMP_HIGH)  # Disable alert sources, (source) → None
sensor.stop_periodic()                                                   # Stop periodic mode, () → None
print(hex(sensor.read_id()))                                             # Read the 32-bit sensor ID, () → int
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement, Magnus formula
sensor.soft_reset()                                                      # Soft reset, () → None
                                                                         # stops periodic mode, waits 500 ms
