"""Unit test for the ADS1013 — runs without hardware using the I2C mock.

Verifies the single-shot Config word and conversion decoding, data-rate
table and validation, continuous mode and reset.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ads1013 import ADS1013Minimal, ADS1013Full, DR_128, DR_3300

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


class WordMock(I2CConnectionMock):
    """Word-addressed variant of the byte-slot mock (16-bit registers at
    consecutive pointers would overlap in the byte-slot model)."""

    def __init__(self):
        super().__init__()
        self.words = {0: 0x0000, 1: 0x8583}

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if len(data) == 3:
            self.words[data[0]] = (data[1] << 8) | data[2]

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        word = self.words.get(data[0], 0)
        return bytes([(word >> 8) & 0xFF, word & 0xFF])


def config_writes(mock):
    return [(w[1] << 8) | w[2] for w in mock.writes if len(w) == 3 and w[0] == 1]


mock = WordMock()
adc = ADS1013Minimal(mock)
check_true('init_no_traffic', len(mock.writes) == 0)
mock.words[0] = 0x7FF0
check_true('read_raw_max', adc.read_raw() == 2047)
check_true('config_word_default', config_writes(mock)[-1] == 0x8583)
mock.words[0] = 0x8000
check_true('read_raw_min', adc.read_raw() == -2048)
mock.words[0] = 0xFFF0
check_true('read_raw_minus_one', adc.read_raw() == -1)
mock.words[0] = 0x3E80
check_true('read_voltage_1v', abs(adc.read_voltage() - 1.0) < 1e-9)

mock = WordMock()
adc = ADS1013Full(mock)
check_true('full_scale', adc.full_scale() == 2.048)
check_true('lsb', adc.lsb() == 0.001)
adc.set_data_rate(DR_128)
check_true('dr_sps_128', adc.data_rate_sps() == 128 and adc.get_data_rate() == 0)
adc.set_data_rate(DR_3300)
check_true('dr_sps_3300', adc.data_rate_sps() == 3300 and adc.get_data_rate() == 6)
mock.words[0] = 0x1000
adc.read_raw()
check_true('config_word_dr', config_writes(mock)[-1] == (0x8000 | 0x0403 | 0x0100 | (6 << 5)))
for bad in (-1, 7):
    try:
        adc.set_data_rate(bad)
        check_true('dr_rejects_%d' % bad, False)
    except ValueError:
        check_true('dr_rejects_%d' % bad, True)

mock = WordMock()
adc = ADS1013Full(mock)
adc.start_continuous()
check_true('continuous_config', config_writes(mock)[-1] == 0x0483)
mock.words[0] = 0x1000
mock.writes = []
check_true('continuous_raw', adc.read_continuous_raw() == 256)
check_true('continuous_no_config_write', len(config_writes(mock)) == 0)
check_true('continuous_volts', abs(adc.read_continuous() - 0.256) < 1e-9)
adc.stop_continuous()
check_true('stop_continuous_config', config_writes(mock)[-1] == 0x0583)
mock.words[1] = 0x0583
check_true('is_converting_true', adc.is_converting())
mock.words[1] = 0x8583
check_true('is_converting_false', not adc.is_converting())
check_true('read_config', adc.read_config() == 0x8583)
adc.set_data_rate(DR_128)
adc.reset()
check_true('reset_config', mock.words[1] == 0x8583 and adc.get_data_rate() == 4)

print('===DONE: %d passed, %d failed===' % (passed, failed))
