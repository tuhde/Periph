import _testconfig as cfg
import board
import digitalio
from periph.transport.dhtxx_circuitpython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Minimal, DHT11Full

passed = 0
failed = 0

def check_true(cond, label):
    global passed, failed
    if cond:
        print("PASS {}".format(label))
        passed += 1
    else:
        print("FAIL {}".format(label))
        failed += 1

pin = digitalio.DigitalInOut(board.DATA_PIN)
transport = DHTxxTransport(pin)
dht = DHT11Full(transport)

try:
    raw = dht.read_raw()
    checksum_ok = (raw[0] + raw[1] + raw[2] + raw[3]) & 0xFF == raw[4]
    check_true(checksum_ok, "checksum")
except Exception as e:
    print("FAIL checksum: {}".format(e))
    failed += 1

try:
    temp, hum = dht.read()
    check_true(temp > -40.0 and temp < 80.0, "temperature_range")
    check_true(hum >= 0.0 and hum <= 100.0, "humidity_range")
except Exception as e:
    print("FAIL read: {}".format(e))
    failed += 1

transport.close()
print("===DONE: {} passed, {} failed===".format(passed, failed))
