import sys

from periph.connection.dhtxx_mock import DHTxxConnectionMock
from periph.chips.humidity.dht11 import DHT11Minimal, DHT11Full, DHT11Error

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


def close_enough(a, b, eps=0.001):
    return abs(a - b) < eps


# --- DHT11Minimal: decoding ---

connection = DHTxxConnectionMock()
connection.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))
sensor = DHT11Minimal(connection)
t, h = sensor.read()
check_true('decode_datasheet_example', close_enough(t, 24.4) and close_enough(h, 53.0))

connection.queue_read(bytes([0x20, 0x00, 0x0A, 0x81, 0xAB]))
t, h = sensor.read()
check_true('decode_negative_temperature', close_enough(t, -10.1) and close_enough(h, 32.0))

# --- Checksum validation ---

connection.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))  # bad checksum
try:
    sensor.read()
    check_true('checksum_error_raises', False)
except DHT11Error:
    check_true('checksum_error_raises', True)

# --- Frame length validation ---

connection.queue_read(bytes([0x35, 0x00, 0x18]))  # too short
try:
    sensor.read()
    check_true('short_frame_raises', False)
except DHT11Error:
    check_true('short_frame_raises', True)

# --- DHT11Full: convenience accessors ---

connection2 = DHTxxConnectionMock()
connection2.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))
full = DHT11Full(connection2, max_retries=3)
check_true('read_temperature', close_enough(full.read_temperature(), 24.4))

connection2.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))
check_true('read_humidity', close_enough(full.read_humidity(), 53.0))

# --- read_raw(): unprocessed frame, still checksum-validated ---

connection2.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))
raw = full.read_raw()
check_true('read_raw_returns_frame', list(raw) == [0x35, 0x00, 0x18, 0x04, 0x51])

connection2.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))  # bad checksum
try:
    full.read_raw()
    check_true('read_raw_checksum_error_raises', False)
except DHT11Error:
    check_true('read_raw_checksum_error_raises', True)

# --- read_retry(): retries only on checksum error (DHT11Error), not on a
# transport-level exception - see specs/humidity/dht11.md's read_retry note
# ("Retry up to max_retries times on checksum error").

connection3 = DHTxxConnectionMock()
connection3.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))  # bad checksum, attempt 1
connection3.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))  # good, attempt 2
full3 = DHT11Full(connection3, max_retries=3)
t, h = full3.read_retry()
check_true('read_retry_succeeds', close_enough(t, 24.4) and close_enough(h, 53.0))

connection4 = DHTxxConnectionMock()
connection4.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))
connection4.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))
full4 = DHT11Full(connection4, max_retries=2)
try:
    full4.read_retry()
    check_true('read_retry_exhausted', False)
except DHT11Error:
    check_true('read_retry_exhausted', True)

connection5 = DHTxxConnectionMock()
connection5.queue_exception(RuntimeError('sensor timeout'))
connection5.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))  # would succeed if retried
full5 = DHT11Full(connection5, max_retries=3)
try:
    full5.read_retry()
    check_true('read_retry_does_not_catch_transport_error', False)
except RuntimeError:
    check_true('read_retry_does_not_catch_transport_error', True)
except DHT11Error:
    check_true('read_retry_does_not_catch_transport_error', False)

# --- read_retry() default max_retries falls back to the constructor value ---

connection6 = DHTxxConnectionMock()
connection6.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))
connection6.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x00]))
connection6.queue_read(bytes([0x35, 0x00, 0x18, 0x04, 0x51]))  # 3rd attempt succeeds
full6 = DHT11Full(connection6, max_retries=3)
t, h = full6.read_retry()  # no explicit max_retries -> uses constructor's 3
check_true('read_retry_default_uses_constructor_value', close_enough(t, 24.4))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
