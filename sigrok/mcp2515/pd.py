"""
MCP2515 sigrok protocol decoder.

Decodes Microchip MCP2515 stand-alone CAN 2.0B controller SPI transactions
into chip-level register reads, writes, and the special instruction bytes
(RESET, RTS, READ STATUS, RX STATUS, BIT MODIFY, LOAD TX BUFFER, READ RX
BUFFER). The decoder reassembles CAN identifiers from SIDH/SIDL/EID8/EID0
for both TX (LOAD TX BUFFER) and RX (READ RX BUFFER) operations and decodes
the READ STATUS and RX STATUS flag bits.

The decoder sits on top of the ``spi`` protocol decoder.
"""

import sigrokdecode as srd


# SPI instruction bytes (top-level dispatch).
INSTR_RESET       = 0xC0
INSTR_READ        = 0x03
INSTR_WRITE       = 0x02
INSTR_READ_STATUS = 0xA0
INSTR_RX_STATUS   = 0xB0
INSTR_BIT_MODIFY  = 0x05
INSTR_RTS_BASE    = 0x80
INSTR_LOAD_TX_BASE = 0x40
INSTR_READ_RX_BASE = 0x90

# LOAD TX BUFFER / READ RX BUFFER offsets (low 4 bits).
TX_BUF_OFFSETS = {
    0x00: 'TXB0SIDH', 0x02: 'TXB0D0',   0x04: 'TXB1SIDH', 0x06: 'TXB1D0',
    0x08: 'TXB2SIDH', 0x0A: 'TXB2D0',
}
RX_BUF_OFFSETS = {
    0x00: 'RXB0SIDH', 0x02: 'RXB0D0',   0x04: 'RXB1SIDH', 0x06: 'RXB1D1',
}

REGS = {
    0x00: 'RXF0SIDH', 0x01: 'RXF0SIDL', 0x02: 'RXF0EID8', 0x03: 'RXF0EID0',
    0x04: 'RXF1SIDH', 0x05: 'RXF1SIDL', 0x06: 'RXF1EID8', 0x07: 'RXF1EID0',
    0x08: 'RXF2SIDH', 0x09: 'RXF2SIDL', 0x0A: 'RXF2EID8', 0x0B: 'RXF2EID0',
    0x0C: 'BFPCTRL',  0x0D: 'TXRTSCTRL',
    0x0E: 'CANSTAT',  0x0F: 'CANCTRL',
    0x10: 'RXF3SIDH', 0x11: 'RXF3SIDL', 0x12: 'RXF3EID8', 0x13: 'RXF3EID0',
    0x14: 'RXF4SIDH', 0x15: 'RXF4SIDL', 0x16: 'RXF4EID8', 0x17: 'RXF4EID0',
    0x18: 'RXF5SIDH', 0x19: 'RXF5SIDL', 0x1A: 'RXF5EID8', 0x1B: 'RXF5EID0',
    0x1C: 'TEC',      0x1D: 'REC',
    0x20: 'RXM0SIDH', 0x21: 'RXM0SIDL', 0x22: 'RXM0EID8', 0x23: 'RXM0EID0',
    0x24: 'RXM1SIDH', 0x25: 'RXM1SIDL', 0x26: 'RXM1EID8', 0x27: 'RXM1EID0',
    0x28: 'CNF3',     0x29: 'CNF2',     0x2A: 'CNF1',
    0x2B: 'CANINTE',  0x2C: 'CANINTF',  0x2D: 'EFLG',
    0x30: 'TXB0CTRL',
    0x31: 'TXB0SIDH', 0x32: 'TXB0SIDL', 0x33: 'TXB0EID8', 0x34: 'TXB0EID0',
    0x35: 'TXB0DLC',  0x36: 'TXB0D0',
    0x40: 'TXB1CTRL',
    0x41: 'TXB1SIDH', 0x42: 'TXB1SIDL', 0x43: 'TXB1EID8', 0x44: 'TXB1EID0',
    0x45: 'TXB1DLC',  0x46: 'TXB1D0',
    0x50: 'TXB2CTRL',
    0x51: 'TXB2SIDH', 0x52: 'TXB2SIDL', 0x53: 'TXB2EID8', 0x54: 'TXB2EID0',
    0x55: 'TXB2DLC',  0x56: 'TXB2D0',
    0x60: 'RXB0CTRL',
    0x61: 'RXB0SIDH', 0x62: 'RXB0SIDL', 0x63: 'RXB0EID8', 0x64: 'RXB0EID0',
    0x65: 'RXB0DLC',  0x66: 'RXB0D0',
    0x70: 'RXB1CTRL',
    0x71: 'RXB1SIDH', 0x72: 'RXB1SIDL', 0x73: 'RXB1EID8', 0x74: 'RXB1EID0',
    0x75: 'RXB1DLC',  0x76: 'RXB1D0',
}

