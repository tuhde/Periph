import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.es8311 import ES8311Full

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
connection = I2CConnection(i2c, cfg.ADDR)

dac = ES8311Full(connection)
check_true('init accepted', True)

dac.set_volume(-20.0)
check_true('set_volume/get_volume round trip', abs(dac.get_volume() - (-20.0)) < 0.01)

dac.set_mic_gain(24)
check_true('set_mic_gain/get_mic_gain round trip', abs(dac.get_mic_gain() - 24.0) < 0.01)

dac.set_mute(True)
check_true('DAC mute bits set', (dac.read_register(0x31) & 0x60) == 0x60)
dac.set_mute(False)
check_true('DAC mute bits cleared', (dac.read_register(0x31) & 0x60) == 0x00)

dac.configure_clock(12288000, 16000)
check_true('configure_clock accepted', True)

dac.configure_serial(format='i2s', word_length=16)
check_true('configure_serial reads back format/word length', (dac.read_register(0x09) & 0x1F) == 0x0C)

dac.set_adc_volume(0)
check_true('ADC volume 0 dB (0xBF)', dac.read_register(0x17) == 0xBF)

dac.configure_alc(True, max_level_db=-12.0, min_level_db=-18.1, automute=True, noise_gate_db=-60)
check_true('ALC enabled bit', (dac.read_register(0x18) & 0x80) == 0x80)
dac.configure_alc(False)

dac.configure_drc(True, max_level_db=-12.0, min_level_db=-24.1)
check_true('DRC enabled bit', (dac.read_register(0x34) & 0x80) == 0x80)
dac.configure_drc(False)

dac.set_loopback(True)
check_true('loopback bit set', (dac.read_register(0x44) & 0x80) == 0x80)
dac.set_loopback(False)

dac.set_adcdat_mode('dac_adc')
check_true('ADCDAT mode dac_adc (4)', ((dac.read_register(0x44) >> 4) & 7) == 4)
dac.set_adcdat_mode('adc_adc')

dac.set_dac_ramp(8)
check_true('DAC ramp rate 8', (dac.read_register(0x37) >> 4) == 8)

dac.power_down()
check_true('power_down clears CSM_ON', (dac.read_register(0x00) & 0x80) == 0x00)
dac.reset()
check_true('reset accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
