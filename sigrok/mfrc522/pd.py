"""
MFRC522 sigrok protocol decoder.

Decodes MFRC522 SPI transactions into register-level reads and writes,
with FIFO bursts (register 0x09) grouped and decoded as recognized
ISO/IEC 14443-3 or MIFARE command frames where the leading byte matches a
known command. ``CommandReg`` writes (register 0x01) are separately annotated
with the MFRC522 command name.

The decoder stacks on top of the ``spi`` protocol decoder. It expects data
in the form::

    ptype == 'CS_ASSERT' / 'CS_DEASSERT':     cs edge
    ptype == 'DATA':                         (mosi, miso)

Where ``mosi`` and ``miso`` are single bytes. The decoder reassembles
register-pointer and data bytes from MOSI, and annotates the corresponding
MISO read-back.
"""

import sigrokdecode as srd

ADDRS = set(range(0x28, 0x30))  # I2C default; SPI uses CS so no address filter needed

REGS = {
    0x01: 'CommandReg',
    0x02: 'ComIEnReg',
    0x03: 'DivIEnReg',
    0x04: 'ComIrqReg',
    0x05: 'DivIrqReg',
    0x06: 'ErrorReg',
    0x07: 'Status1Reg',
    0x08: 'Status2Reg',
    0x09: 'FIFODataReg',
    0x0A: 'FIFOLevelReg',
    0x0B: 'WaterLevelReg',
    0x0C: 'ControlReg',
    0x0D: 'BitFramingReg',
    0x0E: 'CollReg',
    0x11: 'ModeReg',
    0x12: 'TxModeReg',
    0x13: 'RxModeReg',
    0x14: 'TxControlReg',
    0x15: 'TxASKReg',
    0x16: 'TxSelReg',
    0x17: 'RxSelReg',
    0x18: 'RxThresholdReg',
    0x19: 'DemodReg',
    0x1C: 'MfTxReg',
    0x1D: 'MfRxReg',
    0x1F: 'SerialSpeedReg',
    0x21: 'CRCResultH',
    0x22: 'CRCResultL',
    0x24: 'ModWidthReg',
    0x26: 'RFCfgReg',
    0x27: 'GsNReg',
    0x28: 'CWGsPReg',
    0x29: 'ModGsPReg',
    0x2A: 'TModeReg',
    0x2B: 'TPrescalerReg',
    0x2C: 'TReloadRegH',
    0x2D: 'TReloadRegL',
    0x2E: 'TCounterValH',
    0x2F: 'TCounterValL',
    0x31: 'TestSel1Reg',
    0x32: 'TestSel2Reg',
    0x33: 'TestPinEnReg',
    0x34: 'TestPinValueReg',
    0x35: 'TestBusReg',
    0x36: 'AutoTestReg',
    0x37: 'VersionReg',
    0x38: 'AnalogTestReg',
    0x39: 'TestDAC1Reg',
    0x3A: 'TestDAC2Reg',
    0x3B: 'TestADCReg',
}

# MFRC522 CommandReg commands
CMD_NAMES = {
    0x00: 'Idle',
    0x01: 'Mem',
    0x02: 'GenerateRandomID',
    0x03: 'CalcCRC',
    0x04: 'Transmit',
    0x07: 'NoCmdChange',
    0x08: 'Receive',
    0x0C: 'Transceive',
    0x0E: 'MFAuthent',
    0x0F: 'SoftReset',
}

# ISO/IEC 14443-3 Type A
PICC_REQA = 0x26
PICC_WUPA = 0x52
PICC_HLTA = 0x50
PICC_ANTICOLL_CL1 = 0x93
PICC_ANTICOLL_CL2 = 0x95
PICC_ANTICOLL_CL3 = 0x97
PICC_SELECT_CL1 = 0x93
PICC_SELECT_CL2 = 0x95
PICC_SELECT_CL3 = 0x97

# MIFARE Classic
MF_AUTH_A = 0x60
MF_AUTH_B = 0x61
MF_READ = 0x30
MF_WRITE = 0xA0
MF_INCREMENT = 0xC1
MF_DECREMENT = 0xC0
MF_RESTORE = 0xC2
MF_TRANSFER = 0xB0

# MIFARE Ultralight / NTAG
UL_READ = 0x30
UL_WRITE = 0xA2

ANN_REG_WRITE   = 0
ANN_REG_READ    = 1
ANN_CMD_WRITE   = 2
ANN_FIFO_FRAME  = 3
ANN_WARNING     = 4


def _reg_name(addr_byte):
    reg = (addr_byte >> 1) & 0x3F
    read = (addr_byte & 0x80) != 0
    return reg, read, REGS.get(reg, 'reg[0x%02X]' % reg)


def _decode_command_reg(raw):
    cmd = raw & 0x0F
    name = CMD_NAMES.get(cmd, 'unknown(0x%X)' % cmd)
    extra = []
    if raw & 0x10:
        extra.append('PowerDown')
    if raw & 0x20:
        extra.append('RcvOff')
    detail = ', '.join(extra) if extra else 'no flags'
    return 'CommandReg 0x%02X: cmd=%s %s' % (raw, name, detail)


