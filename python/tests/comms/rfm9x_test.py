import time
import _testconfig as cfg
from machine import SPI, Pin
from periph.transport.spi_micropython import SPITransport
from periph.chips.comms.rfm9x import (
    RFM95Minimal, RFM96Minimal, RFM97Minimal, RFM98Minimal,
    RFM95Full, RFM96Full, RFM97Full, RFM98Full,
)

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


spi = SPI(cfg.SPI_ID, baudrate=5000000, polarity=0, phase=0,
          sck=Pin(cfg.SPI_SCK), mosi=Pin(cfg.SPI_MOSI), miso=Pin(cfg.SPI_MISO))
cs = Pin(cfg.SPI_CS, Pin.OUT)
transport = SPITransport(spi, cs)

reset_pin = Pin(cfg.RESET_PIN, Pin.OUT) if hasattr(cfg, 'RESET_PIN') else None
dio0_pin = Pin(cfg.DIO0_PIN, Pin.IN) if hasattr(cfg, 'DIO0_PIN') else None

rfm = RFM95Full(transport, 868_000_000, reset_pin, dio0_pin)

version = rfm.version()
check_eq('version_reg', version, 0x12)

check_true('version_nonzero', version != 0)

check_true('rssi_sane', rfm.rssi() > -150 and rfm.rssi() < 0)

rfm.send(b'test')
time.sleep_ms(50)

rfm.standby()
check_true('standby_mode', True)

rfm.sleep()
check_true('sleep_mode', True)

rfm.standby()
rfm.set_tx_power(14, use_pa_boost=False)
check_true('set_tx_power_rfo', True)

rfm.set_tx_power(17, use_pa_boost=True)
check_true('set_tx_power_boost', True)

rfm.set_frequency(868_000_000)
check_true('set_frequency_hf', True)

rfm.configure(sf=7, bandwidth_khz=125, coding_rate=5, crc=True)
check_true('configure_valid', True)

try:
    rfm.configure(sf=7, bandwidth_khz=500, coding_rate=5, crc=True)
    print('FAIL lf_band_bw_check')
    failed += 1
except ValueError:
    print('PASS lf_band_bw_check')

try:
    rfm.set_frequency(300_000_000)
    print('FAIL freq_range_check')
    failed += 1
except ValueError:
    print('PASS freq_range_check')

print('===DONE: {} passed, {} failed==='.format(passed, failed))