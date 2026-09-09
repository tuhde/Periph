import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.rfid.mfrc522 import MFRC522Full

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


# --- register addresses (module-private in the driver; mirrored here) ------
# SPI addressing: write = (reg<<1)&0x7E, read = write|0x80.
_REG_COMMAND        = 0x01
_REG_COM_IRQ        = 0x04
_REG_DIV_IRQ        = 0x05
_REG_ERROR          = 0x06
_REG_STATUS_2       = 0x08
_REG_FIFO_DATA      = 0x09
_REG_FIFO_LEVEL     = 0x0A
_REG_BIT_FRAMING    = 0x0D
_REG_MODE           = 0x11
_REG_TX_MODE        = 0x12
_REG_RX_MODE        = 0x13
_REG_TX_CONTROL     = 0x14
_REG_TX_ASK         = 0x15
_REG_T_MODE         = 0x2A
_REG_T_PRESCALER    = 0x2B
_REG_T_RELOAD_H     = 0x2C
_REG_T_RELOAD_L     = 0x2D
_REG_RF_CFG         = 0x26
_REG_AUTO_TEST      = 0x36
_REG_VERSION        = 0x37


def waddr(reg):
    return (reg << 1) & 0x7E


def raddr(reg):
    return waddr(reg) | 0x80


class FifoAwareMock(I2CConnectionMock):
    """I2CConnectionMock plus a FIFO: MFRC522 reads FIFO_LEVEL then FIFO_DATA
    one byte at a time via write_read(), which the base register-map mock
    can't model on its own (a plain register always returns the same fixed
    byte, but FIFO_LEVEL/FIFO_DATA must reflect "how many bytes are left in
    *this* response" across several transceive rounds in one call, e.g.
    read_uid()'s REQA -> anticollision -> select -> halt sequence).

    queue_fifo(chunk) queues one whole response as a unit. FIFO_LEVEL reads
    report the current front chunk's remaining length; FIFO_DATA reads pop
    one byte from it, and the chunk is dropped once drained so the next
    queued response becomes visible to the next FIFO_LEVEL read.
    """

    def __init__(self):
        super().__init__()
        self._fifo_chunks = []

    def queue_fifo(self, data):
        self._fifo_chunks.append(list(data))

    def write_read(self, data, n):
        addr = data[0]
        if addr == raddr(_REG_FIFO_LEVEL) and n == 1:
            self.writes.append(bytes(data))
            level = len(self._fifo_chunks[0]) if self._fifo_chunks else 0
            return bytes([level])
        if addr == raddr(_REG_FIFO_DATA) and n == 1:
            self.writes.append(bytes(data))
            if not self._fifo_chunks:
                return bytes([0])
            byte = self._fifo_chunks[0].pop(0)
            if not self._fifo_chunks[0]:
                self._fifo_chunks.pop(0)
            return bytes([byte])
        return super().write_read(data, n)


def new_connection():
    conn = FifoAwareMock()
    # COM_IRQ read during init's PowerDown-clear poll: default 0 -> bit4
    # clear immediately, loop exits on first read.
    return conn


def writes_at(conn, addr):
    return [w for w in conn.writes if len(w) == 2 and w[0] == addr]


connection = new_connection()
sensor = MFRC522Full(connection)
check_true('init', True)

check_true('init_soft_reset', writes_at(connection, waddr(_REG_COMMAND))[0][1] == 0x0F)
check_true('init_timer_mode', writes_at(connection, waddr(_REG_T_MODE))[-1][1] == 0x80)
check_true('init_timer_prescaler', writes_at(connection, waddr(_REG_T_PRESCALER))[-1][1] == 0xA9)
check_true('init_timer_reload_h', writes_at(connection, waddr(_REG_T_RELOAD_H))[-1][1] == 0x03)
check_true('init_timer_reload_l', writes_at(connection, waddr(_REG_T_RELOAD_L))[-1][1] == 0xE8)
check_true('init_force_100_ask', writes_at(connection, waddr(_REG_TX_ASK))[-1][1] == 0x40)
check_true('init_mode_crc_a', writes_at(connection, waddr(_REG_MODE))[-1][1] == 0x3D)
check_true('init_antenna_on', connection.registers.get(waddr(_REG_TX_CONTROL), 0) & 0x03 == 0x03)


def prep_transceive(conn, com_irq=0x30, error=0x00, fifo_bytes=None):
    """Set the constant "transceive completed" IRQ/error signal for every
    _card_command(TRANSCEIVE) round-trip on this connection (every call
    reads these fresh, and every test here wants the same outcome for all
    of a sequence's steps), then queue one response chunk."""
    conn.set_register(raddr(_REG_COM_IRQ), com_irq)
    conn.set_register(raddr(_REG_ERROR), error)
    if fifo_bytes is not None:
        conn.queue_fifo(fifo_bytes)


# --- is_card_present(): REQA -> 2-byte ATQA ---------------------------------
connection2 = new_connection()
sensor2 = MFRC522Full(connection2)
prep_transceive(connection2, fifo_bytes=[0x04, 0x00])
check_true('is_card_present_true', sensor2.is_card_present() is True)

connection3 = new_connection()
sensor3 = MFRC522Full(connection3)
prep_transceive(connection3, com_irq=0x01)  # TimerIRq only -> no card
check_true('is_card_present_false', sensor3.is_card_present() is False)


# --- read_uid(): single cascade level (4-byte UID) --------------------------
connection4 = new_connection()
sensor4 = MFRC522Full(connection4)
uid_bytes = [0x12, 0x34, 0x56, 0x78]
bcc = 0
for b in uid_bytes:
    bcc ^= b


