import busio
import digitalio
import _testconfig as cfg
from periph.transport.spi_circuitpython import SPITransport

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


spi = busio.SPI(cfg.SCK, MOSI=cfg.MOSI, MISO=cfg.MISO)
cs  = digitalio.DigitalInOut(cfg.CS)
cs.direction = digitalio.Direction.OUTPUT
transport = SPITransport(spi, cs, baudrate=cfg.FREQ)

transport.write(bytes([0x00]))
check_true('write accepted', True)

data = transport.read(1)
check_true('read returns 1 byte', len(data) == 1)

data = transport.write_read(bytes([0x00]), 1)
check_true('write_read returns 1 byte', len(data) == 1)

spi.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