READ_STATUS_BITS = [
    (0x80, 'TX2IF'),  (0x40, 'TXB2REQ'),
    (0x20, 'TX1IF'),  (0x10, 'TXB1REQ'),
    (0x08, 'TX0IF'),  (0x04, 'TXB0REQ'),
    (0x02, 'RX1IF'),  (0x01, 'RX0IF'),
]

OPMOD_NAMES = {
    0x00: 'Normal', 0x20: 'Sleep', 0x40: 'Loopback',
    0x60: 'Listen-Only', 0x80: 'Configuration',
}

CANINTF_NAMES = [
    (0x80, 'MERRF'), (0x40, 'WAKIF'), (0x20, 'ERRIF'),
    (0x10, 'TX2IF'),  (0x08, 'TX1IF'),  (0x04, 'TX0IF'),
    (0x02, 'RX1IF'),  (0x01, 'RX0IF'),
]

EFLG_NAMES = [
    (0x80, 'RX1OVR'), (0x40, 'RX0OVR'),
    (0x20, 'TXBO'),   (0x10, 'TXEP'),
    (0x08, 'RXEP'),   (0x04, 'TXWAR'),
    (0x02, 'RXWAR'),  (0x01, 'EWARN'),
]

ANN_INSTR     = 0
ANN_REG_WRITE = 1
ANN_REG_READ  = 2
ANN_TX_FRAME  = 3
ANN_RX_FRAME  = 4
ANN_STATUS    = 5
ANN_BIT_MOD   = 6
ANN_RTS       = 7
ANN_WARNING   = 8
# Named start/end pair for the "reset_to_config" conformance check (see
# specs/comms/mcp2515.md, Timing Constraints, and specs/comms/mcp2515_timing.conf):
# the SPI RESET instruction (0xC0) marks reset_to_config_start; the next
# CS-asserted instruction byte (any other opcode) marks reset_to_config_done.
# The bound is the minimum elapsed time between them.
ANN_RESET_START = 9
ANN_RESET_DONE  = 10


def _unpack_id(sidh, sidl, eid8, eid0, ide):
    if ide:
        return ((sidh << 21)
                | ((sidl >> 5) << 18)
                | ((sidl & 0x03) << 16)
                | (eid8 << 8)
                | eid0)
    return (sidh << 3) | (sidl >> 5)


def _decode_can_id(sidh, sidl, eid8, eid0):
    ide = bool(sidl & 0x08)
    rtr_std = bool(sidl & 0x10)
    can_id = _unpack_id(sidh, sidl, eid8, eid0, ide)
    kind = 'EXT' if ide else 'STD'
    rtr = rtr_std if not ide else bool(eid8 & 0x40)  # RTR in DLC for ext
    return kind, rtr, can_id


