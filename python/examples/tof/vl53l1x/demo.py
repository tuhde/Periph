"""Demo for the VL53L1X — long-range doorway people counter with a split ROI.

The sensor hangs overhead in a doorway (up to 2.5 m). Two narrow 8x16 ROIs
(centre SPADs 167 and 231, the left/right half-array centres used by ST's
own people-counting code) form two virtual beams. Each zone learns its floor
distance, then counts as occupied when something is more than 300 mm
closer. The order in which the zones become occupied tells IN from OUT.
Every 30 s a signal/ambient snapshot is printed; strong sunlight switches to
short distance mode. After 60 s or 50 events the full ROI is restored.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.tof.vl53l1x import VL53L1XFull, I2C_ADDRESS, DISTANCE_MODE_LONG, DISTANCE_MODE_SHORT

ZONE_CENTRES = (167, 231)   # left, right
OCCUPIED_MM = 300
MAX_EVENTS = 50
MAX_SECONDS = 60
SNAPSHOT_S = 30
BRIGHT_MCPS = 5.0

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = VL53L1XFull(connection)                         # Create VL53L1X Full driver, (connection)

# --- Two virtual beams ---
# Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps the
# two zones fast enough to catch a walking person. An 8x16 ROI covers one
# half of the SPAD array, so alternating the ROI centre alternates beams.
sensor.set_distance_mode(DISTANCE_MODE_LONG)             # Set distance mode, (mode) → None
sensor.set_timing_budget(33000)                          # Set timing budget, (budget_us µs) → None
sensor.set_roi(8, 16)                                    # Set ROI size, (width SPADs, height SPADs) → None
print('optical centre SPAD', sensor.optical_center(), '- zone centres', ZONE_CENTRES)  # Read optical-centre SPAD, () → int


def measure(zone):
    sensor.set_roi_center(ZONE_CENTRES[zone])            # Set ROI centre, (spad) → None
    d = sensor.distance()                                # Measure distance, () → int mm
    return d if sensor.range_valid() else None           # Check last measurement, () → bool


# --- Learn the empty doorway ---
# Twenty readings per zone give the floor (or opposite frame) distance each
# beam sees when nobody is there; invalid readings are ignored.
baseline = []
for zone in (0, 1):
    values = [v for v in (measure(zone) for _ in range(20)) if v is not None]
    baseline.append(sum(values) / len(values) if values else 4000)
print('baseline left {:.0f} mm, right {:.0f} mm'.format(baseline[0], baseline[1]))

# --- Count crossings ---
# A person entering blocks the left beam first, then the right one (and the
# reverse when leaving). Once both beams clear again, the recorded order
# decides the direction.
count_in = 0
count_out = 0
events = 0
sequence = []
start = time.ticks_ms()
last_snapshot = start
while events < MAX_EVENTS and time.ticks_diff(time.ticks_ms(), start) < MAX_SECONDS * 1000:
    readings = [measure(0), measure(1)]
    occupied = [r is not None and r < baseline[z] - OCCUPIED_MM for z, r in enumerate(readings)]
    for zone in (0, 1):
        if occupied[zone] and zone not in sequence:
            sequence.append(zone)
    if not any(occupied) and sequence:
        if sequence == [0, 1]:
            count_in += 1
        elif sequence == [1, 0]:
            count_out += 1
        events += 1
        print('IN {} OUT {} (left {}, right {})'.format(count_in, count_out, readings[0], readings[1]))
        sequence = []

    # --- Watch the light ---
    # Sunlight through an open door raises the ambient rate and eats long-mode
    # range; short mode keeps working up to ~1.3 m under strong ambient light.
    if time.ticks_diff(time.ticks_ms(), last_snapshot) >= SNAPSHOT_S * 1000:
        last_snapshot = time.ticks_ms()
        sensor.distance()                                # Measure distance, () → int mm
        m = sensor.read_measurement()                    # Read result block, () → dict
        print('signal {:.2f} MCPS, ambient {:.2f} MCPS'.format(m['signal_rate_mcps'], m['ambient_rate_mcps']))
        if m['ambient_rate_mcps'] > BRIGHT_MCPS and sensor.distance_mode() == DISTANCE_MODE_LONG:  # Read distance mode, () → str
            sensor.set_distance_mode(DISTANCE_MODE_SHORT)  # Set distance mode, (mode) → None
            print('bright ambient light: switched to short distance mode')

# --- Restore the full field of view ---
sensor.set_roi(16, 16)                                   # Set ROI size, (width SPADs, height SPADs) → None
print('done: IN {} OUT {}'.format(count_in, count_out))
