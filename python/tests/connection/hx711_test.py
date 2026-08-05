import _testconfig as cfg
from machine import Pin
from periph.connection.hx711_micropython import HX711Connection

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


dout   = Pin(cfg.DOUT,   Pin.IN)
pd_sck = Pin(cfg.PD_SCK, Pin.OUT)
connection = HX711Connection(dout, pd_sck)

check_true('is_ready returns bool', isinstance(connection.is_ready(), bool))

val = connection.read_raw(25)
check_true('read_raw(25) returns int', isinstance(val, int))
check_true('read_raw(25) in 24-bit signed range', -8388608 <= val <= 8388607)

val = connection.read_raw(26)
check_true('read_raw(26) returns int', isinstance(val, int))
check_true('read_raw(26) in 24-bit signed range', -8388608 <= val <= 8388607)

val = connection.read_raw(27)
check_true('read_raw(27) returns int', isinstance(val, int))
check_true('read_raw(27) in 24-bit signed range', -8388608 <= val <= 8388607)

try:
    connection.read_raw(24)
    check_true('read_raw(24) raises ValueError', False)
except ValueError:
    check_true('read_raw(24) raises ValueError', True)

connection.close()
check_true('close accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
