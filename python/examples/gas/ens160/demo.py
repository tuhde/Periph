from periph.transport.i2c_auto import I2CTransport
from periph.chips.gas.ens160 import ENS160Full
import time

AQI_LABELS = {1: 'Excellent', 2: 'Good', 3: 'Moderate', 4: 'Poor', 5: 'Unhealthy'}

transport = I2CTransport(0x52)                               # Create I²C transport, (addr=0x52) → Transport
sensor = ENS160Full(transport)                               # Create ENS160 driver, (transport)

# --- Wait for sensor warm-up ---
# The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
# reaches 0. During warm-up, readings are unreliable. The driver surfaces the
# status so the application can display progress to the user.
print('Waiting for sensor warm-up...')
while True:                                                   # Wait for valid data, () → blocks until warm
    try:
        sensor.read_air_quality()
        break
    except RuntimeError as e:
        print(str(e))
        time.sleep(1)
print('Sensor ready!')

# --- Set compensation from external sensor ---
# If an external temperature/humidity sensor is available, feeding its readings
# to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
# fixed 22C/45%RH as an example.
sensor.set_compensation(22.0, 45.0)                          # Set compensation, (temp_celsius=22.0, rh_percent=45.0) → None

# --- Indoor air quality monitoring loop ---
# Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
# AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
for n in range(60):
    data = sensor.read_air_quality()                         # Read air quality, () → dict {aqi, tvoc_ppb, eco2_ppm}
    aqi = data['aqi']
    label = AQI_LABELS.get(aqi, 'Unknown')
    print('{}s: AQI={} ({}) TVOC={} ppb eCO2={} ppm'.format(
        n, aqi, label, data['tvoc_ppb'], data['eco2_ppm']))
    time.sleep(1)
