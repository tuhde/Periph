import machine
import _testconfig as cfg
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Minimal
from machine import Pin

data_pin = Pin(cfg.DHT11_PIN, Pin.IN)
transport = DHTxxTransport(data_pin)
dht = DHT11Minimal(transport)                       # Create DHT11 driver, (transport)

for _ in range(5):
    t, h = dht.read()                              # Read temperature & humidity, () → (float °C, float %RH)
    print('{} C, {} %RH'.format(t, h))
    machine.sleep(2000)
print('===DONE: 0 passed, 0 failed===')
