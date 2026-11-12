import os

from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.cs43l22 import CS43L22Full

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x4A'), 16)

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


connection = I2CConnection(I2C_BUS, I2C_ADDR)

dac = CS43L22Full(connection)
check_true('init accepted', True)
check_true('read_id', dac.read_id()[0] == 0x1C)

dac.set_volume(-20.0)
check_true('volume round trip', abs(dac.get_volume() - (-20.0)) < 0.01)
dac.set_volume_channels(-10.0, -12.0)
check_true('volume channels', dac.read_register(0x20) == 0xEC and dac.read_register(0x21) == 0xE8)
dac.set_headphone_gain(CS43L22Full.GAIN_1_000)
check_true('headphone gain', dac.headphone_gain() == 1.0)
dac.mute(True)
check_true('mute', dac.read_register(0x0F) & 0xC0 == 0xC0)
dac.mute(False)
check_true('unmute', dac.read_register(0x0F) & 0xC0 == 0)
dac.set_tone(6, 6)
check_true('tone', dac.read_register(0x1F) == 0x44)
dac.set_limiter(True, -6, -12)
check_true('limiter', dac.read_register(0x28) & 0x80 == 0x80)
dac.set_limiter(False)
dac.set_battery_compensation(False)
check_true('speaker status readable', 'spkr_hp_pin' in dac.read_speaker_status())
dac.stop()
check_true('stop powers down', dac.read_register(0x02) == 0x9F)

print('Passed: %d  Failed: %d' % (passed, failed))
