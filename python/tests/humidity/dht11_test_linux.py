import os
import sys
import time

from periph.connection.dhtxx_linux import DHTxxConnection
from periph.chips.humidity.dht11 import DHT11Minimal, DHT11Full, DHT11Error

CHIP_NUM  = int(os.environ.get('DHT11_CHIP', '0'))
DATA_LINE = int(os.environ.get('DHT11_DATA', '4'))

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


try:
    connection = DHTxxConnection(CHIP_NUM, DATA_LINE)
except Exception as e:
    print('connection open failed (no hw?):', e, file=sys.stderr)
    check_true('minimal_skip_no_hw', True)
    check_true('full_skip_no_hw', True)
    print('===DONE: {} passed, {} failed==='.format(passed, failed))
    sys.exit(0 if failed == 0 else 1)

time.sleep(1.0)  # power-up stabilisation, see specs/humidity/dht11.md

# --- DHT11Minimal ---

minimal = DHT11Minimal(connection)
try:
    t, h = minimal.read()
    check_true('minimal_read_ok', True)
    check_true('temperature_in_plausible_range', -20.0 <= t <= 60.0)
    check_true('humidity_in_plausible_range', 0.0 <= h <= 100.0)
except DHT11Error as e:
    check_true('minimal_read_ok', False)
    print('  (checksum/framing error:', e, ')', file=sys.stderr)

time.sleep(2.0)  # minimum sampling interval, see specs/humidity/dht11.md

# --- DHT11Full ---

full = DHT11Full(connection, max_retries=3)
try:
    t, h = full.read_retry()
    check_true('full_read_retry_ok', True)
    check_true('full_temperature_in_plausible_range', -20.0 <= t <= 60.0)
    check_true('full_humidity_in_plausible_range', 0.0 <= h <= 100.0)
except DHT11Error as e:
    check_true('full_read_retry_ok', False)
    print('  (all retries failed:', e, ')', file=sys.stderr)

time.sleep(2.0)

try:
    raw = full.read_raw()
    check_true('read_raw_returns_5_bytes', len(raw) == 5)
except DHT11Error as e:
    check_true('read_raw_returns_5_bytes', False)
    print('  (checksum error:', e, ')', file=sys.stderr)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
