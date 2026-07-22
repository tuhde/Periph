import board
import busio
import bitbangio
import digitalio
import _testconfig as cfg
from periph.transport.sipo_circuitpython import SiPoTransport

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


rck = digitalio.DigitalInOut(cfg.RCK)
rck.direction = digitalio.Direction.OUTPUT

srclr = digitalio.DigitalInOut(cfg.SRCLR)
srclr.direction = digitalio.Direction.OUTPUT

g = digitalio.DigitalInOut(cfg.G)
g.direction = digitalio.Direction.OUTPUT

if getattr(cfg, 'SIPO_MODE', 'sw') == 'hw':
    spi = busio.SPI(clock=cfg.SRCK, MOSI=cfg.SER_IN)
else:
    # MISO is unused by this write-only transport but bitbangio.SPI requires
    # one; cfg.MISO is any free pin not otherwise wired.
    spi = bitbangio.SPI(clock=cfg.SRCK, MOSI=cfg.SER_IN, MISO=cfg.MISO)

transport = SiPoTransport(spi, rck, srclr=srclr, g=g)

transport.write(bytes([0xA5]))
check_true('write accepted', True)

transport.write(bytes([0x00, 0xFF]))
check_true('write multi-byte accepted', True)

transport.clear()
check_true('clear accepted', True)

transport.set_output_enable(False)
check_true('set_output_enable(False) accepted', True)

transport.set_output_enable(True)
check_true('set_output_enable(True) accepted', True)

transport.close()
check_true('close accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
