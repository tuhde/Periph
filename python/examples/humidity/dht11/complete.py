from machine import Pin
from periph.transport.dht11_micropython import DHT11Pin
from periph.chips.humidity.dht11 import DHT11Full

data_pin = Pin(4, Pin.IN, Pin.PULL_UP)                  # DATA pin with pull-up (machine.Pin)
pin = DHT11Pin(data_pin)                                # Wrap pin in DHT11 adapter, (machine.Pin) → DHT11Pin
dht = DHT11Full(pin)                                    # Create DHT11 driver, (data_pin) → DHT11Full

t = dht.read_temperature()                              # Read temperature, () → float °C
                                                        # returns the temperature portion of the 40-bit frame
h = dht.read_humidity()                                 # Read humidity, () → float %RH
                                                        # returns the humidity portion of the 40-bit frame
t2, h2 = dht.read_retry(max_retries=3)                  # Read with retry, (max_retries=3) → (float, float) °C, %RH
                                                        # retries up to 3 times on checksum or timeout errors
raw = dht.read_raw()                                    # Read raw 5-byte frame, () → bytes
                                                        # [hum_int, hum_dec, temp_int, temp_dec, checksum]
print("Temp:", t, "°C  Hum:", h, "%RH")
print("Retry:", t2, h2)
print("Raw:", raw)
