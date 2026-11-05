"""Unit test for the ADS1113 — runs without hardware using a word-addressed I2C mock.

Verifies the fixed-range conversion, continuous config word, reset, and that
gain, comparator and interrupt APIs are absent.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ads1113 import ADS1113Minimal, ADS1113Full

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
    """Each pointer owns one 16-bit word (CONVERSION, CONFIG, LO, HI)."""

    def __init__(self):
        super().__init__()
        self.words = {0: 0x0000, 1: 0x8583, 2: 0x8000, 3: 0x7FFF}

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


class Pin:
    def __init__(self, high):
        self.high = high

    def is_high(self):
        return self.high


# --- ADS1113 -------------------------------------------------------------
mock = WordMock()
a13 = ADS1113Minimal(mock)
mock.words[0] = 0x0200
check_true('ads1113_read_voltage', a13.read_voltage() == 0x0200 * 0.0000625)
a13f = ADS1113Full(mock)
check_true('ads1113_fixed_range', a13f.full_scale() == 2.048 and a13f.lsb() == 0.0000625)
check_true('ads1113_no_gain_or_comparator',
           not hasattr(a13f, 'set_gain') and not hasattr(a13f, 'configure_comparator')
           and not hasattr(a13f, 'poll_interrupt'))
a13f.set_data_rate(ADS1113Full.DR_8)
a13f.start_continuous()
check_true('ads1113_continuous_config', config_writes(mock)[-1] == 0x0403)
a13f.reset()
check_true('ads1113_reset', mock.words[1] == 0x8583 and a13f.data_rate_sps() == 128)

print('===DONE: %d passed, %d failed===' % (passed, failed))
