from machine import Pin
from periph.transport.dht11_micropython import DHT11Pin
from periph.chips.humidity.dht11 import DHT11Minimal

data_pin = Pin(4, Pin.IN, Pin.PULL_UP)                  # DATA pin with pull-up (machine.Pin)
pin = DHT11Pin(data_pin)                                # Wrap pin in DHT11 adapter, (machine.Pin) → DHT11Pin
dht = DHT11Minimal(pin)                                 # Create DHT11 driver, (data_pin) → DHT11Minimal

temp, hum = dht.read()                                  # Read temperature and humidity, () → (float, float) °C, %RH
print("Temp:", temp, "°C  Hum:", hum, "%RH")
