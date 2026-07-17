import os
import sys
import time

from periph.transport.i2c_linux import I2CTransport
from periph.chips.comms.rda5807m import RDA5807MFull

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x10'), 16)

# FM_READY deasserts on any register write and takes ~20 ms to settle back;
# not documented in the datasheet, measured on real hardware.
_SETTLE_S = 0.03

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


transport = I2CTransport(I2C_BUS, I2C_ADDR)
fm = RDA5807MFull(transport, frequency_mhz=100.0, volume=8)

time.sleep(_SETTLE_S)
check_true('is_ready', fm.is_ready())
check_true('frequency near 100.0 MHz', abs(fm.frequency() - 100.0) < 0.2)

fm.set_frequency(97.5)
check_true('set_frequency: frequency near 97.5 MHz', abs(fm.frequency() - 97.5) < 0.2)

fm.set_volume(10)
check_true('signal_strength in range', 0 <= fm.signal_strength() <= 127)
check_true('is_stereo is bool', fm.is_stereo() in (True, False))

fm.mute(True)
fm.mute(False)
time.sleep(_SETTLE_S)
check_true('mute/unmute: is_ready after', fm.is_ready())

freq = fm.seek(up=True)
check_true('seek: result is float or None', freq is None or isinstance(freq, float))

fm.enable_rds(True)
check_true('rds_ready is bool', fm.rds_ready() in (True, False))

fm.configure(band=RDA5807MFull.BAND_WORLD, space=RDA5807MFull.SPACE_100K)
time.sleep(_SETTLE_S)
check_true('after configure: is_ready', fm.is_ready())

fm.standby(True)
time.sleep(0.01)
fm.standby(False)
check_true('after standby cycle: is_ready', fm.is_ready())

fm.soft_reset()
check_true('after soft_reset: is_ready', fm.is_ready())

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
