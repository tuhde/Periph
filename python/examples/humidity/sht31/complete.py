from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.sht31 import SHT31Full
import time

connection = I2CConnection(0x44)                                         # Create I²C connection, (addr=0x44) → Connection
sensor = SHT31Full(connection)                                           # Create SHT31 driver, (connection) → None

t, rh = sensor.read()                                                    # Read temperature and humidity, () → (float °C, float %RH)
                                                                         # single shot, high repeatability, CRC checked
print(t, rh)
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
raw_t, raw_rh = sensor.read_raw()                                        # Read raw words, () → (int, int)
                                                                         # 16-bit temperature and humidity ticks
print(raw_t, raw_rh)

sensor.set_repeatability('low')                                          # Select repeatability, (level) → None
                                                                         # 'low' / 'medium' / 'high' (4 / 6 / 15 ms)
print(sensor.read())
sensor.set_repeatability('high')
sensor.set_clock_stretching(False)                                       # Use clock-stretching commands, (on) → None
                                                                         # needs bus-layer support; off polls the NACKed read header

sensor.start_periodic(2, 'high')                                         # Start periodic mode, (rate, repeatability='high', art=False) → None
                                                                         # rate 0.5 / 1 / 2 / 4 / 10 mps; art=True selects the 4 mps ART mode
time.sleep(1.0)
print(sensor.fetch())                                                    # Fetch the latest periodic result, () → (float, float) or None
                                                                         # None while the read header is NACKed
sensor.stop_periodic()                                                   # Stop periodic mode, () → None
                                                                         # break command 0x3093, waits 1 ms

sensor.set_heater(True)                                                  # Switch the heater, (on) → None
                                                                         # about 5-25 mW; readings are unusable while on
print(hex(sensor.read_status()))                                         # Read status register, () → int
                                                                         # CRC verified; bit 13 = heater on
sensor.set_heater(False)
sensor.clear_status()                                                    # Clear status flags, () → None
                                                                         # command 0x3041
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement, Magnus formula
sensor.soft_reset()                                                      # Soft reset, () → None
                                                                         # 0x30A2, waits 1 ms, back to single shot
