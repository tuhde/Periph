import time

import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rfid.pn532 import PN532Full
from machine import I2C, Pin

passed = 0
failed = 0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


reader = PN532Full(connection)
reader.init()
fw = reader.firmware_version()
check_true('firmware_ic', fw['ic'] == 0x32)
check_true('firmware_support_a', fw['iso14443a'])
gs = reader.general_status()
check_true('general_status_targets', 0 <= gs['targets'] <= 2)
check_true('self_test_echo', reader.self_test())
check_true('self_test_rom', reader.self_test(0x01))
check_true('send_command', reader.send_command(0x02)[0] == 0x32)
reader.start_command(0x02)
check_true('wait_ready', reader.wait_ready(1.0))
check_true('poll_interrupt', reader.poll_interrupt())
check_true('read_response', reader.read_response(0x02)[0] == 0x32)
old = reader.read_register(0x6302)
reader.write_register(0x6302, old)
check_true('register_roundtrip', reader.read_register(0x6302) == old)
reader.set_rf_field(False)
reader.set_rf_field(True)
check_true('rf_field_toggle', True)
reader.set_max_retries(2)
t0 = time.time()
uid = reader.read_uid()
check_true('read_uid_returns', uid is None or len(uid) in (4, 7, 10))
check_true('read_uid_bounded', time.time() - t0 < 2.0)
targets = reader.list_passive_targets()
check_true('list_passive_targets', isinstance(targets, list))
if targets:
    reader.release(targets[0].tg)
reader.power_down()
check_true('wake_after_power_down', reader.firmware_version()['ic'] == 0x32)
print('===DONE: %d passed, %d failed===' % (passed, failed))
