import os
import sys

import gpiod
from periph.transport.sipo_linux import SiPoTransport

CHIP = os.environ.get('GPIO_CHIP', '/dev/gpiochip0')
MODE = os.environ.get('SIPO_MODE', 'sw')  # 'sw' (bit-bang) or 'hw' (spidev)

RCK    = int(os.environ.get('SIPO_RCK',    '5'))
SRCLR  = int(os.environ.get('SIPO_SRCLR',  '6'))
G      = int(os.environ.get('SIPO_G',      '13'))
SER_IN = int(os.environ.get('SIPO_SER_IN', '19'))
SRCK   = int(os.environ.get('SIPO_SRCK',   '26'))

SPI_BUS    = int(os.environ.get('SIPO_SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('SIPO_SPI_DEVICE', '0'))

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


offsets = [RCK, SRCLR, G]
if MODE == 'sw':
    offsets += [SER_IN, SRCK]

chip = gpiod.Chip(CHIP)
settings = gpiod.LineSettings(direction=gpiod.line.Direction.OUTPUT)
request = chip.request_lines(consumer='sipo_test', config={o: settings for o in offsets})

if MODE == 'hw':
    transport = SiPoTransport(request, RCK, bus_num=SPI_BUS, device_num=SPI_DEVICE,
                               srclr_offset=SRCLR, g_offset=G)
else:
    transport = SiPoTransport(request, RCK, ser_in_offset=SER_IN, srck_offset=SRCK,
                               srclr_offset=SRCLR, g_offset=G)

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
sys.exit(0 if failed == 0 else 1)
