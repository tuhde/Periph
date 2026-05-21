from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Minimal, BMP280Full
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)            # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x76)                            # Create I2C transport, (i2c, addr=0x76)
chip = BMP280Full(transport)                                    # Create BMP280 full driver, (transport, addr=0x76, osrs_t=1, osrs_p=1, mode=1, filter=0, t_sb=0)
                                                                 # initialises calibration coefficients; default ×1/×1, forced mode, filter off

_id = chip.chip_id()                                          # Read chip ID, () → int
print("chip_id=0x{:02X}".format(_id))

_t = chip.temperature()                                       # Read temperature, () → float °C
_p = chip.pressure()                                         # Read pressure, () → float hPa
print("T={:.2f}°C  P={:.2f}hPa".format(_t, _p))

chip.set_oversampling(BMP280Full.OSRS_X2, BMP280Full.OSRS_X2)  # Update oversampling, (osrs_t=1–5, osrs_p=1–5) → None
chip.set_mode(BMP280Full.MODE_NORMAL)                           # Update power mode, (mode=0|1|3) → None
chip.set_filter(BMP280Full.FILTER_16)                          # Update IIR filter, (coeff=0–4) → None
chip.set_standby(BMP280Full.T_SB_500_MS)                       # Update standby, (t_sb=0–7) → None

_status = chip.status()                                       # Read status register, () → int
print("status=0x{:02X}".format(_status))

chip.configure(osrs_t=3, osrs_p=3, mode=1, filter=0)           # Update configuration, (osrs_t=0–5, osrs_p=0–5, mode=0|1|3, filter=0–4, t_sb=0–7) → None

_alt = chip.altitude()                                        # Compute altitude, (sea_level_hpa=1013.25) → float m
_slp = chip.sea_level_pressure(altitude_m=10)               # Compute sea-level pressure, (altitude_m) → float hPa
print("altitude={:.1f}m  sea_level={:.2f}hPa".format(_alt, _slp))

chip.reset()                                                  # Soft reset and re-read calibration, () → None
time.sleep(1)