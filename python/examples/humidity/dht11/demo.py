from machine import Pin
from periph.transport.dht11_micropython import DHT11Pin
from periph.chips.humidity.dht11 import DHT11Full
import time

data_pin = Pin(4, Pin.IN, Pin.PULL_UP)                  # DATA pin with pull-up (machine.Pin)
pin = DHT11Pin(data_pin)                                # Wrap pin in DHT11 adapter, (machine.Pin) → DHT11Pin
dht = DHT11Full(pin)                                    # Create DHT11 driver, (data_pin) → DHT11Full

# --- Indoor comfort monitor ---
# Poll the sensor every 5 seconds, print a one-line status with a comfort
# assessment, and use read_retry() so a single dropped bit does not abort
# the loop.
while True:
    try:
        t, h = dht.read_retry(max_retries=3)            # Read with retry, (max_retries=3) → (float, float) °C, %RH
        if h < 30:
            comfort = "dry"
        elif h <= 60:
            comfort = "comfortable"
        else:
            comfort = "humid"
        print("T=%.1f °C  H=%.1f %%RH  (%s)" % (t, h, comfort))
    except OSError as e:
        print("read failed:", e)
    time.sleep(5)
