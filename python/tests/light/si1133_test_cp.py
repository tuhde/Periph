import busio
import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.light.si1133 import Si1133Full

passed = 0
failed = 0

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1

sensor = Si1133Full(connection)
sensor.init()
part_id, hw_id, rev_id = sensor.read_id()
check_true('part_id', part_id == 0x33)
check_true('hw_id_impl', hw_id & 0x1F == 0x03)
state, err, counter = sensor.read_response()
check_true('response_no_error', not err)
m = sensor.read()
check_true('read_uv_range', -0x800000 <= m['uv'] <= 0x7FFFFF)
check_true('read_white_range', -0x800000 <= m['white'] <= 0x7FFFFF)
check_true('read_white_positive_in_light', m['white'] > 0)
check_true('read_uv_scratch', -0x800000 <= sensor.read_uv() <= 0x7FFFFF)
check_true('read_white_scratch', sensor.read_white() > 0)
check_true('read_ir_small', -0x800000 <= sensor.read_ir(Si1133Full.MUX_SMALL_IR) <= 0x7FFFFF)
check_true('read_ir_large', -0x800000 <= sensor.read_ir(Si1133Full.MUX_LARGE_IR) <= 0x7FFFFF)
sensor.configure_channel(2, Si1133Full.MUX_LARGE_IR, hw_gain=5, bits24=False)
sensor.enable_channels(0x07)
values = sensor.read_channels()
check_true('read_channels_count', len(values) == 3)
check_true('read_channels_16bit', 0 <= values[2] <= 0xFFFF)
sensor.write_parameter(0x1C, 0x05)
check_true('parameter_roundtrip', sensor.read_parameter(0x1C) == 0x05)
check_true('chan_list_readback', sensor.read_parameter(0x01) == 0x07)
sensor.set_threshold(0, 1000)
sensor.set_channel_threshold(0, 1)
check_true('threshold_readback', sensor.read_parameter(0x25) == 0x03 and sensor.read_parameter(0x26) == 0xE8)
sensor.set_channel_threshold(0, 0)
sensor.enable_channels(0x03)
sensor.data_ready()
sensor.start_autonomous(0.1, (1, 1, 1))
time.sleep(0.5)
check_true('autonomous_irq', sensor.poll_interrupt() & 0x03 != 0)
check_true('autonomous_values', len(sensor.read_channels(False)) == 2)
sensor.pause()
sensor.reset()
state, err, counter = sensor.read_response()
check_true('reset_sleep', state & Si1133Full.RESP_SLEEP)
check_true('read_after_reset', sensor.read()['white'] > 0)
print('===DONE: %d passed, %d failed===' % (passed, failed))
