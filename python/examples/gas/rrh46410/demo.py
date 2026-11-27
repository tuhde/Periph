from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh46410 import RRH46410Full
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
sensor = RRH46410Full(connection)                                        # Create RRH46410 driver, (connection) → None
sensor.init()                                                            # Check product ID, start IAQ 2nd Gen mode, () → None

# --- Identity ---
# Get Product ID returns 0x2310 and the firmware version; the tracking number
# identifies the individual module.
pid, fw = sensor.product_id()                                            # Product ID and firmware, () → (product_id, (major, minor, patch))
print('product 0x%04X, firmware %d.%d.%d' % (pid, fw[0], fw[1], fw[2]))
print('tracking', ''.join('%02X' % b for b in sensor.tracking_number()))  # Tracking number, () → bytes
print('mode', sensor.get_mode())                                         # Operation mode, () → int

# --- Humidity compensation ---
# The MOx element reacts to humidity; feeding the real value (e.g. from a
# humidity sensor) improves the results. The module assumes 50 %RH.
sensor.set_humidity(42.0)                                                # Ambient humidity, (percent %RH) → None

# --- Indoor air-quality monitor ---
# A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
# warm-up during which the values stay fixed.
warmup = sensor.warmup_samples()                                         # Warm-up length, (mode=None) → int samples
print('sample period %.0f s, warm-up %d samples' % (sensor.sample_period(), warmup))  # Sample period, (mode=None) → float s
for _ in range(20):
    r = sensor.read()                                                    # Next sample, () → dict
    progress = 'ok' if r['warmed_up'] else 'warm-up %3d%%' % min(100, r['sample'] * 100 // warmup)
    print('IAQ %.1f (%s)  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %4d ppm  rel %d  %s' % (
        r['iaq'], sensor.air_quality_label(r['iaq']), r['tvoc_mg_m3'],     # UBA rating, (iaq) → str
        r['etoh_ppm'], r['eco2_ppm'], r['relative_iaq'], progress))

# --- Interrupt instead of polling ---
# With INT wired the driver reacts to its rising edge; otherwise it polls the
# sample counter. The callback receives the decoded result.
sensor.on_interrupt(lambda r: print('INT sample %d IAQ %.1f' % (r['sample'], r['iaq'])))  # Subscribe, (callback, int_pin=None) → None
time.sleep(10)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
while not sensor.poll_interrupt():                                       # New sample pending, () → bool
    time.sleep(0.5)
if sensor.wait_for_sample(4.0):                                          # Wait for a new sample, (timeout s) → bool
    print('latest', sensor.read_results())                               # Latest result, () → dict or None

# --- Other operation modes ---
# PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
# one sample every 90 s. Each mode change restarts the warm-up.
for mode, name in ((RRH46410Full.MODE_PBAQ, 'PBAQ'), (RRH46410Full.MODE_ULP, 'ULP')):
    sensor.set_mode(mode)                                                # Operation mode, (mode) → None
    print('%s: sample every %.0f s, warm-up %d samples (%.0f min)' % (
        name, sensor.sample_period(), sensor.warmup_samples(),           # Mode timing, (mode=None) → float s / int
        sensor.sample_period() * sensor.warmup_samples() / 60))
sensor.set_mode(RRH46410Full.MODE_PBAQ)                                  # Operation mode, (mode) → None
print('PBAQ', sensor.read())                                             # Next sample, () → dict {tvoc_mg_m3, etoh_ppm, sample, warmed_up}

# --- GPIOs ---
# GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
sensor.config_gpio(0x03)                                                 # Both outputs, (directions) → None
for _ in range(3):
    sensor.set_gpio(0x01)                                                # GPIO1 high, (mask) → None
    time.sleep(0.2)
    sensor.clear_gpio(0x01)                                              # GPIO1 low, (mask) → None
    time.sleep(0.2)
print('GPIO levels', bin(sensor.get_gpio()))                             # GPIO levels, () → int
print('status', sensor.status())                                         # Last status byte, () → dict
print('raw mode', sensor.send_command(0x10, b'', 1)[0])                  # Raw request, (command, params, response_len) → bytes

# --- Wrap up ---
# clean_sensor(confirm=True) runs the 1-minute cleaning once in the sensor's
# lifetime; it is skipped here. Suspend stops measuring; reset restarts the
# module in SUSPEND.
sensor.suspend()                                                         # Suspend mode, () → None
sensor.reset()                                                           # Reset, () → None
print('mode after reset', sensor.get_mode())                             # Operation mode, () → int
