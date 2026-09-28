import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.comms.rfm9x import (
    RFM95Minimal, RFM95Full, RFM96Minimal, RFM97Minimal, RFM97Full,
)

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


class FakePin:
    """Minimal MicroPython-style Pin: value() reads, value(x) writes."""
    def __init__(self, initial=0):
        self._state = initial
        self.calls = []

    def value(self, x=None):
        if x is None:
            return self._state
        self._state = x
        self.calls.append(x)


REG_VERSION = 0x42
EXPECTED_VERSION = 0x12


def new_connection():
    c = SPIConnectionMock()
    c.set_register(REG_VERSION, [EXPECTED_VERSION])
    return c


# --- RFM95Minimal.__init__: version check, frequency, default config ---
connection = new_connection()
sensor = RFM95Minimal(connection, 915000000)
check_true('init_frf_written', bytes([0x86, 0xE4]) in connection.writes and bytes([0x87, 0xC0]) in connection.writes and bytes([0x88, 0x00]) in connection.writes)
check_true('init_default_modem_config', bytes([0x9D, 0x72]) in connection.writes and bytes([0x9E, 0x77]) in connection.writes)
check_true('init_default_tx_power', bytes([0x89, 0x8F]) in connection.writes)  # PA_CONFIG = PA_BOOST|(17-2)

try:
    bad_version_conn = SPIConnectionMock()
    bad_version_conn.set_register(REG_VERSION, [0x99])
    RFM95Minimal(bad_version_conn, 915000000)
    check_true('init_rejects_wrong_version', False)
except OSError:
    check_true('init_rejects_wrong_version', True)

try:
    RFM95Minimal(connection, 433000000)  # outside RFM95's HF range
    check_true('init_rejects_out_of_range_frequency', False)
except ValueError:
    check_true('init_rejects_out_of_range_frequency', True)

# LF variant (RFM96) uses a different frequency in-range and a different
# freq-band flag (LowFrequencyModeOn) -- just confirm it constructs cleanly.
lf_connection = new_connection()
RFM96Minimal(lf_connection, 433000000)
check_true('lf_variant_constructs', True)

# RFM97 has a lower MAX_SF (9 instead of 12) -- confirmed via configure() below.

# --- send(): FIFO write, payload length, TX mode, IRQ poll+clear, back to standby ---
# RFM95 is HF (freq_flag=0x08). MODE_TX=0x03 -> OP_MODE = 0x80|0x08|0x03 = 0x8B.
connection.set_register(0x12, [0x08])  # IRQ_FLAGS: TX_DONE set, poll succeeds immediately
del connection.writes[:]
sensor.send(bytes([0xDE, 0xAD, 0xBE]))
# send() calls _standby() first (writes[0]), then the FIFO/TX sequence.
check_true('send_fifo_addr_ptr', connection.writes[1] == bytes([0x8D, 0x80]))
check_true('send_fifo_payload', connection.writes[2] == bytes([0x80, 0xDE, 0xAD, 0xBE]))
check_true('send_payload_length', connection.writes[3] == bytes([0xA2, 0x03]))
check_true('send_dio_mapping', connection.writes[4] == bytes([0xC0, 0x40]))
check_true('send_tx_mode', connection.writes[5] == bytes([0x81, 0x83]))
check_true('send_clears_irq', bytes([0x92, 0x08]) in connection.writes)
check_true('send_returns_to_standby', connection.writes[-1] == bytes([0x81, 0x81]))

try:
    sensor.send(bytes(256))
    check_true('send_rejects_over_255_bytes', False)
except ValueError:
    check_true('send_rejects_over_255_bytes', True)

# --- receive(): RX mode, IRQ poll, FIFO read ---
connection.set_register(0x12, [0x40])  # IRQ_FLAGS: RX_DONE set
connection.set_register(0x10, [0x00])  # FIFO_RX_CURRENT = 0
connection.set_register(0x13, [0x03])  # RX_NB_BYTES = 3
connection.set_register(0x00, [0xAA, 0xBB, 0xCC])  # FIFO payload
del connection.writes[:]
payload = sensor.receive(timeout_ms=100)
check_true('receive_rx_mode', connection.writes[2] == bytes([0x81, 0x86]))
check_true('receive_returns_payload', payload == bytes([0xAA, 0xBB, 0xCC]))
check_true('receive_clears_irq', bytes([0x92, 0x40]) in connection.writes)

# receive() timeout: IRQ never set, neither RX_DONE nor RX_TIMEOUT bits.
connection.set_register(0x12, [0x00])
result = sensor.receive(timeout_ms=10)
check_true('receive_timeout_returns_none', result is None)

# --- RFM95Full: configure, set_frequency, set_tx_power, standby/sleep, telemetry, reset ---
full_connection = new_connection()
full = RFM95Full(full_connection, 915000000)

# configure(sf=9, bandwidth_khz=125.0, coding_rate=5, crc=True):
# MODEM_CONFIG_1 = (bw_code=7<<4)|((5-4)<<1)|0 = 0x72
# MODEM_CONFIG_2 = (9<<4)|(1<<2)|0x03 = 0x97
del full_connection.writes[:]
full.configure(sf=9, bandwidth_khz=125.0, coding_rate=5, crc=True)
check_true('configure_detection_opt', bytes([0xB1, 0x03]) in full_connection.writes)  # sf != 6
check_true('configure_modem_config_1', bytes([0x9D, 0x72]) in full_connection.writes)
check_true('configure_modem_config_2', bytes([0x9E, 0x97]) in full_connection.writes)

