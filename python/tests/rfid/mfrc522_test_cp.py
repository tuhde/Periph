import time
import _testconfig as cfg
from periph.transport.spi_circuitpython import SPITransport
from periph.chips.rfid.mfrc522 import MFRC522Full
import busio
import digitalio
import board

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


spi = busio.SPI(board.SCK, MOSI=board.MOSI, MISO=board.MISO)
cs  = digitalio.DigitalInOut(board.D5)
cs.switch_to_output(value=True)
spi.try_lock()
spi.configure(baudrate=1000000, phase=0, polarity=0)
spi.unlock()
transport = SPITransport(spi, cs)
mfrc = MFRC522Full(transport)

chip_type, version = mfrc.version()
check_true('chip_type == 0x09 (MFRC522)', chip_type == 0x09)
check_true('version in {1, 2}', version in (1, 2))

mfrc.antenna_on()
ctrl = mfrc._read_reg(0x14)
check_true('antenna_on sets TxControlReg bits 0|1', (ctrl & 0x03) == 0x03)
mfrc.antenna_off()
ctrl = mfrc._read_reg(0x14)
check_true('antenna_off clears TxControlReg bits 0|1', (ctrl & 0x03) == 0x00)
mfrc.antenna_on()

for dB in (18, 23, 33, 38, 43, 48):
    mfrc.set_antenna_gain(dB)
    g = mfrc.antenna_gain()
    check_true('set_antenna_gain({}) read back == {}'.format(dB, dB), g == dB)

present = mfrc.is_card_present()
check_true('is_card_present returns bool', isinstance(present, bool))

raw = mfrc._read_reg(0x37)
check_true('raw VersionReg in 0x90/0x91/0x92', raw in (0x90, 0x91, 0x92))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
