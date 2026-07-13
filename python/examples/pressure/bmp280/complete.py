import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
bmp = BMP280Full(transport)                              # Create BMP280 driver, (transport, bus_type='i2c')
cid = bmp.chip_id()                                     # Read chip ID, () → int
                                                         # returns 0x58 for BMP280
bmp.configure(osrs_t=1, osrs_p=1, mode=0, filter=0, t_sb=0)  # Configure chip, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None
                                                         # writes ctrl_meas and config registers
bmp.set_oversampling(BMP280Full.OSRS_X4, BMP280Full.OSRS_X2)  # Set oversampling, (osrs_t 0–5, osrs_p 0–5) → None
                                                         # changes conversion time vs resolution trade-off
bmp.set_mode(BMP280Full.MODE_FORCED)                     # Set power mode, (mode 0/1/3) → None
bmp.set_filter(BMP280Full.FILTER_4)                      # Set IIR filter, (coeff 0–4) → None
                                                         # suppresses short-term pressure disturbances
bmp.set_standby(BMP280Full.T_SB_125_MS)                  # Set standby time, (t_sb 0–7) → None
                                                         # only relevant in normal mode
st = bmp.status()                                       # Read status register, () → int
t = bmp.temperature()                                    # Read temperature, () → float °C
p = bmp.pressure()                                       # Read pressure, () → float hPa
alt = bmp.altitude()                                     # Compute altitude, (sea_level_hpa=1013.25) → float m
                                                         # uses barometric formula to convert pressure to metres
slp = bmp.sea_level_pressure(alt)                        # Compute sea-level pressure, (altitude_m) → float hPa
bmp.reset()                                              # Soft reset chip, () → None
                                                         # re-reads calibration and re-applies configuration
print('T={} C, P={} hPa, alt={} m, slp={} hPa'.format(t, p, alt, slp))
print('===DONE: 0 passed, 0 failed===')
