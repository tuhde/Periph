from machine import Pin
import time
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Full

DATA_PIN = 4

# Indoor comfort monitor: read temperature and humidity every 5 seconds
# and print a one-line status showing both values plus a comfort assessment.
# Uses read_retry(max_retries=3) to handle occasional checksum errors gracefully.
# When a read fails after all retries, prints a warning and continues.
transport = DHTxxTransport(Pin(DATA_PIN))  # Create DHTxx transport, (data_pin=Pin(GPIO) Pin.OUT)
dht = DHT11Full(transport)               # Create DHT11 full driver, (transport)

while True:
    try:
        temp, hum = dht.read_retry(max_retries=3)  # Read with retry, (max_retries=3 int) -> (float C, float %RH)
    except ValueError as e:
        print("Warning: {}".format(e))               # all retries exhausted
        continue

    if hum < 30:
        comfort = "dry"
    elif hum <= 60:
        comfort = "comfortable"
    else:
        comfort = "humid"

    print("Temperature: {:.1f} C, Humidity: {:.1f} %RH -- {}".format(temp, hum, comfort))
    time.sleep(5)
