"""Unit test for the ADS1115 — runs without hardware using a word-addressed I2C mock.

Verifies the whole-word Config encoding (mux, PGA, mode, data rate, comparator
bits), single-shot sequencing and sign extension, gain/LSB scaling, continuous
mode, threshold encoding, conversion-ready and comparator interrupt routing,
poll_interrupt, and reset.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ads1115 import ADS1115Minimal, ADS1115Full

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


# --- Minimal (ADS1115): config word and conversion -----------------------
mock = WordMock()
adc = ADS1115Minimal(mock)
check_true('init_no_traffic', mock.writes == [])
mock.words[0] = 0x4000
check_true('read_raw_value', adc.read_raw() == 0x4000)
check_true('read_raw_config_word', config_writes(mock) == [0xC583])
mock.words[0] = 0xFFFF
check_true('read_raw_negative', adc.read_raw(2) == -1)
check_true('read_raw_ch2_mux', config_writes(mock)[-1] == 0xE583)
adc.read_raw(9)
check_true('read_raw_clamps_channel', config_writes(mock)[-1] == 0xF583)
mock.words[0] = 0x4000
check_true('read_voltage', adc.read_voltage() == 0x4000 * 0.0000625)

# --- Full (ADS1115) ------------------------------------------------------
mock = WordMock()
full = ADS1115Full(mock)
full.set_gain(ADS1115Full.PGA_4_096)
check_true('gain_scale', full.full_scale() == 4.096 and full.lsb() == 4.096 / 32768)
mock.words[0] = 0x2000
check_true('read_voltage_uses_gain', full.read_voltage(1) == 0x2000 * 4.096 / 32768)
check_true('gain_in_config', config_writes(mock)[-1] == 0xD383)
try:
    full.set_gain(6)
    check_true('gain_rejects_6', False)
except ValueError:
    check_true('gain_rejects_6', True)
full.set_data_rate(ADS1115Full.DR_860)
check_true('data_rate_sps', full.data_rate_sps() == 860 and full.get_data_rate() == 7)
full.read_raw()
check_true('data_rate_in_config', config_writes(mock)[-1] == 0xC3E3)
check_true('differential_raw', full.read_differential_raw(ADS1115Full.DIFF_2_3) == 0x2000)
check_true('differential_mux', config_writes(mock)[-1] == 0xB3E3)
try:
    full.read_differential_raw(4)
    check_true('differential_rejects', False)
except ValueError:
    check_true('differential_rejects', True)

full.start_continuous(ADS1115Full.MUX_AIN1_GND)
check_true('continuous_config', config_writes(mock)[-1] == 0x52E3)
mock.words[0] = 0x0100
check_true('continuous_read', full.read_continuous_raw() == 256)
full.stop_continuous()
check_true('stop_continuous', config_writes(mock)[-1] == 0x53E3)
mock.words[1] = 0x0583
check_true('is_converting', full.is_converting())
mock.words[1] = 0x8583
check_true('not_converting', not full.is_converting())
check_true('read_config', full.read_config() == 0x8583)

# --- Full: comparator and thresholds -------------------------------------
mock = WordMock()
full = ADS1115Full(mock)
full.set_thresholds(0.5, 3.0 - 0.0)
check_true('thresholds_raw_low', mock.words[2] == 8000)       # 0.5 V / 62.5 uV
check_true('thresholds_raw_high_clamped', mock.words[3] == 0x7FFF)  # 3.0 V -> 48000, clamped
full.set_high_threshold_raw(-100)
check_true('negative_threshold_encoding', mock.words[3] == 0xFF9C)
full.set_low_threshold_raw(-200)
check_true('get_thresholds_raw', full.get_thresholds_raw() == (-200, -100))
try:
    full.set_thresholds(1.0, 1.0)
    check_true('thresholds_reject_inverted', False)
except ValueError:
    check_true('thresholds_reject_inverted', True)
full.set_thresholds(0.5, 1.5)
full.configure_comparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_HIGH, True, ADS1115Full.QUEUE_2)
# mux 0, PGA 2, single-shot, 128 SPS, window, active high, latching, queue 2
check_true('comparator_bits', config_writes(mock)[-1] == 0x0400 | 0x0100 | 0x0080 | 0x0010 | 0x0008 | 0x0004 | 0x0001)
full.disable_comparator()
check_true('disable_comparator', config_writes(mock)[-1] & 0x3 == 3)
mock.words[0] = 0
full.clear_alert()
check_true('clear_alert_reads_conversion', mock.writes[-1] == bytes([0]))

# --- Full: interrupt routing ---------------------------------------------
mock = WordMock()
full = ADS1115Full(mock)
full.enable_interrupt(ADS1115Full.SOURCE_CONVERSION_READY)
check_true('ready_thresholds', mock.words[3] == 0x8000 and mock.words[2] == 0x0000)
check_true('ready_queue', config_writes(mock)[-1] & 0x3 == 0)
mock.words[1] = 0x8583
check_true('poll_ready_fallback', full.poll_interrupt() == ADS1115Full.SOURCE_CONVERSION_READY)
mock.words[1] = 0x0583
check_true('poll_ready_idle', full.poll_interrupt() == 0)
mock.int_pin = Pin(False)
check_true('poll_pin_asserted_low', full.poll_interrupt() == ADS1115Full.SOURCE_CONVERSION_READY)
mock.int_pin = Pin(True)
check_true('poll_pin_deasserted', full.poll_interrupt() == 0)
full.disable_interrupt(ADS1115Full.SOURCE_CONVERSION_READY)
check_true('disable_interrupt', config_writes(mock)[-1] & 0x3 == 3)
check_true('poll_disabled', full.poll_interrupt() == 0)
full.set_thresholds(0.5, 1.5)
full.enable_interrupt(ADS1115Full.SOURCE_COMPARATOR)
check_true('enable_comparator', config_writes(mock)[-1] & 0x3 == 0)
mock.int_pin = Pin(False)
check_true('poll_comparator_asserted', full.poll_interrupt() == ADS1115Full.SOURCE_COMPARATOR)

# --- Reset ---------------------------------------------------------------
full.set_gain(0)
mock.writes = []
full.reset()
check_true('reset_writes', mock.words[1] == 0x8583 and mock.words[2] == 0x8000 and mock.words[3] == 0x7FFF)
check_true('reset_clears_cache', full.get_gain() == 2 and full.get_data_rate() == 4)


print('===DONE: %d passed, %d failed===' % (passed, failed))
