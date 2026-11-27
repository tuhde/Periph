from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh46410 import RRH46410Full
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
# The module has the fixed address 0x38 and stretches the I²C clock.
sensor = RRH46410Full(connection)                                        # Create RRH46410 driver, (connection) → None
# Constructor stores the connection only; no bus traffic yet.
sensor.init()                                                            # Check product ID, start IAQ 2nd Gen mode, () → None
# Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

print(sensor.product_id())                                               # Product ID and firmware, () → (product_id, (major, minor, patch))
# The product ID is always 0x2310.
print(sensor.tracking_number())                                          # ASIC tracking number, () → bytes (6)
# Unique per module.
print(sensor.get_mode())                                                 # Operation mode, () → int
# 1 = IAQ 2nd Gen after init.

sensor.set_humidity(45.0)                                                # Ambient humidity, (percent %RH) → None
# Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
print(sensor.read())                                                     # Next sample, () → dict {iaq, tvoc_mg_m3 mg/m³, etoh_ppm ppm, eco2_ppm ppm, relative_iaq, sample, warmed_up}
# Waits for the sample counter to change; values are fixed during the warm-up.
print(sensor.wait_for_sample(4.0))                                       # Wait for a new sample, (timeout s) → bool
# True when the counter changed (or INT went high) within the timeout.
print(sensor.read_results())                                             # Latest result, () → dict or None
# Does not wait; None before the first sample of the mode.
print(sensor.air_quality_label(2.4))                                     # UBA rating, (iaq) → str
# "very good", "good", "medium", "poor" or "bad".
print(sensor.sample_period(), sensor.warmup_samples())                   # Mode timing, (mode=None) → float s / int
# 3 s and 100 samples in IAQ 2nd Gen mode.
print(sensor.status())                                                   # Last status byte, () → dict
# Error bits and the operation-mode error code (0xA = sensor damaged).

sensor.on_interrupt(lambda r: print('sample', r['sample'], r['iaq']))    # Subscribe, (callback, int_pin=None) → None
# Rising edge on INT, or a polling thread comparing the sample counter.
time.sleep(7)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
# Stops the pin handler or polling thread.
print(sensor.poll_interrupt())                                           # New sample pending, () → bool
# INT level if wired, otherwise a counter comparison.

sensor.set_mode(RRH46410Full.MODE_PBAQ)                                  # Operation mode, (mode) → None
# PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
print(sensor.read())                                                     # Next sample, () → dict {tvoc_mg_m3, etoh_ppm, sample, warmed_up}
# PBAQ results have a finer resolution (raw / 1000).
sensor.set_mode(RRH46410Full.MODE_ULP)                                   # Operation mode, (mode) → None
# ULP: one sample every 90 s at 0.23 mW.
print(sensor.sample_period(), sensor.warmup_samples())                   # Mode timing, (mode=None) → float s / int
# 90 s and 10 samples.

sensor.config_gpio(0x03)                                                 # GPIO directions, (directions) → None
# Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
sensor.set_gpio(0x01)                                                    # Drive high, (mask) → None
# GPIO1 high.
print(bin(sensor.get_gpio()))                                            # GPIO levels, () → int
# Bits 0–3 = GPIO1–GPIO4.
sensor.clear_gpio(0x01)                                                  # Drive low, (mask) → None
# GPIO1 low.

print(sensor.send_command(0x10, b'', 1))                                 # Raw request, (command, params=b'', response_len=0) → bytes
# Checksums added and verified; here Get Operation Mode.
# sensor.clean_sensor(confirm=True)                                      # One-time cleaning, (confirm=False) → None
# Runs for one minute, once in the sensor's lifetime; left disabled here.
sensor.suspend()                                                         # Suspend mode, () → None
# Stops measuring, about 0.7 µA.
sensor.reset()                                                           # Reset, () → None
# Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
