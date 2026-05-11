import _testconfig as cfg
from periph.transport.spi_micropython import SPITransport

from machine import SPI, Pin

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


spi = SPI(cfg.SPI_ID)
cs = Pin(cfg.SPI_CS, Pin.OUT)
cs.value(1)
spi.init(baudrate=cfg.SPI_BAUD, polarity=0, phase=0)

transport = SPITransport(spi, cs)

data = b'\x01\x02\x03'
transport.write(data)
check_true('write completed', True)

result = transport.read(3)
check_true('read returned 3 bytes', len(result) == 3)

result = transport.write_read(b'\x00', 2)
check_true('write_read returned 2 bytes', len(result) == 2)

print('===DONE: {} passed, {} failed==='.format(passed, failed))