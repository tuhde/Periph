import machine
import _testconfig as cfg
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Full
from machine import Pin

data_pin = Pin(cfg.DHT11_PIN, Pin.IN)
transport = DHTxxTransport(data_pin)
dht = DHT11Full(transport, 3)                       # Create DHT11 driver, (transport, max_retries=3)

# --- Indoor comfort monitor ---
# Reads temperature and humidity every 5 seconds and prints a one-line
# status with a comfort assessment. Demonstrates reliable real-world polling
# with retry-based error recovery.
def comfort(h):
    if h < 30.0: return 'dry'
    if h > 60.0: return 'humid'
    return 'comfortable'

for n in range(60):
    t, h = dht.read_retry(3)                        # Read with retries, (max_retries=3) → (float °C, float %RH)
    if h is None:
        # --- Handle read failure ---
        # After all retries are exhausted, log a warning and continue.
        # The next loop iteration will try again with a fresh sample.
        print('WARN: DHT11 read failed after retries')
    else:
        print('{} C, {} %RH, {}'.format(t, h, comfort(h)))
    machine.sleep(5000)
print('===DONE: 0 passed, 0 failed===')
