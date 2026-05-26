from machine import Pin
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Full

DATA_PIN = 4

transport = DHTxxTransport(Pin(DATA_PIN))  # Create DHTxx transport, (data_pin=Pin(GPIO) Pin.OUT)
dht = DHT11Full(transport)               # Create DHT11 full driver, (transport)

while True:
    temp = dht.read_temperature()         # Read temperature, () -> float C
    hum = dht.read_humidity()            # Read humidity, () -> float %RH
    print("Temperature: {:.1f} C, Humidity: {:.1f} %RH".format(temp, hum))

    raw = dht.read_raw()                  # Return raw 5-byte frame, () -> bytes
                                         # validates checksum; raises on mismatch
    print("Raw:", raw.hex())

    temp_retry, hum_retry = dht.read_retry(max_retries=3)  # Retry read, (max_retries=3 int) -> (float C, float %RH)
    print("Retry: {:.1f} C, {:.1f} %RH".format(temp_retry, hum_retry))