connection4.set_register(raddr(_REG_COM_IRQ), 0x30)
connection4.set_register(raddr(_REG_ERROR), 0x00)
# Every _calc_crc() call in this flow (inside _select() and _halt_card())
# polls DIV_IRQ, which defaults to 0 (never set here) - it just runs its
# full bounded retry loop and returns a placeholder CRC, which is fine:
# the mock's transceive success is keyed on COM_IRQ/ERROR, not on the CRC
# bytes actually being cryptographically correct.
# REQA response (is_card_present(), called first by read_uid())
connection4.queue_fifo([0x04, 0x00])
# Anticollision CL1 response: 4 UID bytes + BCC
connection4.queue_fifo(uid_bytes + [bcc])
# Select CL1 response: SAK with completion bit clear (0x00 = complete, single-size UID)
connection4.queue_fifo([0x00])
# HLTA (halt) - result ignored by the driver, no response bytes needed

uid = sensor4.read_uid()
check_true('read_uid', uid == bytes(uid_bytes))

connection5 = new_connection()
sensor5 = MFRC522Full(connection5)
connection5.set_register(raddr(_REG_COM_IRQ), 0x01)  # TimerIRq only -> no card
check_true('read_uid_none', sensor5.read_uid() is None)


# --- antenna control ---------------------------------------------------------
connection6 = new_connection()
sensor6 = MFRC522Full(connection6)
sensor6.antenna_off()
check_true('antenna_off', connection6.registers[waddr(_REG_TX_CONTROL)] & 0x03 == 0x00)
sensor6.antenna_on()
check_true('antenna_on', connection6.registers[waddr(_REG_TX_CONTROL)] & 0x03 == 0x03)

sensor6.set_antenna_gain(38)
check_true('set_antenna_gain', connection6.registers[waddr(_REG_RF_CFG)] & 0x70 == 0x50)
connection6.set_register(raddr(_REG_RF_CFG), 0x60)
check_true('antenna_gain', sensor6.antenna_gain() == 43)

try:
    sensor6.set_antenna_gain(99)
    check_true('set_antenna_gain_invalid_raises', False)
except ValueError:
    check_true('set_antenna_gain_invalid_raises', True)


# --- version() ---------------------------------------------------------------
connection7 = new_connection()
sensor7 = MFRC522Full(connection7)
connection7.set_register(raddr(_REG_VERSION), 0x92)  # chip_type=9, version=2
check_true('version', sensor7.version() == (9, 2))


# --- self_test(): FIFO fills to >=64 bytes on the first CalcCRC iteration, --
# then read_fifo(64) must return the exact v1.0 reference table.
connection8 = new_connection()
sensor8 = MFRC522Full(connection8)
connection8.set_register(raddr(_REG_VERSION), 0x91)  # version=1 -> v1.0 reference table
ref_v10 = [
    0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
    0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
    0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
    0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
    0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
    0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
    0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
    0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
]
connection8.queue_fifo(ref_v10)
check_true('self_test_pass', sensor8.self_test() is True)


# --- authenticate() / stop_crypto() ------------------------------------------
connection9 = new_connection()
sensor9 = MFRC522Full(connection9)
connection9.set_register(raddr(_REG_STATUS_2), 0x08)  # MFCrypto1On set immediately
ok = sensor9.authenticate(4, MFRC522Full.KEY_A, bytes([0xFF] * 6), bytes(uid_bytes))
check_true('authenticate', ok is True)

sensor9.stop_crypto()
check_true('stop_crypto', connection9.registers[waddr(_REG_STATUS_2)] & 0x08 == 0x00)

connection9b = new_connection()
sensor9b = MFRC522Full(connection9b)
check_true('authenticate_bad_key_length', sensor9b.authenticate(4, MFRC522Full.KEY_A, bytes([0xFF] * 5), bytes(uid_bytes)) is False)


# --- read_block() / write_block() (via the CRC + transceive mocked flow) ----
connection10 = new_connection()
sensor10 = MFRC522Full(connection10)
block_data = bytes(range(16))
# _calc_crc() polls DIV_IRQ; make it show CRCIRq set immediately, and preload
# CRC_RESULT_H/L (module-private regs 0x21/0x22) with a fixed placeholder -
# the driver just forwards whatever the chip returns as the trailing 2
# command bytes, so any placeholder value round-trips correctly.
_REG_DIV_IRQ_R = raddr(_REG_DIV_IRQ)
_REG_CRC_H_R = raddr(0x21)
_REG_CRC_L_R = raddr(0x22)
connection10.set_register(_REG_DIV_IRQ_R, 0x04)
connection10.set_register(_REG_CRC_H_R, 0xAB)
connection10.set_register(_REG_CRC_L_R, 0xCD)
connection10.set_register(raddr(_REG_COM_IRQ), 0x30)
connection10.set_register(raddr(_REG_ERROR), 0x00)
connection10.queue_fifo(list(block_data))
got = sensor10.read_block(4)
check_true('read_block', got == block_data)

connection11 = new_connection()
sensor11 = MFRC522Full(connection11)
connection11.set_register(_REG_DIV_IRQ_R, 0x04)
connection11.set_register(_REG_CRC_H_R, 0xAB)
connection11.set_register(_REG_CRC_L_R, 0xCD)
connection11.set_register(raddr(_REG_COM_IRQ), 0x30)
connection11.set_register(raddr(_REG_ERROR), 0x00)
connection11.queue_fifo([0x0A])  # phase 1 ACK (0x0A in low nibble)
connection11.queue_fifo([0x0A])  # phase 2 ACK
check_true('write_block', sensor11.write_block(4, block_data) is True)


print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
