import time
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.comms.rda5807m import RDA5807MFull

from machine import I2C, Pin

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


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
fm = RDA5807MFull(transport, frequency_mhz=100.0, volume=8)

check_true('is_ready', fm.is_ready())
check_true('frequency near 100.0 MHz', abs(fm.frequency() - 100.0) < 0.2)

fm.set_frequency(97.5)
check_true('set_frequency: frequency near 97.5 MHz', abs(fm.frequency() - 97.5) < 0.2)

fm.set_volume(10)
check_true('signal_strength in range', 0 <= fm.signal_strength() <= 127)
check_true('is_stereo is bool', fm.is_stereo() in (True, False))

fm.mute(True)
fm.mute(False)
check_true('mute/unmute: is_ready after', fm.is_ready())

freq = fm.seek(up=True)
check_true('seek: result is float or None', freq is None or isinstance(freq, float))

fm.enable_rds(True)
check_true('rds_ready is bool', fm.rds_ready() in (True, False))

fm.configure(band=RDA5807MFull.BAND_WORLD, space=RDA5807MFull.SPACE_100K)
check_true('after configure: is_ready', fm.is_ready())

fm.standby(True)
time.sleep_ms(10)
fm.standby(False)
time.sleep_ms(10)
check_true('after standby cycle: is_ready', fm.is_ready())

fm.soft_reset()
check_true('after soft_reset: is_ready', fm.is_ready())

print('===DONE: {} passed, {} failed==='.format(passed, failed))