try:
    full.configure(sf=9, bandwidth_khz=999.0, coding_rate=5)
    check_true('configure_rejects_bad_bandwidth', False)
except ValueError:
    check_true('configure_rejects_bad_bandwidth', True)

# RFM97's MAX_SF is 9 (not 12 like the other three variants).
rfm97_connection = new_connection()
rfm97 = RFM97Full(rfm97_connection, 915000000)
try:
    rfm97.configure(sf=10, bandwidth_khz=125.0, coding_rate=5)
    check_true('configure_rejects_sf_over_variant_max', False)
except ValueError:
    check_true('configure_rejects_sf_over_variant_max', True)

# set_frequency(868 MHz): FRF = 0xD9, 0x00, 0x00
del full_connection.writes[:]
full.set_frequency(868000000)
check_true('set_frequency_writes_frf', full_connection.writes == [bytes([0x86, 0xD9]), bytes([0x87, 0x00]), bytes([0x88, 0x00])])

# set_tx_power(20, use_pa_boost=True): high-power path, PA_CONFIG=0x8F
del full_connection.writes[:]
full.set_tx_power(20, use_pa_boost=True)
check_true('set_tx_power_high_power_dac', bytes([0xCD, 0x87]) in full_connection.writes)  # PA_DAC_HIGH_POWER
check_true('set_tx_power_high_power_ocp', bytes([0x8B, 0x3B]) in full_connection.writes)  # OCP_240MA
check_true('set_tx_power_high_power_config', bytes([0x89, 0x8F]) in full_connection.writes)

# set_tx_power(10, use_pa_boost=False): RFO path, PA_CONFIG=0x7A
del full_connection.writes[:]
full.set_tx_power(10, use_pa_boost=False)
check_true('set_tx_power_rfo_config', bytes([0x89, 0x7A]) in full_connection.writes)

# standby() / sleep()
del full_connection.writes[:]
full.standby()
check_true('standby', full_connection.writes[-1] == bytes([0x81, 0x81]))
del full_connection.writes[:]
full.sleep()
check_true('sleep', full_connection.writes[-1] == bytes([0x81, 0x80]))

# version() / rssi() / last_packet_rssi() / last_packet_snr()
check_true('version', full.version() == 0x12)
full_connection.set_register(0x1B, [100])
check_true('rssi', full.rssi() == -137 + 100)
full_connection.set_register(0x1A, [90])
check_true('last_packet_rssi', full.last_packet_rssi() == -137 + 90)
full_connection.set_register(0x19, [20])  # positive SNR: 20/4 = 5.0 dB
check_true('last_packet_snr_positive', abs(full.last_packet_snr() - 5.0) < 1e-9)
full_connection.set_register(0x19, [0xF4])  # negative SNR: (244-256)/4 = -3.0 dB
check_true('last_packet_snr_negative', abs(full.last_packet_snr() - (-3.0)) < 1e-9)

# receive_continuous() / read_packet() / stop_receive()
del full_connection.writes[:]
full.receive_continuous()
check_true('receive_continuous_rx_cont_mode', full_connection.writes[-1] == bytes([0x81, 0x85]))  # MODE_RX_CONT=0x05 -> 0x80|0x08|0x05
full_connection.set_register(0x12, [0x40])
full_connection.set_register(0x10, [0x00])
full_connection.set_register(0x13, [0x02])
full_connection.set_register(0x00, [0x11, 0x22])
check_true('read_packet_returns_payload', full.read_packet() == bytes([0x11, 0x22]))
full_connection.set_register(0x12, [0x00])
check_true('read_packet_none_when_not_ready', full.read_packet() is None)
del full_connection.writes[:]
full.stop_receive()
check_true('stop_receive_returns_to_standby', full_connection.writes[-1] == bytes([0x81, 0x81]))

# receive(use_interrupt=True) requires dio0_pin
try:
    full.receive(use_interrupt=True)
    check_true('receive_interrupt_requires_dio0_pin', False)
except OSError:
    check_true('receive_interrupt_requires_dio0_pin', True)

dio0 = FakePin(initial=1)  # DIO0 already asserted -> immediate return
dio0_connection = new_connection()
dio0_connection.set_register(0x12, [0x40])
dio0_connection.set_register(0x10, [0x00])
dio0_connection.set_register(0x13, [0x01])
dio0_connection.set_register(0x00, [0x99])
full_with_dio0 = RFM95Full(dio0_connection, 915000000, dio0_pin=dio0)
check_true('receive_interrupt_returns_payload', full_with_dio0.receive(use_interrupt=True) == bytes([0x99]))

# --- reset(): requires reset_pin, pulses it low then high, re-runs init ---
try:
    full.reset()
    check_true('reset_without_pin_raises', False)
except OSError:
    check_true('reset_without_pin_raises', True)

reset_pin = FakePin(initial=1)
reset_connection = new_connection()
with_reset = RFM95Full(reset_connection, 915000000, reset_pin=reset_pin)
check_true('init_with_reset_pin_pulses', reset_pin.calls == [0, 1])
reset_pin.calls.clear()
with_reset.reset()
check_true('reset_pulses_pin', reset_pin.calls == [0, 1])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
