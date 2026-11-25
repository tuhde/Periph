"""Unit test for the ADS1114 — runs without hardware using a word-addressed I2C mock.

Verifies the single-input Config encoding, gain/LSB scaling, continuous mode,
and that the mux API is absent.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ads1114 import ADS1114Minimal, ADS1114Full

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


# --- ADS1114 -------------------------------------------------------------
mock = WordMock()
a14 = ADS1114Minimal(mock)
mock.words[0] = 0x1000
check_true('ads1114_read_raw', a14.read_raw() == 0x1000 and config_writes(mock) == [0x8583])
a14f = ADS1114Full(mock)
a14f.set_gain(ADS1114Full.PGA_0_256)
check_true('ads1114_lsb', a14f.lsb() == 0.256 / 32768)
check_true('ads1114_no_mux', not hasattr(a14f, 'read_differential_raw'))
a14f.start_continuous()
check_true('ads1114_continuous', config_writes(mock)[-1] == 0x0A83)
check_true('ads1114_has_comparator', hasattr(a14f, 'configure_comparator'))


print('===DONE: %d passed, %d failed===' % (passed, failed))
