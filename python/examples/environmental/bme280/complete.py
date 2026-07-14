import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.bme280 import BME280Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
bme = BME280Full(transport)                            # Create BME280 driver, (transport, bus_type='i2c')
cid = bme.chip_id()                                    # Read chip ID, () → int
                                                         # returns 0x60 for BME280
bme.configure(osrs_t=1, osrs_p=1, osrs_h=1, mode=0, filter=0, t_sb=0)  # Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None
                                                         # writes ctrl_hum, config, ctrl_meas in correct order
bme.set_oversampling(BME280Full.OSRS_X4, BME280Full.OSRS_X2, BME280Full.OSRS_X1)  # Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → None
                                                         # humidity update requires ctrl_meas write to latch
bme.set_mode(BME280Full.MODE_FORCED)                  # Set power mode, (mode 0/1/3) → None
bme.set_filter(BME280Full.FILTER_4)                   # Set IIR filter, (coeff 0–4) → None
                                                         # suppresses short-term pressure disturbances
bme.set_standby(BME280Full.T_SB_125_MS)               # Set standby time, (t_sb 0–7) → None
                                                         # only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
st = bme.status()                                      # Read status register, () → int
t = bme.temperature()                                  # Read temperature, () → float °C
p = bme.pressure()                                     # Read pressure, () → float hPa
h = bme.humidity()                                     # Read humidity, () → float %RH
alt = bme.altitude()                                   # Compute altitude, (sea_level_hpa=1013.25) → float m
                                                         # uses barometric formula to convert pressure to metres
slp = bme.sea_level_pressure(alt)                      # Compute sea-level pressure, (altitude_m) → float hPa
dp = bme.dew_point()                                   # Compute dew point, () → float °C
                                                         # Magnus-Tetens approximation from current T and RH
bme.reset()                                            # Soft reset chip, () → None
                                                         # re-reads calibration and re-applies configuration
print('T={} C, P={} hPa, RH={} %RH, alt={} m, dp={} C'.format(t, p, h, alt, dp))
print('===DONE: 0 passed, 0 failed===')
