from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.ccs811 import CCS811Full
import time

connection = I2CConnection(0x5A)                                         # Create I²C connection, (addr=0x5A) → Connection
sensor = CCS811Full(connection)                                          # Create CCS811 driver, (connection) → None
sensor.init()                                                            # Check HW_ID, start application, mode 1, () → None

# --- Identity ---
# HW_ID 0x81 identifies the CCS81x family; the firmware versions tell which
# algorithm revision computes eCO2 and TVOC.
print('hardware', sensor.hardware_version())                             # Hardware ID and version, () → (hw_id, hw_version)
print('firmware', sensor.firmware_version())                             # Firmware versions, () → dict {boot, app}
print('status 0x%02X, error 0x%02X' % (sensor.status(), sensor.error())) # STATUS and ERROR_ID, () → int


def classify(eco2):
    if eco2 < 1000:
        return 'good'
    if eco2 <= 1500:
        return 'fair'
    return 'poor'


# --- Air-quality monitor: 30 s at 1 Hz ---
# Mode 1 delivers a sample every second. Values settle after about 20 min of
# warm-up; a brand-new sensor needs a 48 h burn-in.
for i in range(30):
    m = sensor.read()                                                    # Next sample, () → dict {eco2_ppm ppm, tvoc_ppb ppb}
    print('%2d s  eCO2 %4d ppm  TVOC %4d ppb  %s' % (i, m['eco2_ppm'], m['tvoc_ppb'], classify(m['eco2_ppm'])))
print('eCO2 %d ppm' % sensor.read_eco2())                                # eCO2 only, () → int ppm
print('TVOC %d ppb' % sensor.read_tvoc())                                # TVOC only, () → int ppb

# --- Environment compensation ---
# Feeding the real temperature and humidity (e.g. from a BME280) corrects the
# algorithm; without it the chip assumes 25 °C and 50 %RH.
sensor.set_environment(40.0, 23.0)                                       # Compensation data, (humidity_rh %, temperature_c °C) → None
v_ref, v_ntc = sensor.read_ntc()                                         # NTC voltages, () → (v_ref_mv mV, v_ntc_mv mV)
if v_ref:
    print('NTC %.0f ohm' % sensor.ntc_resistance(10000))                 # NTC resistance, (r_ref_ohm Ω) → float Ω
else:
    print('no NTC circuit fitted')

# --- Threshold interrupt: only report zone changes ---
# With INT_THRESH the chip raises nINT only when eCO2 crosses 1000 or
# 2000 ppm by more than the hysteresis. Polling STATUS here shows each sample.
sensor.set_thresholds(1000, 2000, 50)                                    # Zone boundaries, (low_medium_ppm, medium_high_ppm, hysteresis_ppm) → None
sensor.enable_interrupt(CCS811Full.SOURCE_THRESHOLD)                     # Threshold interrupt, (source) → None
for _ in range(5):
    while not sensor.poll_interrupt() & CCS811Full.STATUS_DATA_READY:    # STATUS, () → int
        time.sleep(0.1)
    print('event', sensor.read())                                        # Reading releases nINT, () → dict
sensor.disable_interrupt(CCS811Full.SOURCE_THRESHOLD)                    # Threshold interrupt off, (source) → None

# --- Data-ready callback ---
# The callback gets the STATUS byte; without an INT pin the driver polls.
sensor.enable_interrupt(CCS811Full.SOURCE_DATA_READY)                    # Data-ready interrupt, (source) → None
sensor.on_interrupt(lambda st: print('callback 0x%02X' % st, sensor.read()))  # Subscribe, (callback, int_pin=None) → None
time.sleep(3)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.disable_interrupt(CCS811Full.SOURCE_DATA_READY)                   # Data-ready interrupt off, (source) → None

# --- Raw sensor data ---
# The MOx sensor's current and voltage give its resistance; the algorithm
# derives eCO2 and TVOC from it.
current, voltage = sensor.read_raw()                                     # Raw data, () → (current_ua µA, voltage_v V)
print('raw %d uA, %.3f V, %.0f ohm' % (current, voltage, voltage / (current * 1e-6) if current else 0))

# --- Slower sampling: mode 2 (every 10 s) ---
# Pulse heating saves power; pass through idle when changing modes.
sensor.set_mode(0)                                                       # Idle, (mode) → None
sensor.set_mode(2)                                                       # Every 10 s, (mode) → None
for _ in range(2):
    print('mode 2', sensor.read(), 'ready', sensor.data_ready())         # Next sample, () → dict; ready flag, () → bool
sensor.set_mode(0)                                                       # Idle, (mode) → None
sensor.set_mode(1)                                                       # Back to every second, (mode) → None

# --- Baseline ---
# Save the baseline daily and restore it after a power cycle, but only after
# about 20 min of warm-up; otherwise the chip relearns it from scratch.
baseline = sensor.get_baseline()                                         # Baseline, () → int
print('baseline 0x%04X' % baseline)
sensor.set_baseline(baseline)                                            # Restore baseline, (baseline) → None

# --- Wrap up ---
# A soft reset puts the chip into boot mode; the driver starts the
# application again in mode 1.
sensor.soft_reset()                                                      # Software reset, () → None
print('status after reset 0x%02X' % sensor.status())                    # STATUS, () → int
