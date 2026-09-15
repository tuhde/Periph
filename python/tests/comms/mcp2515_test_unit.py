import sys
from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.comms.mcp2515 import MCP2515Minimal, MCP2515Full, CanFrame

passed = 0
failed = 0

def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label); passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected)); failed += 1

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label); passed += 1
    else:
        print('FAIL', label); failed += 1


# --- CanFrame validation ---
try:
    CanFrame(0x800, b'', extended=False)
    check_true('CanFrame rejects standard id > 0x7FF', False)
except ValueError:
    check_true('CanFrame rejects standard id > 0x7FF', True)

try:
    CanFrame(0x20000000, b'', extended=True)
    check_true('CanFrame rejects extended id > 0x1FFFFFFF', False)
except ValueError:
    check_true('CanFrame rejects extended id > 0x1FFFFFFF', True)

try:
    CanFrame(0x123, b'\x00' * 9)
    check_true('CanFrame rejects dlc > 8', False)
except ValueError:
    check_true('CanFrame rejects dlc > 8', True)

# --- _pack_id: standard frame ---
# id 0x123 => SIDH = 0x24, SIDL = 0x60 (bits 7:5 = 0b011), EID[17:16] ignored
def _ids(can, can_id, extended):
    return can._pack_id(can_id, extended)

conn = SPIConnectionMock()
chip = MCP2515Minimal.__new__(MCP2515Minimal)
sidh, sidl, eid8, eid0 = _ids(chip, 0x123, extended=False)
check_eq('pack std SIDH 0x123', sidh, 0x24)
check_eq('pack std SIDL 0x123', sidl, 0x60)
check_eq('pack std EID8 0x123', eid8, 0x00)
check_eq('pack std EID0 0x123', eid0, 0x00)

# --- _pack_id: extended frame ---
# id 0x1FFFFFFF (max) =>
#   SIDH = EID[28:21] = 0xFF
#   SIDL = SID[2:0]=EID[20:18]=0b111 << 5 = 0xE0 | 0x08 (EXIDE) | EID[17:16]=0b11 = 0xEB
#   EID8 = EID[15:8] = 0xFF
#   EID0 = EID[7:0] = 0xFF
sidh, sidl, eid8, eid0 = _ids(chip, 0x1FFFFFFF, extended=True)
check_eq('pack ext SIDH max', sidh, 0xFF)
check_eq('pack ext SIDL max', sidl, 0xEB)
check_eq('pack ext EID8 max', eid8, 0xFF)
check_eq('pack ext EID0 max', eid0, 0xFF)

# --- _unpack_id roundtrip ---
for can_id in (0x001, 0x123, 0x7FF, 0x1FFFFFFF, 0x18FF1234):
    s, l, e8, e0 = _ids(chip, can_id, can_id > 0x7FF)
    ide = bool(l & 0x08)
    rt = chip._unpack_id(s, l, e8, e0, ide)
    check_eq('roundtrip id 0x%X' % can_id, rt, can_id)

# --- send() loads a TX buffer and issues RTS ---
conn = SPIConnectionMock()
chip = MCP2515Minimal.__new__(MCP2515Minimal)
chip._connection = conn
chip._CANCTRL_REQOP_NORMAL = MCP2515Minimal._CANCTRL_REQOP_NORMAL
chip._REG_TXB0CTRL = MCP2515Minimal._REG_TXB0CTRL
chip._REG_TXB1CTRL = MCP2515Minimal._REG_TXB1CTRL
chip._REG_TXB2CTRL = MCP2515Minimal._REG_TXB2CTRL
chip._TXBnCTRL_TXREQ = MCP2515Minimal._TXBnCTRL_TXREQ
chip._INSTR_LOAD_TX_BUF = MCP2515Minimal._INSTR_LOAD_TX_BUF
chip._INSTR_RTS = MCP2515Minimal._INSTR_RTS
chip._read_reg = lambda reg: 0x00  # TXREQ cleared immediately
chip._tx_free_buf = lambda timeout_ms=10: 0  # always TXB0
chip._send(0x123, b'\xDE\xAD', extended=False, rtr=False, buf_index=0)
# Expect one LOAD TX BUFFER (0x40 + 0 offset) plus one RTS (0x80 | 0x01)
write_instrs = [w[0] for w in conn.writes if w[0] in (0x40, 0x41, 0x42, 0x43, 0x44, 0x80, 0x81, 0x82, 0x83, 0x84)]
check_true('send issues LOAD TX BUFFER', 0x40 in write_instrs)
check_true('send issues RTS for TXB0', 0x81 in write_instrs)

