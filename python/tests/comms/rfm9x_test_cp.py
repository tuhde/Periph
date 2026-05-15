import time
import board
import busio
import digitalio
import _testconfig as cfg
from periph.transport.spi_circuitpython import SPITransport
from periph.chips.comms.rfm9x import RFM95Full

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


spi_bus = busio.SPI(board.SCK, MOSI=board.MOSI, MISO=board.MISO)
cs = digitalio.DigitalInOut(board.SPI_CS)
cs.direction = digitalio.Direction.OUTPUT
transport = SPITransport(spi_bus, cs)

reset_pin = getattr(cfg, 'RESET_PIN', None)
dio0_pin = getattr(cfg, 'DIO0_PIN', None)
reset_pin_obj = digitalio.DigitalInOut(reset_pin) if reset_pin else None
dio0_pin_obj = digitalio.DigitalInOut(dio0_pin) if dio0_pin else None

rfm = RFM95Full(transport, 868_000_000, reset_pin_obj, dio0_pin_obj)

version = rfm.version()
check_eq('version_reg', version, 0x12)
check_true('version_nonzero', version != 0)
check_true('rssi_sane', rfm.rssi() > -150 and rfm.rssi() < 0)

rfm.send(b'test')
time.sleep(0.05)

rfm.standby()
rfm.sleep()
rfm.standby()

rfm.set_tx_power(14, use_pa_boost=False)
rfm.set_tx_power(17, use_pa_boost=True)
rfm.set_frequency(868_000_000)
rfm.configure(sf=7, bandwidth_khz=125, coding_rate=5, crc=True)
check_true('configure_valid', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))