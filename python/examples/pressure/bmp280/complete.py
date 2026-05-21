from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)         # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x76)                          # Create I2C transport, (i2c, addr=0x76)
chip = BMP280Full(transport, osrs_t=1, osrs_p=1)             # Create BMP280 full driver, (transport, osrs_t=1, osrs_p=1)

cid = chip.chip_id()                                        # Read chip ID, () → int
                                                          # returns 0x58 for BMP280
print('chip_id=0x{:02X}'.format(cid))

chip.set_oversampling(osrs_t=3, osrs_p=3)                    # Set oversampling, (osrs_t, osrs_p) → None
                                                          # ×4 temperature, ×4 pressure
chip.set_filter(BMP280Full.FILTER_8)                        # Set IIR filter, (coeff) → None
                                                          # coefficient 8 = 75% settling at ~9 samples

t = chip.temperature()                                      # Read temperature, () → float °C
p = chip.pressure()                                          # Read pressure, () → float hPa

alt = chip.altitude()                                      # Compute altitude, (sea_level_hpa=1013.25) → float m
                                                          # uses barometric formula: 44330 * (1 - (p/1013.25)^0.19)
slp = chip.sea_level_pressure(alt)                         # Compute sea-level pressure, (altitude_m) → float hPa
                                                          # inverts the altitude formula to find sea-level pressure

status = chip.status()                                      # Read status register, () → int
                                                          # STATUS_MEASURING=0x08, STATUS_IM_UPDATE=0x01
print('status=0x{:02X}'.format(status))

chip.reset()                                               # Soft reset chip, () → None
                                                          # re-reads calibration after reset

chip.configure(osrs_t=5, osrs_p=5, mode=BMP280Full.MODE_NORMAL,  # Configure all params, (osrs_t, osrs_p, mode, filter, t_sb) → None
              filter=BMP280Full.FILTER_16, t_sb=BMP280Full.T_SB_250_MS)
                                                          # ×16/×16, normal mode, filter coeff 16, 250ms standby

t2 = chip.temperature()                                     # Read temperature, () → float °C
p2 = chip.pressure()                                        # Read pressure, () → float hPa

print('T={:.2f}C  P={:.2f}hPa  alt={:.1f}m  slp={:.1f}hPa'.format(t2, p2, alt, slp))
print('===DONE: 0 passed, 0 failed===')