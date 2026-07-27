import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection

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


spi = SPI(cfg.SPI_ID, baudrate=cfg.FREQ, sck=Pin(cfg.SCK), mosi=Pin(cfg.MOSI), miso=Pin(cfg.MISO))
cs  = Pin(cfg.CS, Pin.OUT, value=1)
connection = SPIConnection(spi, cs)

connection.write(bytes([0x00]))
check_true('write accepted', True)

data = connection.read(1)
check_true('read returns 1 byte', len(data) == 1)

data = connection.write_read(bytes([0x00]), 1)
check_true('write_read returns 1 byte', len(data) == 1)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
