import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.bme680 import BME680Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
bme = BME680Full(transport)                              # Create BME680 driver, (transport)
cid = bme.chip_id()                                     # Read chip ID, () → int
                                                         # returns 0x61 for BME680
bme.configure(osrs_t=1, osrs_p=1, osrs_h=1, mode=0, filter=0)  # Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1, filter 0–7) → None
                                                         # writes ctrl_hum, config, ctrl_meas in correct order
bme.set_oversampling(BME680Full.OSRS_X4, BME680Full.OSRS_X2, BME680Full.OSRS_X1)  # Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → None
                                                         # changes conversion time vs resolution trade-off
bme.set_filter(BME680Full.FILTER_7)                      # Set IIR filter, (coeff 0–7) → None
                                                         # applies to temperature and pressure only
bme.set_heater(320, 150)                                 # Configure heater profile 0, (temp_c, duration_ms) → None
                                                         # sets target temperature and on-time for gas measurement
bme.set_heater_profile(1, 200, 100)                      # Configure heater profile 1, (index 0–9, temp_c, duration_ms) → None
bme.select_heater_profile(0)                             # Select active profile, (index 0–9) → None
bme.set_gas_enabled(True)                                 # Enable gas conversion, (enabled) → None
bme.set_heater_off(False)                                # Control heater override, (off) → None
bme.set_ambient_temperature(25.0)                        # Override ambient for heater calc, (temp_c) → None
                                                         # re-applies the active heater profile
st = bme.status()                                       # Read status register, () → int
t, p, h, g = bme.read_all()                              # Read all sensors in one cycle, () → tuple
                                                         # returns (T, P, RH, R_gas) from single TPHG trigger
gv = bme.gas_valid()                                    # Check gas validity, () → bool
hs = bme.heater_stable()                                # Check heater stability, () → bool
bme.reset()                                              # Soft reset chip, () → None
                                                         # re-reads calibration and re-applies configuration
print('T={} C, P={} hPa, RH={} %, R_gas={} Ohm'.format(t, p, h, g))
print('===DONE: 0 passed, 0 failed===')
