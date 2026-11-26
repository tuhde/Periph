from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.ccs811 import CCS811Full
import time

connection = I2CConnection(0x5A)                                         # Create I²C connection, (addr=0x5A) → Connection
# Bind the connection to the chip's default address (ADDR low).
sensor = CCS811Full(connection)                                          # Create CCS811 driver, (connection) → None
# Constructor stores the connection only; no bus traffic yet.
sensor.init()                                                            # Check HW_ID, start application, mode 1, () → None
# Verifies HW_ID 0x81, writes APP_START if the chip is in boot mode, selects 1 s sampling.

print(sensor.hardware_version())                                         # Hardware ID and version, () → (hw_id, hw_version)
# HW_ID is 0x81, HW_VERSION 0x1X.
print(sensor.firmware_version())                                         # Firmware versions, () → dict {boot: (major, minor, trivial), app: (...)}
# Bootloader and application firmware, each major.minor.trivial.
print(hex(sensor.status()))                                              # STATUS register, () → int
# Bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR.
print(hex(sensor.error()))                                               # ERROR_ID register, () → int
# Zero unless STATUS.ERROR is set.

print(sensor.read())                                                     # Next sample, () → dict {eco2_ppm ppm, tvoc_ppb ppb}
# Waits for DATA_READY, then reads eCO2 and TVOC.
print(sensor.read_eco2())                                                # eCO2 only, () → int ppm
# Equivalent CO2 estimated from the VOC level, 400–8192 ppm.
print(sensor.read_tvoc())                                                # TVOC only, () → int ppb
# Total volatile organic compounds, 0–1187 ppb.
print(sensor.read_raw())                                                 # Raw sensor data, () → (current_ua µA, voltage_v V)
# Heater-side sense current and the voltage across the sensor.
print(sensor.data_ready())                                               # New sample waiting, () → bool
# False right after a read, because reading the result clears DATA_READY.

sensor.set_environment(45.0, 22.5)                                       # Compensation data, (humidity_rh %, temperature_c °C) → None
# Real ambient values improve accuracy; the chip assumes 50 %RH and 25 °C otherwise.
print(sensor.read_ntc())                                                 # NTC circuit voltages, () → (v_ref_mv mV, v_ntc_mv mV)
# Only meaningful with a reference resistor and NTC wired to the AUX pin.
try:
    print(sensor.ntc_resistance(100000))                                 # NTC resistance, (r_ref_ohm Ω) → float Ω
    # V_NTC × R_REF / V_REF; map it to °C with the thermistor's curve.
except OSError as e:
    print('no NTC circuit:', e)

sensor.set_thresholds(1000, 2000, 50)                                    # Threshold boundaries, (low_medium_ppm=1500, medium_high_ppm=2500, hysteresis_ppm=50) → None
# eCO2 zones used by the threshold interrupt.
baseline = sensor.get_baseline()                                         # Algorithm baseline, () → int
# Save it to non-volatile storage about once a day.
print(hex(baseline))
sensor.set_baseline(baseline)                                            # Restore baseline, (baseline) → None
# Restore a saved value only after about 20 min of warm-up.

sensor.enable_interrupt(CCS811Full.SOURCE_DATA_READY)                    # nINT on new data, (source) → None
# Sets INT_DATARDY and keeps the drive mode.
sensor.on_interrupt(lambda st: print('interrupt status 0x%02X' % st, sensor.read()))  # Subscribe, (callback, int_pin=None) → None
# Without an INT pin the driver polls STATUS; reading the sample releases nINT.
time.sleep(3)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
# Stops the pin handler or polling thread.
print(hex(sensor.poll_interrupt()))                                      # STATUS without releasing nINT, () → int
# DATA_READY shows whether a sample is pending.
sensor.disable_interrupt(CCS811Full.SOURCE_DATA_READY)                   # nINT off, (source) → None
# Clears INT_DATARDY.

sensor.set_mode(4)                                                       # Drive mode, (mode 0–4) → None
# Mode 4: raw data every 250 ms, no eCO2/TVOC updates.
time.sleep(0.5)
print(sensor.read_raw())                                                 # Raw sensor data, () → (current_ua µA, voltage_v V)
# RAW_DATA keeps updating in mode 4.
sensor.set_mode(0)                                                       # Drive mode, (mode 0–4) → None
# Idle before switching to another mode.
sensor.set_mode(1)                                                       # Drive mode, (mode 0–4) → None
# Back to one sample per second.

sensor.soft_reset()                                                      # Software reset, () → None
# SW_RESET into boot mode, then APP_START and mode 1 again.