# --- send() for extended frame sets EXIDE bit in SIDL ---
conn = SPIConnectionMock()
chip = MCP2515Minimal.__new__(MCP2515Minimal)
chip._connection = conn
chip._CANCTRL_REQOP_NORMAL = MCP2515Minimal._CANCTRL_REQOP_NORMAL
chip._REG_TXB0CTRL = MCP2515Minimal._REG_TXB0CTRL
chip._REG_TXB1CTRL = MCP2515Minimal._REG_TXB1CTRL
chip._REG_TXB2CTRL = MCP2515Minimal._REG_TXB2CTRL
chip._TXBnCTRL_TXREQ = MCP2515Minimal._TXBnCTRL_TXREQ
chip._INSTR_LOAD_TX_BUF = MCP2515Minimal._INSTR_LOAD_TX_BUF
chip._INSTR_RTS = MCP2515Minimal._INSTR_RTS
chip._read_reg = lambda reg: 0x00
chip._tx_free_buf = lambda timeout_ms=10: 0
chip._send(0x18FF1234, b'\x00', extended=True, rtr=False, buf_index=0)
# LOAD TX BUFFER (1 byte instr) + 5 (SIDH/SIDL/EID8/EID0/DLC) + 8 (data) = 14 bytes
load_write = next((w for w in conn.writes if w[0] == 0x40), None)
check_true('extended send writes 14 bytes', load_write is not None and len(load_write) == 14)
check_eq('extended send SIDL has EXIDE', load_write[2] & 0x08, 0x08)

# --- recv() returns a parsed CanFrame on RX0IF ---
conn = SPIConnectionMock()
# Preload READ STATUS (0xA0) to return RX0IF
conn.set_register(MCP2515Minimal._INSTR_READ_STATUS, MCP2515Minimal._CANINTF_RX0IF)
# Preload READ RX BUFFER (0x90) at offset 0: 13 bytes total
# SIDH=0x24, SIDL=0x60, EID8=0, EID0=0, DLC=2, data=0xDE 0xAD + 5 padding
conn.set_register(
    MCP2515Minimal._INSTR_READ_RX_BUF,
    [0x24, 0x60, 0x00, 0x00, 0x02, 0xDE, 0xAD, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00],
)
chip = MCP2515Minimal.__new__(MCP2515Minimal)
chip._connection = conn
chip._INSTR_READ_STATUS = MCP2515Minimal._INSTR_READ_STATUS
chip._INSTR_READ_RX_BUF = MCP2515Minimal._INSTR_READ_RX_BUF
chip._CANINTF_RX0IF = MCP2515Minimal._CANINTF_RX0IF
chip._CANINTF_RX1IF = MCP2515Minimal._CANINTF_RX1IF
frame = chip._poll_rx(0)
check_true('recv returns CanFrame', isinstance(frame, CanFrame))
if isinstance(frame, CanFrame):
    check_eq('recv id', frame.id, 0x123)
    check_eq('recv data', frame.data, b'\xDE\xAD')
    check_eq('recv extended', frame.extended, False)
    check_eq('recv rtr', frame.rtr, False)

