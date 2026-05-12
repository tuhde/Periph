import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp180 import BMP180Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, 0x77)
bmp = BMP180Full(transport)                             # Create BMP180 driver, (transport, oss=0)
cid = bmp.chip_id()                                    # Read chip ID, () → int
                                                     # returns 0x55 for BMP180
oss = bmp.oversampling()                               # Read OSS, () → int 0–3
bmp.set_oversampling(BMP180Full.OSS_STANDARD)           # Set OSS, (oss 0–3) → None
                                                     # changes conversion time vs resolution trade-off
t = bmp.temperature()                                  # Read temperature, () → float C
p = bmp.pressure()                                     # Read pressure, () → float hPa
alt = bmp.altitude()                                  # Compute altitude, (sea_level_hpa=1013.25) → float m
                                                     # uses barometric formula to convert pressure to metres
slp = bmp.sea_level_pressure(alt)                      # Compute sea-level pressure, (altitude_m) → float hPa
bmp.reset()                                           # Soft reset chip, () → None
                                                     # re-reads calibration after reset
print('T={} C, P={} hPa, alt={} m, slp={} hPa'.format(t, p, alt, slp))
print('===DONE: 0 passed, 0 failed===')
