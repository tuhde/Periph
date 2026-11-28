from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh62000 import RRH62000Full
import time

LABELS = ((2.0, 'very good'), (3.0, 'good'), (4.0, 'medium'), (5.0, 'poor'))


def iaq_label(iaq):
    for limit, text in LABELS:
        if iaq < limit:
            return text
    return 'bad'


connection = I2CConnection(0x69)                                         # Create I²C connection, (addr=0x69) → Connection
sensor = RRH62000Full(connection)                                        # Create RRH62000 driver, (connection, access_interval_ms=1000) → None
sensor.init()                                                            # Wait for start-up, check firmware version, () → None

# --- Identity and settings ---
# The module may only be accessed once per second; every call below waits for
# that interval, so this section takes several seconds.
print('firmware %d.%d' % sensor.firmware_version())                     # Firmware version, () → (major, minor)
print('algorithm %d.%d.%d' % sensor.algorithm_version())                # Algorithm version, () → (major, minor, patch)
print('unique ID', sensor.unique_id().hex())                             # Unique ID, () → bytes
print('moving average %d, fan %d %%, cleaning every %d s for %d s' % (
    sensor.get_moving_average(), sensor.get_fan_speed(),                 # Moving average, () → int / Fan speed, () → int %
    sensor.get_cleaning_interval(), sensor.get_cleaning_time()))         # Cleaning interval, () → int s / Cleaning time, () → int s

# --- Room air-quality station ---
# A complete sample arrives every 3 s. The IAQ index follows the German UBA
# scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
# first use before trusting TVOC and eCO2.
end = time.time() + 30
while time.time() < end:
    m = sensor.read_all()                                                # Complete block, () → dict
    print('IAQ %.2f (%s)  PM2.5 %.1f ug/m3  eCO2 %d ppm  TVOC %d ug/m3  %.1f C  %.1f %%RH' % (
        m['iaq'], iaq_label(m['iaq']), m['pm2_5_ug_m3'], m['eco2_ppm'], m['tvoc'],
        m['temperature_c'], m['humidity_rh']))

# --- Particle counts and status ---
# Number concentrations count particles from the bin size up to 10 µm per cm³.
p = sensor.read_particles()                                              # Particle values, () → dict
print('particles/cm3  >0.3um %.1f  >0.5um %.1f  >1um %.1f  >2.5um %.1f  >4um %.1f' % (
    p['nc_0_3'], p['nc_0_5'], p['nc_1'], p['nc_2_5'], p['nc_4']))
print('PM2.5 KCl %.1f / smoke %.1f ug/m3' % (p['pm2_5_ug_m3'], p['pm2_5_smoke_ug_m3']))
print('status', sensor.read_status())                                    # Status flags, () → dict
print('new data pending:', sensor.data_ready())                          # New sample available, () → bool

# --- Quieter, smoother operation ---
# 30 moving averages smooth the PM values (slower response); 70 % fan speed is
# quieter.
sensor.set_moving_average(30)                                            # Moving average, (count 1–60) → None
sensor.set_fan_speed(70)                                                 # Fan speed, (percent 60–100) → None
print('moving average %d, fan %d %%' % (sensor.get_moving_average(), sensor.get_fan_speed()))

# --- Fan cleaning ---
# A manual cleaning blows dust out of the air channel; the automatic cleaning
# is shortened to every 3 days for 10 s.
sensor.start_cleaning()                                                  # Start fan cleaning, () → None
for _ in range(30):
    if sensor.cleaning_done():                                           # Cleaning status, () → bool
        print('cleaning done')
        break
sensor.set_cleaning_interval(3 * 24 * 3600)                              # Cleaning interval, (seconds) → None
sensor.set_cleaning_time(10)                                             # Cleaning time, (seconds) → None
print('cleaning every %d s' % sensor.get_cleaning_interval())

# --- Sleep, diagnostics, reset ---
# Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
sensor.sleep()                                                           # Sleep, () → None
time.sleep(5)
sensor.wake()                                                            # Wake up, () → None
print('MOX resistance %d ohm' % sensor.read_mox_resistance())           # MOX resistance, () → int Ω
sensor.soft_reset()                                                      # Reset, () → None
print('after reset', sensor.read())                                      # Main values, () → dict