# --- recv() with no frame returns None ---
conn = SPIConnectionMock()
conn.set_register(MCP2515Minimal._INSTR_READ_STATUS, 0x00)  # READ STATUS returns 0
chip = MCP2515Minimal.__new__(MCP2515Minimal)
chip._connection = conn
chip._INSTR_READ_STATUS = MCP2515Minimal._INSTR_READ_STATUS
chip._INSTR_READ_RX_BUF = MCP2515Minimal._INSTR_READ_RX_BUF
chip._CANINTF_RX0IF = MCP2515Minimal._CANINTF_RX0IF
chip._CANINTF_RX1IF = MCP2515Minimal._CANINTF_RX1IF
frame = chip._poll_rx(0)
check_eq('recv None when empty', frame, None)

# --- Full set_filter / set_mask preserve other bits ---
conn = SPIConnectionMock()
chip = MCP2515Full.__new__(MCP2515Full)
chip._connection = conn
chip._CANCTRL_REQOP_CONFIG = MCP2515Minimal._CANCTRL_REQOP_CONFIG
chip._CANSTAT_OPMOD_CONFIG = MCP2515Minimal._CANSTAT_OPMOD_CONFIG
chip._CANSTAT_OPMOD_NORMAL = MCP2515Minimal._CANSTAT_OPMOD_NORMAL
chip._REG_CANCTRL = MCP2515Minimal._REG_CANCTRL
chip._REG_CANSTAT = MCP2515Minimal._REG_CANSTAT
chip._REG_CNF1 = MCP2515Minimal._REG_CNF1
chip._REG_CNF2 = MCP2515Minimal._REG_CNF2
chip._REG_CNF3 = MCP2515Minimal._REG_CNF3
chip._REG_RXB0CTRL = MCP2515Minimal._REG_RXB0CTRL
chip._REG_RXB1CTRL = MCP2515Minimal._REG_RXB1CTRL
chip._REG_CANINTE = MCP2515Minimal._REG_CANINTE
chip._REG_TXB0CTRL = MCP2515Minimal._REG_TXB0CTRL
chip._REG_TXB1CTRL = MCP2515Minimal._REG_TXB1CTRL
chip._REG_TXB2CTRL = MCP2515Minimal._REG_TXB2CTRL
chip._REG_RXM0SIDH = MCP2515Minimal._REG_RXM0SIDH
chip._REG_RXM1SIDH = MCP2515Minimal._REG_RXM1SIDH
chip._TXBnCTRL_TXP_MASK = MCP2515Minimal._TXBnCTRL_TXP_MASK
chip._RXB0CTRL_RXM_MASK = MCP2515Minimal._RXB0CTRL_RXM_MASK
chip._RXB0CTRL_RXM_ANY = MCP2515Minimal._RXB0CTRL_RXM_ANY
chip._RXB0CTRL_BUKT = MCP2515Minimal._RXB0CTRL_BUKT
chip._CANCTRL_REQOP_NORMAL = MCP2515Minimal._CANCTRL_REQOP_NORMAL
chip._INSTR_WRITE = MCP2515Minimal._INSTR_WRITE
chip._INSTR_READ = MCP2515Minimal._INSTR_READ
chip._INSTR_BIT_MODIFY = MCP2515Minimal._INSTR_BIT_MODIFY
chip._INSTR_READ_STATUS = MCP2515Minimal._INSTR_READ_STATUS
chip._CANSTAT_OPMOD_MASK = MCP2515Minimal._CANSTAT_OPMOD_MASK
chip._CNF_PRESCALER_8MHZ = MCP2515Minimal._CNF_PRESCALER_8MHZ
chip._CNF_PRESCALER_16MHZ = MCP2515Minimal._CNF_PRESCALER_16MHZ
chip._bitrate_kbps = 125
chip._osc_mhz = 8
chip._modify_reg = lambda reg, mask, value: conn.write(bytes([MCP2515Minimal._INSTR_BIT_MODIFY, reg, mask, value]))
chip._write_reg = lambda reg, value: conn.write(bytes([MCP2515Minimal._INSTR_WRITE, reg, value]))
chip._read_reg = lambda reg: conn.registers.get(reg, 0)
chip._wait_op_mode = lambda target, timeout_ms=100: None
chip._reset = lambda: conn.write(bytes([MCP2515Minimal._INSTR_RESET]))
chip._sleep_ms = lambda ms: None
chip._get_mode = lambda: 'normal'
chip._set_mode = lambda mode: None
chip._set_filter(0, 0x123, extended=False)
# Filter 0 lives at base 0x00. Expect WRITE (0x02) to 0x00 with SIDH=0x24.
filter_writes = [w for w in conn.writes if len(w) == 3 and w[0] == MCP2515Minimal._INSTR_WRITE]
check_true('set_filter issues WRITE', len(filter_writes) >= 4)
sidh_write = next((w for w in filter_writes if w[1] == 0x00), None)
check_true('set_filter writes SIDH at 0x00', sidh_write is not None and sidh_write[2] == 0x24)
sidl_write = next((w for w in filter_writes if w[1] == 0x01), None)
check_true('set_filter writes SIDL at 0x01', sidl_write is not None and sidl_write[2] == 0x60)