def _decode_fifo_frame(mosi):
    if not mosi:
        return None
    cmd = mosi[0]
    if cmd == PICC_REQA:
        return 'REQA 0x%02X' % cmd
    if cmd == PICC_WUPA:
        return 'WUPA 0x%02X' % cmd
    if cmd == PICC_HLTA:
        return 'HLTA 0x%02X 0x%02X' % (mosi[0], mosi[1] if len(mosi) > 1 else 0)
    if cmd in (PICC_ANTICOLL_CL1, PICC_ANTICOLL_CL2, PICC_ANTICOLL_CL3):
        level = (cmd - 0x93) // 2 + 1
        return 'Anticoll CL%d 0x%02X 0x%02X' % (level, mosi[0], mosi[1] if len(mosi) > 1 else 0)
    if cmd in (PICC_SELECT_CL1, PICC_SELECT_CL2, PICC_SELECT_CL3):
        level = (cmd - 0x93) // 2 + 1
        return 'Select CL%d 0x%02X 0x%02X' % (level, mosi[0], mosi[1] if len(mosi) > 1 else 0)
    if cmd in (MF_AUTH_A, MF_AUTH_B):
        key_type = 'A' if cmd == MF_AUTH_A else 'B'
        block = mosi[1] if len(mosi) > 1 else 0
        return 'MFAuthent key %s block=0x%02X' % (key_type, block)
    if cmd == MF_READ:
        block = mosi[1] if len(mosi) > 1 else 0
        return 'MIFARE Read block=0x%02X' % block
    if cmd == MF_WRITE:
        block = mosi[1] if len(mosi) > 1 else 0
        return 'MIFARE Write block=0x%02X' % block
    if cmd in (MF_INCREMENT, MF_DECREMENT, MF_RESTORE):
        op = {MF_INCREMENT: 'Increment', MF_DECREMENT: 'Decrement', MF_RESTORE: 'Restore'}[cmd]
        block = mosi[1] if len(mosi) > 1 else 0
        return 'MIFARE %s block=0x%02X' % (op, block)
    if cmd == MF_TRANSFER:
        block = mosi[1] if len(mosi) > 1 else 0
        return 'MIFARE Transfer block=0x%02X' % block
    if cmd == UL_WRITE:
        page = mosi[1] if len(mosi) > 1 else 0
        return 'Ultralight Write page=0x%02X' % page
    return None


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mfrc522'
    name = 'MFRC522'
    longname = 'NXP MFRC522 13.56 MHz RFID/NFC reader'
    desc = 'Decode MFRC522 SPI register transactions and ISO/IEC 14443-3 PICC commands.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['mfrc522']
    tags = ['IC', 'RFID', 'NFC']

    annotations = (
        ('reg-write',   'Register write'),
        ('reg-read',    'Register read'),
        ('cmd-write',   'CommandReg write'),
        ('fifo-frame',  'FIFO frame'),
        ('warning',     'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_CMD_WRITE, ANN_FIFO_FRAME)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.mosi_buf    = []
        self.miso_buf    = []
        self.ss_block    = None
        self.cs_active   = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if not self.cs_active or not self.mosi_buf:
            return
        ss, es = self.ss_block, self.ss
        if not self.mosi_buf:
            return
        first = self.mosi_buf[0]
        reg, read, name = _reg_name(first)
        if read:
            # Read: addr byte + 0 or more dummy data; the miso_buf holds the response
            resp = self.miso_buf[1:] if len(self.miso_buf) > 1 else []
            if reg == 0x37 and len(resp) >= 1:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ, ['Read %s: 0x%02X' % (name, resp[0]), '%s=0x%02X' % (name, resp[0])]])
                return
            if reg == 0x09 and len(resp) >= 1:
                # FIFO read — annotate generically
                short = ' '.join('0x%02X' % b for b in resp[:8])
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ, ['FIFO read: %s' % short, 'FIFO[%d]' % len(resp)]])
                return
            if len(resp) == 1:
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ, ['Read %s: 0x%02X' % (name, resp[0]), '%s=0x%02X' % (name, resp[0])]])
                return
            short = ' '.join('0x%02X' % b for b in resp[:8])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_READ, ['Read %s: %s' % (name, short), '%s[%d]' % (name, len(resp))]])
            return
        # Write: addr byte + 1 or more data bytes
        if reg == 0x01 and len(self.mosi_buf) >= 2:
            self.put(ss, es, self.out_ann,
                     [ANN_CMD_WRITE, [_decode_command_reg(self.mosi_buf[1]),
                                       'cmd=0x%02X' % (self.mosi_buf[1] & 0x0F)]])
            return
        if reg == 0x09 and len(self.mosi_buf) >= 2:
            # FIFO write — try to decode as a known frame
            decoded = _decode_fifo_frame(self.mosi_buf[1:])
            if decoded:
                self.put(ss, es, self.out_ann,
                         [ANN_FIFO_FRAME, [decoded, decoded[:24]]])
                return
            short = ' '.join('0x%02X' % b for b in self.mosi_buf[1:9])
            self.put(ss, es, self.out_ann,
                     [ANN_FIFO_FRAME, ['FIFO write: %s' % short, 'FIFO[%d]' % (len(self.mosi_buf) - 1)]])
            return
        if len(self.mosi_buf) == 2:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE, ['Write %s: 0x%02X' % (name, self.mosi_buf[1]),
                                      '%s=0x%02X' % (name, self.mosi_buf[1])]])
            return
        data = ' '.join('0x%02X' % b for b in self.mosi_buf[1:9])
        self.put(ss, es, self.out_ann,
                 [ANN_REG_WRITE, ['Write %s: %s' % (name, data), '%s[%d]' % (name, len(self.mosi_buf) - 1)]])

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
