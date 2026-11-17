from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht10 import AHT10Full
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
aht = AHT10Full(connection)                                             # Create AHT10 driver, (connection) → None

# --- Verify calibration before starting the logging session ---
# The driver already ran the init command (0xE1) and retried once via soft
# reset if the CAL bit was not set, so this should print True.
print('Calibrated:', aht.is_calibrated())                              # Check calibration status, () → bool

t_min = rh_min = 1e9
t_max = rh_max = -1e9
print('%-8s %-10s %-10s %-10s' % ('Sample', 'T (C)', 'RH (%)', 'Dew (C)'))
for n in range(12):
    # --- One measurement takes > 75 ms ---
    # The driver waits 80 ms after the trigger and polls BUSY, so the
    # calls below simply block until the data is valid.
    r = aht.read()                                                     # Trigger measurement, () → dict {temperature_c, humidity_pct}
    dew = aht.dew_point()                                              # Dew point, () → float °C
    t, rh = r['temperature_c'], r['humidity_pct']
    t_min, t_max = min(t_min, t), max(t_max, t)
    rh_min, rh_max = min(rh_min, rh), max(rh_max, rh)
    print('%-8d %-10.2f %-10.2f %-10.2f' % (n, t, rh, dew))
    # --- Keep the interval >= 1 s ---
    # Measuring more often self-heats the sensor and biases the reading.
    time.sleep(5)

print('T min/max: %.2f / %.2f C   RH min/max: %.2f / %.2f %%' % (t_min, t_max, rh_min, rh_max))

aht.soft_reset()                                                       # Send soft reset command, () → None
                                                                       # sends 0xBA, waits 20 ms, re-runs the init command
print('Calibrated after reset:', aht.is_calibrated())                  # Check calibration status, () → bool