chip._set_mask(0, 0x7FF, extended=False)
mask_writes = [w for w in conn.writes if len(w) == 3 and w[0] == MCP2515Minimal._INSTR_WRITE and w[1] >= 0x20 and w[1] <= 0x23]
check_true('set_mask issues WRITE to RXM0', len(mask_writes) == 4)
sidh_write = next((w for w in mask_writes if w[1] == 0x20), None)
check_true('set_mask writes SIDH at 0x20', sidh_write is not None and sidh_write[2] == 0xFF)
sidl_write = next((w for w in mask_writes if w[1] == 0x21), None)
check_true('set_mask writes SIDL at 0x21', sidl_write is not None and sidl_write[2] == 0xE0)

# --- _set_rx_mode issues BIT MODIFY on RXBnCTRL ---
conn = SPIConnectionMock()
chip._modify_reg = lambda reg, mask, value: conn.write(bytes([MCP2515Minimal._INSTR_BIT_MODIFY, reg, mask, value]))
chip._set_rx_mode(0, 3)
check_eq('set_rx_mode 3 BIT MODIFY', conn.writes[-1][0], MCP2515Minimal._INSTR_BIT_MODIFY)
check_eq('set_rx_mode targets RXB0CTRL', conn.writes[-1][1], MCP2515Minimal._REG_RXB0CTRL)
check_eq('set_rx_mode value 3', conn.writes[-1][3], 3 << 5)

# --- _set_one_shot writes OSM bit ---
conn = SPIConnectionMock()
chip._modify_reg = lambda reg, mask, value: conn.write(bytes([MCP2515Minimal._INSTR_BIT_MODIFY, reg, mask, value]))
chip._set_one_shot(True)
check_eq('set_one_shot True OSM', conn.writes[-1][3], 0x08)
chip._set_one_shot(False)
check_eq('set_one_shot False OSM', conn.writes[-1][3], 0x00)

# --- _clear_overflow sends BIT MODIFY with EFLG bit cleared ---
conn = SPIConnectionMock()
chip._modify_reg = lambda reg, mask, value: conn.write(bytes([MCP2515Minimal._INSTR_BIT_MODIFY, reg, mask, value]))
chip._clear_overflow(0)
check_eq('clear_overflow 0 mask RX0OVR', conn.writes[-1][2], MCP2515Minimal._EFLG_RX0OVR)
check_eq('clear_overflow 0 value', conn.writes[-1][3], 0x00)
chip._clear_overflow(1)
check_eq('clear_overflow 1 mask RX1OVR', conn.writes[-1][2], MCP2515Minimal._EFLG_RX1OVR)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
