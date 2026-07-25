from periph.transport.dhtxx_auto import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Minimal

transport = DHTxxTransport(4)
dht = DHT11Minimal(transport)                       # Create DHT11 driver, (transport)

for _ in range(5):
    t, h = dht.read()                              # Read temperature & humidity, () → (float °C, float %RH)
    print('{} C, {} %RH'.format(t, h))
    import time; time.sleep(2)
print('===DONE: 0 passed, 0 failed===')