def _format_id_hex(can_id, extended):
    if extended:
        return '0x%08X' % can_id
    return '0x%03X' % can_id


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mcp2515'
    name = 'MCP2515'
    longname = 'Microchip MCP2515 stand-alone CAN 2.0B controller'
    desc = 'Decode MCP2515 SPI transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['mcp2515']
    tags = ['IC', 'Comms', 'CAN']

    annotations = (
        ('instr',       'SPI instruction'),
        ('reg-write',   'Register write'),
        ('reg-read',    'Register read'),
        ('tx-frame',    'TX buffer load'),
        ('rx-frame',    'RX buffer read'),
        ('status',      'Status byte'),
        ('bit-mod',     'BIT MODIFY'),
        ('rts',         'Request-to-send'),
        ('warning',     'Warning'),
        ('reset-start', 'RESET start'),
        ('reset-done',  'RESET done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_INSTR, ANN_REG_WRITE, ANN_REG_READ,
                                   ANN_TX_FRAME, ANN_RX_FRAME, ANN_STATUS,
                                   ANN_BIT_MOD, ANN_RTS)),
        ('timing',   'Timing',   (ANN_RESET_START, ANN_RESET_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.mosi_buf   = []
        self.miso_buf   = []
        self.ss_block   = None
        self.cs_active  = False
        self.last_reset_ss = None
        self.last_reset_es = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if not self.cs_active or not self.mosi_buf:
            return
        ss, es = self.ss_block, self.es
        first = self.mosi_buf[0]
        resp = self.miso_buf[1:] if len(self.miso_buf) > 1 else []

        # If the previous transaction was a RESET, this CS assertion
        # marks the end of the reset_to_config interval.
        if self.last_reset_es is not None and first != INSTR_RESET:
            self.put(self.last_reset_ss, self.last_reset_es, self.out_ann,
                     [ANN_RESET_DONE,
                      ['reset_to_config_done: next instruction 0x%02X' % first,
                       'reset_to_config_done',
                       'RST.']])
            self.last_reset_ss = None
            self.last_reset_es = None

        if first == INSTR_RESET:
            self.put(ss, es, self.out_ann,
                     [ANN_INSTR, ['RESET', 'RESET']])
            self.put(ss, es, self.out_ann,
                     [ANN_RESET_START,
                      ['reset_to_config_start: SPI RESET (0xC0)',
                       'reset_to_config_start',
                       'RST!']])
            self.last_reset_ss = ss
            self.last_reset_es = es
            return

        if first == INSTR_READ_STATUS:
            if resp:
                flags = [name for mask, name in READ_STATUS_BITS if resp[0] & mask]
                flag_str = ' '.join(flags) if flags else 'none'
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS,
                          ['READ STATUS 0x%02X: %s' % (resp[0], flag_str),
                           'STATUS=0x%02X' % resp[0]]])
            else:
                self.put(ss, es, self.out_ann,
                         [ANN_INSTR, ['READ STATUS', 'RSTAT']])
            return

        if first == INSTR_RX_STATUS:
            if resp:
                raw = resp[0]
                msg_type = (raw >> 6) & 0x03
                ide = bool(raw & 0x20)
                rtr = bool(raw & 0x10)
                filt = raw & 0x07
                type_str = {0: 'none', 1: 'RXB0', 2: 'RXB1', 3: 'both'}.get(msg_type, '?')
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS,
                          ['RX STATUS 0x%02X: msg=%s %s%s filt=%d' % (
                              raw, type_str,
                              'EXT' if ide else 'STD',
                              ' RTR' if rtr else '', filt),
                           'RXSTAT=0x%02X' % raw]])
            else:
                self.put(ss, es, self.out_ann,
                         [ANN_INSTR, ['RX STATUS', 'RXSTAT']])
            return

        if (first & 0xF0) == INSTR_RTS_BASE:
            mask = first & 0x07
            rts_list = ['TXB%d' % i for i in range(3) if mask & (1 << i)]
            label = 'RTS ' + '+'.join(rts_list) if rts_list else 'RTS (none)'
            self.put(ss, es, self.out_ann,
                     [ANN_RTS, [label, 'RTS=0x%02X' % first]])
            return

        if first == INSTR_READ:
            if len(self.mosi_buf) < 2:
                self._warn(ss, es, 'READ without address byte')
                return
            reg = self.mosi_buf[1]
            name = REGS.get(reg, 'Reg[0x%02X]' % reg)
            if len(resp) == 1 and reg == 0x0E:
                opmod = resp[0] & 0xE0
                opmod_name = OPMOD_NAMES.get(opmod, 'OPMOD=0x%02X' % opmod)
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: 0x%02X (%s)' % (name, resp[0], opmod_name),
                           '%s=0x%02X' % (name, resp[0])]])
                return
            if len(resp) == 1 and reg == 0x2C:
                flags = [name for mask, name in CANINTF_NAMES if resp[0] & mask]
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: 0x%02X %s' % (name, resp[0], ' '.join(flags)),
                           '%s=0x%02X' % (name, resp[0])]])
                return
            if len(resp) == 1 and reg == 0x2D:
                flags = [name for mask, name in EFLG_NAMES if resp[0] & mask]
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: 0x%02X %s' % (name, resp[0], ' '.join(flags)),
                           '%s=0x%02X' % (name, resp[0])]])
                return
            if len(resp) == 1:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: 0x%02X' % (name, resp[0]),
                           '%s=0x%02X' % (name, resp[0])]])
                return
            short = ' '.join('0x%02X' % b for b in resp[:8])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ,
                      ['Read %s[%d]: %s' % (name, len(resp), short),
                       '%s[%d]' % (name, len(resp))]])
            return

        if first == INSTR_WRITE:
            if len(self.mosi_buf) < 3:
                self._warn(ss, es, 'WRITE without data byte')
                return
            reg = self.mosi_buf[1]
            name = REGS.get(reg, 'Reg[0x%02X]' % reg)
            val = self.mosi_buf[2]
            if reg == 0x0F and len(self.mosi_buf) >= 3:
                reqop = val & 0xE0
                opmod_name = OPMOD_NAMES.get(reqop, 'OPMOD=0x%02X' % reqop)
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: 0x%02X (%s)' % (name, val, opmod_name),
                           '%s=0x%02X' % (name, val)]])
                return
            if reg == 0x2C and len(self.mosi_buf) >= 3:
                flags = [name for mask, name in CANINTF_NAMES if val & mask]
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: 0x%02X %s' % (name, val, ' '.join(flags)),
                           '%s=0x%02X' % (name, val)]])
                return
            if len(self.mosi_buf) == 3:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s: 0x%02X' % (name, val),
                           '%s=0x%02X' % (name, val)]])
                return
            data = ' '.join('0x%02X' % b for b in self.mosi_buf[2:10])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['Write %s[%d]: %s' % (name, len(self.mosi_buf) - 2, data),
                       '%s[%d]' % (name, len(self.mosi_buf) - 2)]])
            return

        if first == INSTR_BIT_MODIFY:
            if len(self.mosi_buf) < 4:
                self._warn(ss, es, 'BIT MODIFY missing fields')
                return
            reg = self.mosi_buf[1]
            mask = self.mosi_buf[2]
            val = self.mosi_buf[3]
            name = REGS.get(reg, 'Reg[0x%02X]' % reg)
            self.put(ss, es, self.out_ann,
                     [ANN_BIT_MOD,
                      ['BIT MODIFY %s: mask=0x%02X data=0x%02X' % (name, mask, val),
                       '%s m=0x%02X v=0x%02X' % (name, mask, val)]])
            return

        if (first & 0xF0) == INSTR_LOAD_TX_BASE:
            offset = first & 0x0F
            label = TX_BUF_OFFSETS.get(offset, 'TX?+0x%X' % offset)
            buf_index = offset // 4 if offset in (0, 4, 8) else 0
            if len(self.mosi_buf) >= 6:
                sidh, sidl, eid8, eid0, dlc = (self.mosi_buf[1], self.mosi_buf[2],
                                                self.mosi_buf[3], self.mosi_buf[4],
                                                self.mosi_buf[5])
                kind, rtr, can_id = _decode_can_id(sidh, sidl, eid8, eid0)
                data_bytes = self.mosi_buf[6:6 + (dlc & 0x0F)]
                rtr_str = ' RTR' if rtr else ''
                data_str = ' '.join('0x%02X' % b for b in data_bytes[:8])
                self.put(ss, es, self.out_ann,
                         [ANN_TX_FRAME,
                          ['TXB%d: %s id=%s%s dlc=%d data=%s' % (
                              buf_index, kind, _format_id_hex(can_id, kind == 'EXT'),
                              rtr_str, dlc & 0x0F, data_str),
                           'TXB%d %s=%s dlc=%d' % (
                               buf_index, kind, _format_id_hex(can_id, kind == 'EXT'),
                               dlc & 0x0F)]])
            else:
                self.put(ss, es, self.out_ann,
                         [ANN_INSTR, ['LOAD TX BUFFER (incomplete)', 'LTXB']])
            return

        if (first & 0xF0) == INSTR_READ_RX_BASE:
            offset = first & 0x0F
            buf_index = 0 if offset < 4 else 1
            if len(resp) >= 5:
                sidh, sidl, eid8, eid0, dlc = resp[0], resp[1], resp[2], resp[3], resp[4]
                kind, rtr, can_id = _decode_can_id(sidh, sidl, eid8, eid0)
                data_bytes = resp[5:5 + (dlc & 0x0F)]
                rtr_str = ' RTR' if rtr else ''
                data_str = ' '.join('0x%02X' % b for b in data_bytes[:8])
                self.put(ss, es, self.out_ann,
                         [ANN_RX_FRAME,
                          ['RXB%d: %s id=%s%s dlc=%d data=%s' % (
                              buf_index, kind, _format_id_hex(can_id, kind == 'EXT'),
                              rtr_str, dlc & 0x0F, data_str),
                           'RXB%d %s=%s dlc=%d' % (
                               buf_index, kind, _format_id_hex(can_id, kind == 'EXT'),
                               dlc & 0x0F)]])
            else:
                self.put(ss, es, self.out_ann,
                         [ANN_INSTR, ['READ RX BUFFER (incomplete)', 'LRXB']])
            return

        # Unknown instruction byte
        self.put(ss, es, self.out_ann,
                 [ANN_INSTR, ['Unknown instruction 0x%02X' % first,
                              '0x%02X' % first]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        if ptype == 'CS_ASSERT':
            self._finish_transaction()
            self.mosi_buf  = []
            self.miso_buf  = []
            self.ss_block  = ss
            self.cs_active = True
        elif ptype == 'CS_DEASSERT':
            self._finish_transaction()
            self.cs_active = False
            self.ss        = es
        elif ptype == 'DATA':
            mosi, miso = pdata
            self.mosi_buf.append(mosi)
            self.miso_buf.append(miso)
            self.ss = es
