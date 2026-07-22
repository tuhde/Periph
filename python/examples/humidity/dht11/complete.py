import machine
import _testconfig as cfg
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Full
from machine import Pin

data_pin = Pin(cfg.DHT11_PIN, Pin.IN)
transport = DHTxxTransport(data_pin)
dht = DHT11Full(transport, 3)                       # Create DHT11 driver, (transport, max_retries=3)

t = dht.read_temperature()                          # Read temperature, () → float °C
                                                     # returns a fresh conversion each call
h = dht.read_humidity()                             # Read humidity, () → float %RH
                                                     # returns a fresh conversion each call
t2, h2 = dht.read_retry(5)                          # Read with retries, (max_retries=5) → (float °C, float %RH)
                                                     # retries up to 5 times on checksum error
raw = dht.read_raw()                                # Read raw frame, () → bytes
                                                     # returns the validated 5-byte frame
print('t={} h={} retry_t={} raw[0]=0x{:02X}'.format(t, h, t2, raw[0]))
print('===DONE: 0 passed, 0 failed===')
