import os
from periph.transport.dht11_linux import DHT11Pin
from periph.chips.humidity.dht11 import DHT11Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def check_range(label, value, lo, hi):
    check_true('%s in [%s, %s]' % (label, lo, hi), lo <= value <= hi)


chip_path = os.environ.get('GPIO_CHIP', '/dev/gpiochip0')
line      = int(os.environ.get('DATA_LINE', '4'))

pin = DHT11Pin(chip_path, line)
dht = DHT11Full(pin)

try:
    t = dht.read_temperature()
    check_range('read_temperature', t, -20.0, 60.0)

    h = dht.read_humidity()
    check_range('read_humidity', h, 0.0, 100.0)

    t2, h2 = dht.read_retry(max_retries=3)
    check_range('read_retry temperature', t2, -20.0, 60.0)
    check_range('read_retry humidity',    h2,  0.0, 100.0)

    raw = dht.read_raw()
    check_true('read_raw is bytes',    isinstance(raw, bytes))
    check_true('read_raw length is 5', len(raw) == 5)
    checksum = (raw[0] + raw[1] + raw[2] + raw[3]) & 0xFF
    check_true('read_raw checksum OK', checksum == raw[4])
finally:
    pin.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
