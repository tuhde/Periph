from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1145 import Si1145Full
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = Si1145Full(connection)                                          # Create Si1145 driver, (connection) → None
sensor.init()                                                            # Check PART_ID, HW_KEY, UV + ALS + PS1, () → None

# --- Identity and calibration ---
# PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL
# returns the factory calibration bytes.
part_id, rev_id, seq_id = sensor.read_id()                               # Identity registers, () → (part_id, rev_id, seq_id)
print('PART_ID 0x%02X  REV_ID 0x%02X  SEQ_ID 0x%02X' % (part_id, rev_id, seq_id))
print('calibration', sensor.read_calibration())                          # GET_CAL, () → list[int]
print('response', sensor.read_response())                                # RESPONSE, () → (counter, error)

# --- Proximity, UV index and light for 10 s ---
# Proximity counts rise as a hand approaches the LED/sensor window.
for i in range(10):
    m = sensor.read()                                                    # Forced PS + ALS, () → dict {proximity, uv_index, visible, infrared}
    print('%2d s  prox %5d  UV %5.2f  vis %5d  ir %5d'
          % (i, m['proximity'], m['uv_index'], m['visible'], m['infrared']))
    time.sleep(1)

# --- LED current and photodiode ---
# Hold a hand ~5 cm above the sensor: higher LED current gives more counts,
# the small photodiode about a tenth of the large one.
input('Hold a hand above the sensor and press Enter ')
for level in (1, 4, 8, 15):
    sensor.set_led_current(1, level)                                     # LED drive, (led, level) → None
    print('LED level %2d -> %d' % (level, sensor.read_proximity()))      # Forced PS, (channel=1) → int counts
sensor.set_led_current(1, 3)
for large in (False, True):
    sensor.set_ps_photodiode(1, large)                                   # PS photodiode, (channel, large) → None
    print('%s photodiode -> %d' % ('large' if large else 'small', sensor.read_proximity()))
sensor.set_ps_led(1, 0x01)                                               # PS LED select, (channel, leds) → None

# --- Gain and high range ---
# Each gain step doubles the integration time; the high range divides the
# sensitivity by 14.5 for direct sunlight.
for gain in (0, 2, 4):
    sensor.set_gain(visible=gain, infrared=gain, proximity=0)            # ADC gains, (visible, infrared, proximity) → None
    print('gain %d  vis %d  ir %d' % (gain, sensor.read_visible(), sensor.read_infrared()))  # Forced ALS, () → int counts
sensor.set_gain()
sensor.set_high_range(True, True, False)                                 # Signal range, (visible, infrared, proximity) → None
print('high range  vis %d  UV %.2f' % (sensor.read_visible(), sensor.read_uv_index()))  # Forced ALS, () → int / float
sensor.set_high_range(False, False, False)
sensor.set_alignment()                                                   # Result alignment, (...) → None
sensor.set_uv_coefficients(Si1145Full.UCOEF_DEFAULT)                     # UCOEF0..3, (ucoef) → None

# --- Autonomous mode with interrupt events and a proximity switch ---
# PS + ALS every 100 ms; each finished measurement raises INT (or is polled).
THRESHOLD = 2000
sensor.set_channels(True, True, True, True)                              # CHLIST, (uv, visible, infrared, ps1, ...) → None
events = []
sensor.enable_interrupt(Si1145Full.SOURCE_PS1)                           # INT_CFG + IRQ_ENABLE, (source) → None
sensor.on_interrupt(events.append)                                       # Subscribe, (callback, int_pin=None) → None
sensor.start_autonomous(0.1)                                             # Autonomous mode, (interval s, proximity, als) → None
near = False
for _ in range(50):
    ps1 = sensor.read_raw()['ps1']                                       # Data registers, () → dict
    if (ps1 > THRESHOLD) != near:
        near = ps1 > THRESHOLD
        print('output', 'ON' if near else 'OFF', '(prox %d)' % ps1)
    time.sleep(0.1)
sensor.pause()                                                           # PSALS_PAUSE, () → None
sensor.off_interrupt()                                                   # Unsubscribe, () → None
print('%d interrupt events, last 0x%02X' % (len(events), events[-1] if events else 0))
sensor.disable_interrupt(Si1145Full.SOURCE_PS1)                          # IRQ_ENABLE clear, (source) → None
sensor.poll_interrupt()                                                  # IRQ_STATUS, () → int sources

# --- Auxiliary channels ---
print('temperature raw %d  VDD raw %d'
      % (sensor.read_temperature_raw(), sensor.read_vdd_raw()))          # AUX channel, () → int counts
sensor.force(proximity=False, als=True)                                  # ALS_FORCE without waiting, (proximity, als) → None
time.sleep(0.05)
print('raw', sensor.read_raw())                                          # Data registers, () → dict

# --- Address and reset ---
sensor.set_i2c_address(0x60)                                             # I2C_ADDR + BUSADDR, (address) → None
sensor.reset()                                                           # RESET + HW_KEY + defaults, () → None
print('after reset', sensor.read())
