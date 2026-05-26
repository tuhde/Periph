from machine import Pin
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Minimal

DATA_PIN = 4

transport = DHTxxTransport(Pin(DATA_PIN))  # Create DHTxx transport, (data_pin=Pin(GPIO) Pin.OUT)
dht = DHT11Minimal(transport)               # Create DHT11 minimal driver, (transport)

while True:
    temp, hum = dht.read()                # Read temperature and humidity, () -> (float C, float %RH)
    print("Temperature: {:.1f} C, Humidity: {:.1f} %RH".format(temp, hum))
