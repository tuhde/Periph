import _testconfig as cfg
from periph.transport.dhtxx_micropython import DHTxxTransport
from periph.chips.humidity.dht11 import DHT11Minimal, DHT11Full
from machine import Pin

passed = 0
failed = 0


class MockTransport:
    """Mock DHTxx transport that returns preset frames in response to read()."""
    def __init__(self, frame):
        self._frame = frame

    def read(self):
        return self._frame


# Test 1: Example from datasheet, expected: 53.0%RH, 24.4°C
frame1 = bytes([0x35, 0x00, 0x18, 0x04, 0x51])
mock = MockTransport(frame1)
sensor = DHT11Minimal(mock)
t, h = sensor.read()
if abs(t - 24.4) < 0.001 and abs(h - 53.0) < 0.001:
    print('PASS decode_datasheet_example')
    passed += 1
else:
    print('FAIL decode_datasheet_example: t={} h={}'.format(t, h))
    failed += 1

# Test 2: Negative temperature
frame2 = bytes([0x20, 0x00, 0x0A, 0x81, 0xAB])
mock2 = MockTransport(frame2)
sensor2 = DHT11Minimal(mock2)
t2, h2 = sensor2.read()
if abs(t2 - (-10.1)) < 0.001 and abs(h2 - 32.0) < 0.001:
    print('PASS decode_negative_temperature')
    passed += 1
else:
    print('FAIL decode_negative_temperature: t={} h={}'.format(t2, h2))
    failed += 1

# Test 3: Checksum error
bad_frame = bytes([0x35, 0x00, 0x18, 0x04, 0x00])
mock3 = MockTransport(bad_frame)
sensor3 = DHT11Minimal(mock3)
err = None
try:
    sensor3.read()
except Exception as e:
    err = e
if err is not None:
    print('PASS checksum_error_raises')
    passed += 1
else:
    print('FAIL checksum_error_raises: expected exception')
    failed += 1

# Test 4: Full read_temperature
mock4 = MockTransport(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))
sensor4 = DHT11Full(mock4, max_retries=3)
if abs(sensor4.read_temperature() - 24.4) < 0.001:
    print('PASS read_temperature')
    passed += 1
else:
    print('FAIL read_temperature')
    failed += 1

# Test 5: Full read_humidity
if abs(sensor4.read_humidity() - 53.0) < 0.001:
    print('PASS read_humidity')
    passed += 1
else:
    print('FAIL read_humidity')
    failed += 1

# Test 6: read_retry success after first failure
attempts = [0]
def flaky_read():
    attempts[0] += 1
    if attempts[0] < 2:
        return bytes([0x35, 0x00, 0x18, 0x04, 0x00])  # bad checksum
    return bytes([0x35, 0x00, 0x18, 0x04, 0x51])  # good

class FlakyTransport:
    def read(self):
        return flaky_read()

sensor5 = DHT11Full(FlakyTransport(), max_retries=3)
t5, h5 = sensor5.read_retry()
if abs(t5 - 24.4) < 0.001 and attempts[0] == 2:
    print('PASS read_retry_succeeds')
    passed += 1
else:
    print('FAIL read_retry_succeeds: attempts={} t={}'.format(attempts[0], t5))
    failed += 1

# Test 7: read_retry exhausted
class AlwaysBadTransport:
    def read(self):
        return bytes([0x35, 0x00, 0x18, 0x04, 0x00])

sensor6 = DHT11Full(AlwaysBadTransport(), max_retries=2)
err2 = None
try:
    sensor6.read_retry()
except Exception as e:
    err2 = e
if err2 is not None:
    print('PASS read_retry_exhausted')
    passed += 1
else:
    print('FAIL read_retry_exhausted: expected exception')
    failed += 1

# Test 8: read_raw
mock_raw = MockTransport(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))
sensor7 = DHT11Full(mock_raw)
raw = sensor7.read_raw()
if list(raw) == [0x35, 0x00, 0x18, 0x04, 0x51]:
    print('PASS read_raw')
    passed += 1
else:
    print('FAIL read_raw: {}'.format(list(raw)))
    failed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))
