import time
import busio
import board
import digitalio
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
        print('FAIL {}: got 0x{:02X}, expected 0x{:02X}'.format(label, got, expected))
        failed += 1

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


spi = busio.SPI(board.SCK, MOSI=board.MOSI, MISO=board.MISO)
cs = digitalio.DigitalInOut(board.D5)
cs.switch_to_output(value=True)
transport = SPITransport(spi, cs)
radio = RFM95Full(transport, 868_000_000)

check_eq('version', radio.version(), 0x12)

radio.configure(sf=7, bandwidth_khz=125.0, coding_rate=5)
check_eq('configure_sf7', radio._sf, 7)

radio.set_frequency(868_500_000)
check_true('frequency_in_range', 868_000_000 <= radio._frequency_hz <= 1020_000_000)

radio.standby()
check_eq('standby_op_mode', radio._read_reg(0x01) & 0x07, 0x01)

radio.send(b"test123")
check_eq('irq_tx_done_cleared', radio._read_reg(0x12) & 0x08, 0x00)

radio.sleep()
check_eq('sleep_op_mode', radio._read_reg(0x01) & 0x07, 0x00)

radio.standby()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
