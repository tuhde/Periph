import time
import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.gas.ccs811 import CCS811Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x5A'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1

sensor = CCS811Full(connection)
sensor.init()
hw_id, hw_version = sensor.hardware_version()
check_true('hardware_id', hw_id == 0x81)
check_true('hardware_version', hw_version & 0xF0 == 0x10)
fw = sensor.firmware_version()
check_true('firmware_version', fw['app'][0] >= 1)
check_true('status_app_mode', sensor.status() & CCS811Full.STATUS_FW_MODE)
check_true('status_no_error', not sensor.status() & CCS811Full.STATUS_ERROR)
m = sensor.read()
check_true('read_eco2_range', 400 <= m['eco2_ppm'] <= 8192)
check_true('read_tvoc_range', 0 <= m['tvoc_ppb'] <= 1187)
check_true('read_eco2_single', 400 <= sensor.read_eco2() <= 8192)
check_true('read_tvoc_single', 0 <= sensor.read_tvoc() <= 1187)
current, voltage = sensor.read_raw()
check_true('read_raw_current', 0 <= current <= 63)
check_true('read_raw_voltage', 0 <= voltage <= 1.65)
check_true('data_ready_cleared', not sensor.data_ready())
sensor.set_environment(50, 25)
sensor.set_thresholds(1000, 2000, 50)
v_ref, v_ntc = sensor.read_ntc()
check_true('read_ntc', 0 <= v_ref <= 0xFFFF and 0 <= v_ntc <= 0xFFFF)
baseline = sensor.get_baseline()
sensor.set_baseline(baseline)
check_true('baseline_roundtrip', sensor.error() == 0)
sensor.enable_interrupt(CCS811Full.SOURCE_DATA_READY)
time.sleep(1.2)
check_true('poll_interrupt_data_ready', sensor.poll_interrupt() & CCS811Full.STATUS_DATA_READY)
sensor.read()
sensor.disable_interrupt(CCS811Full.SOURCE_DATA_READY)
sensor.set_mode(4)
time.sleep(0.6)
check_true('mode_4_raw', 0 <= sensor.read_raw()[0] <= 63)
sensor.set_mode(0)
sensor.set_mode(1)
sensor.soft_reset()
check_true('status_after_reset', sensor.status() & CCS811Full.STATUS_FW_MODE)
check_true('read_after_reset', 400 <= sensor.read()['eco2_ppm'] <= 8192)
print('===DONE: %d passed, %d failed===' % (passed, failed))
connection.close()
