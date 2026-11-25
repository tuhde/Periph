"""Unit test for the ADS1014 — runs without hardware using the I2C mock.

Verifies the single-shot Config word and conversion decoding, gain/data-rate
tables and validation, continuous mode, comparator Config bits, threshold
encoding, reset, and the conversion-ready / comparator interrupt routing.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ads1014 import (
    ADS1014Minimal, ADS1014Full, PGA_6_144, PGA_0_256, DR_128, DR_3300,
    COMP_WINDOW, ACTIVE_HIGH, QUEUE_4, SOURCE_CONVERSION_READY, SOURCE_COMPARATOR)

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
    """Word-addressed variant of the byte-slot mock.

    The ADS1014 registers are 16 bits wide at consecutive pointer values, so
    the shared mock's byte-slot model would overlap them. Each pointer owns
    one 16-bit word here.
    """

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


# --- Minimal: init and single-shot ---------------------------------------
mock = WordMock()
adc = ADS1014Minimal(mock)
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

# --- Full: gain and data rate --------------------------------------------
mock = WordMock()
adc = ADS1014Full(mock)
adc.set_gain(PGA_6_144)
check_true('gain_get', adc.get_gain() == 0)
check_true('full_scale_6_144', adc.full_scale() == 6.144)
check_true('lsb_6_144', abs(adc.lsb() - 0.003) < 1e-12)
adc.set_gain(PGA_0_256)
check_true('lsb_0_256', abs(adc.lsb() - 0.000125) < 1e-12)
mock.words[0] = 0x4000
check_true('read_voltage_gain', abs(adc.read_voltage() - 0.256 * 0x400 / 2048.0) < 1e-9)
check_true('config_gain_bits', config_writes(mock)[-1] == (0x8000 | (5 << 9) | 0x0100 | (4 << 5) | 0x3))
for bad in (-1, 6, 7):
    try:
        adc.set_gain(bad)
        check_true('gain_rejects_%d' % bad, False)
    except ValueError:
        check_true('gain_rejects_%d' % bad, True)

adc.set_data_rate(DR_128)
check_true('dr_sps_128', adc.data_rate_sps() == 128)
adc.set_data_rate(DR_3300)
check_true('dr_sps_3300', adc.data_rate_sps() == 3300 and adc.get_data_rate() == 6)
try:
    adc.set_data_rate(7)
    check_true('dr_rejects_7', False)
except ValueError:
    check_true('dr_rejects_7', True)

# --- Continuous mode -----------------------------------------------------
mock = WordMock()
adc = ADS1014Full(mock)
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

# --- Comparator ----------------------------------------------------------
mock = WordMock()
adc = ADS1014Full(mock)
adc.configure_comparator(mode=COMP_WINDOW, polarity=ACTIVE_HIGH, latch=True, queue=QUEUE_4)
check_true('comparator_config', config_writes(mock)[-1] == (0x0583 & ~0x3F | 0x10 | 0x08 | 0x04 | 0x02))
adc.disable_comparator()
check_true('comparator_disabled', config_writes(mock)[-1] & 0x3 == 3)
try:
    adc.configure_comparator(queue=3)
    check_true('queue_rejects_3', False)
except ValueError:
    check_true('queue_rejects_3', True)

adc.set_thresholds(-0.5, 1.0)
check_true('threshold_low_word', mock.words[2] == ((-500 & 0xFFF) << 4))
check_true('threshold_high_word', mock.words[3] == (1000 << 4))
check_true('threshold_readback', adc.get_thresholds_raw() == (-500, 1000))
adc.set_thresholds(-10.0, 10.0)
check_true('threshold_clamp', adc.get_thresholds_raw() == (-2048, 2047))
try:
    adc.set_thresholds(1.0, 1.0)
    check_true('threshold_rejects_equal', False)
except ValueError:
    check_true('threshold_rejects_equal', True)
try:
    adc.set_high_threshold_raw(2048)
    check_true('threshold_raw_rejects_range', False)
except ValueError:
    check_true('threshold_raw_rejects_range', True)

mock.writes = []
adc.clear_alert()
check_true('clear_alert_reads_conversion', mock.writes == [bytes([0])])

# --- Reset ----------------------------------------------------------------
adc.reset()
check_true('reset_config', mock.words[1] == 0x8583)
check_true('reset_thresholds', mock.words[2] == 0x8000 and mock.words[3] == 0x7FFF)

# --- Interrupt routing ---------------------------------------------------
class FakePin:
    def __init__(self):
        self.high = True
        self.handlers = []

    def is_high(self):
        return self.high

    def on_edge(self, handler, trigger=2):
        self.handlers.append((handler, trigger))

    def off_edge(self, handler):
        self.handlers = [h for h in self.handlers if h[0] != handler]


mock = WordMock()
pin = FakePin()
mock.int_pin = pin
adc = ADS1014Full(mock)
adc.enable_interrupt(SOURCE_CONVERSION_READY)
check_true('ready_thresholds', mock.words[3] == 0x8000 and mock.words[2] == 0x0000)
check_true('ready_que_00', mock.words[1] & 0x3 == 0)
check_true('poll_idle', adc.poll_interrupt() == 0)
pin.high = False
check_true('poll_ready', adc.poll_interrupt() == SOURCE_CONVERSION_READY)

adc.set_high_threshold_raw(-5)
adc.set_low_threshold_raw(10)
try:
    adc.enable_interrupt(SOURCE_COMPARATOR)
    check_true('comparator_needs_thresholds', False)
except ValueError:
    check_true('comparator_needs_thresholds', True)
adc.set_thresholds(-0.1, 0.1)
adc.configure_comparator(latch=True)
adc.enable_interrupt(SOURCE_COMPARATOR)
check_true('comparator_thresholds_restored', adc.get_thresholds_raw() == (-100, 100))
mock.writes = []
check_true('poll_comparator', adc.poll_interrupt() == SOURCE_COMPARATOR)
check_true('poll_latched_reads_conversion', bytes([0]) in mock.writes)

adc.disable_interrupt(SOURCE_COMPARATOR)
check_true('disable_que_11', mock.words[1] & 0x3 == 3)
check_true('poll_disabled', adc.poll_interrupt() == 0)

events = []
adc.enable_interrupt(SOURCE_COMPARATOR)
adc.on_interrupt(events.append)
check_true('on_interrupt_registers_edge', len(pin.handlers) == 1 and pin.handlers[0][1] == 2)
pin.handlers[0][0]()
check_true('on_interrupt_callback', events == [SOURCE_COMPARATOR])
adc.off_interrupt()
check_true('off_interrupt', len(pin.handlers) == 0)

# No pin: conversion-ready falls back to CONFIG.OS, comparator reads 0.
mock = WordMock()
adc = ADS1014Full(mock)
adc.enable_interrupt(SOURCE_CONVERSION_READY)
mock.words[1] |= 0x8000  # the real chip reads OS = 1 when idle
check_true('poll_fallback_ready', adc.poll_interrupt() == SOURCE_CONVERSION_READY)
mock.words[1] &= 0x7FFF
check_true('poll_fallback_busy', adc.poll_interrupt() == 0)

print('===DONE: %d passed, %d failed===' % (passed, failed))
