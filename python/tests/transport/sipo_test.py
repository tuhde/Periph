import _testconfig as cfg
from machine import Pin, SPI, SoftSPI
from periph.transport.sipo_micropython import SiPoTransport

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


rck   = Pin(cfg.RCK,   Pin.OUT)
srclr = Pin(cfg.SRCLR, Pin.OUT)
g     = Pin(cfg.G,     Pin.OUT)

if getattr(cfg, 'SIPO_MODE', 'sw') == 'hw':
    spi = SPI(cfg.SPI_ID, baudrate=1_000_000, polarity=0, phase=0,
              sck=Pin(cfg.SRCK), mosi=Pin(cfg.SER_IN))
else:
    # MISO is unused by this write-only transport but SoftSPI requires one;
    # cfg.MISO is any free GPIO not otherwise wired.
    spi = SoftSPI(baudrate=1_000_000, polarity=0, phase=0,
                   sck=Pin(cfg.SRCK, Pin.OUT), mosi=Pin(cfg.SER_IN, Pin.OUT),
                   miso=Pin(cfg.MISO, Pin.IN))

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
