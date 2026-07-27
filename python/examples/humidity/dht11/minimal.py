from periph.connection.dhtxx_auto import DHTxxConnection
from periph.chips.humidity.dht11 import DHT11Minimal

connection = DHTxxConnection(4)
dht = DHT11Minimal(connection)                       # Create DHT11 driver, (connection)

for _ in range(5):
    t, h = dht.read()                              # Read temperature & humidity, () → (float °C, float %RH)
    print('{} C, {} %RH'.format(t, h))
    import time; time.sleep(2)
print('===DONE: 0 passed, 0 failed===')
