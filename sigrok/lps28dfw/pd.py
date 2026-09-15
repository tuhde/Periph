import sigrokdecode as srd

ADDRS = {0x5C, 0x5D}

REGS = {
    0x0B: 'INTERRUPT_CFG',
    0x0C: 'THS_P_L',
    0x0D: 'THS_P_H',
    0x0E: 'IF_CTRL',
    0x0F: 'WHO_AM_I',
    0x10: 'CTRL_REG1',
    0x11: 'CTRL_REG2',
    0x12: 'CTRL_REG3',
    0x13: 'CTRL_REG4',
    0x14: 'FIFO_CTRL',
    0x15: 'FIFO_WTM',
    0x16: 'REF_P_L',
    0x17: 'REF_P_H',
    0x19: 'I3C_IF_CTRL',
    0x1A: 'RPDS_L',
    0x1B: 'RPDS_H',
    0x24: 'INT_SOURCE',
    0x25: 'FIFO_STATUS1',
    0x26: 'FIFO_STATUS2',
    0x27: 'STATUS',
    0x28: 'PRESSURE_OUT_XL',
    0x29: 'PRESSURE_OUT_L',
    0x2A: 'PRESSURE_OUT_H',
    0x2B: 'TEMP_OUT_L',
    0x2C: 'TEMP_OUT_H',
    0x78: 'FIFO_DATA_OUT_PRESS_XL',
    0x79: 'FIFO_DATA_OUT_PRESS_L',
    0x7A: 'FIFO_DATA_OUT_PRESS_H',
}

ODR_NAMES = {
    0x0: 'power-down', 0x1: '1 Hz', 0x2: '4 Hz', 0x3: '10 Hz',
    0x4: '25 Hz', 0x5: '50 Hz', 0x6: '75 Hz', 0x7: '100 Hz',
    0x8: '200 Hz', 0x9: '200 Hz', 0xA: '200 Hz', 0xB: '200 Hz',
    0xC: '200 Hz', 0xD: '200 Hz', 0xE: '200 Hz', 0xF: '200 Hz',
}
AVG_NAMES = {
    0x0: '4', 0x1: '8', 0x2: '16', 0x3: '32',
    0x4: '64', 0x5: '128', 0x6: '128', 0x7: '512',
}
FS_NAMES = {0: 'Mode 1 (0–1260 hPa)', 1: 'Mode 2 (0–4060 hPa)'}
LFPF_NAMES = {0: 'ODR/4', 1: 'ODR/9'}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_DATA_READ = 2
ANN_PTR_WRITE = 3
ANN_WARNING   = 4


class Decoder(srd.Decoder):
    api_version = 3
    id = 'lps28dfw'
    name = 'LPS28DFW'
    longname = 'STMicroelectronics LPS28DFW dual full-scale digital barometer'
    desc = 'Decode LPS28DFW I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lps28dfw']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('data-read', 'Data read'),
        ('ptr-write', 'Pointer write'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_DATA_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _finish_transaction(self):
        if self.ss_block is None or self.reg_ptr is None:
            return
        if self.reg_ptr not in REGS:
            self.put(self.ss_block, self.ss_block, ANN_WARNING,
                     ['Unknown register 0x%02X' % self.reg_ptr])
            self.ss_block = None
            self.reg_ptr  = None
            self.databuf  = []
            return
        name = REGS[self.reg_ptr]
        if self.is_read:
            payload = self._decode_value(self.reg_ptr, self.databuf)
            label = '%s read: %s' % (name, payload[0])
            short = '%s=%s' % (name, payload[1])
            self.put(self.ss_block, self.ss_block, ANN_REG_READ,
                     [label, short] + payload[2:])
        else:
            payload = self._decode_value(self.reg_ptr, self.databuf)
            label = '%s write: %s' % (name, payload[0])
            short = '%s=%s' % (name, payload[1])
            self.put(self.ss_block, self.ss_block, ANN_REG_WRITE,
                     [label, short] + payload[2:])
        self.ss_block = None
        self.reg_ptr  = None
        self.databuf  = []

    def _decode_value(self, reg, data):
        """Return (long, short, [more]) for the given register value bytes."""
        if reg in (0x0C, 0x15, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C,
                   0x16, 0x17, 0x1A, 0x1B, 0x78, 0x79, 0x7A):
            n = len(data)
            if reg in (0x0C, 0x24, 0x25, 0x26, 0x27, 0x2B, 0x2C, 0x16, 0x17, 0x1A, 0x1B):
                expected = 1 if reg in (0x0C, 0x24, 0x25, 0x26, 0x27, 0x2B, 0x2C) else 1
                if reg in (0x0C, 0x24, 0x25, 0x26, 0x27, 0x2B, 0x2C, 0x16, 0x17, 0x1A, 0x1B):
                    expected = 1
            if reg in (0x28, 0x29, 0x2A, 0x78, 0x79, 0x7A):
                expected = 3
            if reg in (0x2B, 0x2C, 0x16, 0x17, 0x1A, 0x1B):
                expected = 2
            if reg == 0x0C:
                expected = 1
            if reg == 0x24:
                expected = 1
            if reg == 0x25:
                expected = 1
            if reg == 0x26:
                expected = 1
            if reg == 0x27:
                expected = 1
            if reg == 0x15:
                expected = 1
            if n != expected:
                return ('%d byte(s) [expected %d]' % (n, expected), '%d B (≠%d)' % (n, expected))
            if reg == 0x0F:  # WHO_AM_I is handled below separately
                pass
            if reg == 0x0C:
                v = data[0]
                return ('0x%02X (THS_P low byte = %d LSB)' % (v, v), '0x%02X' % v)
            if reg == 0x0F:
                v = data[0]
                return ('0x%02X' % v, '0x%02X' % v)
            if reg in (0x16, 0x17, 0x1A, 0x1B):
                if reg in (0x1A, 0x1B):
                    raw = data[0] if reg == 0x1A else (data[0] << 8)
                    if reg == 0x1B:
                        raw = (self._last_l or 0) | (data[0] << 8)
                    return ('0x%04X' % raw, '0x%04X' % raw)
                if reg == 0x17:
                    raw = ((self._last_l or 0) | (data[0] << 8)) & 0xFFFF
                    return ('0x%04X' % raw, '0x%04X' % raw)
                v = data[0]
                return ('0x%02X' % v, '0x%02X' % v)
            if reg == 0x24:
                v = data[0]
                boot = (v & 0x80) >> 7
                ia   = (v & 0x04) >> 2
                pl   = (v & 0x02) >> 1
                ph   = (v & 0x01)
                return ('0x%02X (BOOT=%d IA=%d PL=%d PH=%d)' % (v, boot, ia, pl, ph), '0x%02X' % v)
            if reg == 0x25:
                v = data[0]
                return ('FSS=%d' % v, 'FSS=%d' % v)
            if reg == 0x26:
                v = data[0]
                wtm = (v & 0x80) >> 7
                ovr = (v & 0x40) >> 6
                full = (v & 0x20) >> 5
                return ('0x%02X (WTM=%d OVR=%d FULL=%d)' % (v, wtm, ovr, full), '0x%02X' % v)
            if reg == 0x27:
                v = data[0]
                tor = (v & 0x20) >> 5
                por = (v & 0x10) >> 4
                tda = (v & 0x02) >> 1
                pda = (v & 0x01)
                return ('0x%02X (T_OR=%d P_OR=%d T_DA=%d P_DA=%d)' % (v, tor, por, tda, pda), '0x%02X' % v)
            if reg in (0x28, 0x78):
                v = data[0]
                return ('XL=0x%02X' % v, 'XL=0x%02X' % v)
            if reg in (0x29, 0x79):
                v = data[0]
                return ('L=0x%02X' % v, 'L=0x%02X' % v)
            if reg in (0x2A, 0x7A):
                v = data[0]
                return ('H=0x%02X' % v, 'H=0x%02X' % v)
            if reg == 0x2B:
                v = data[0]
                return ('TL=0x%02X' % v, 'TL=0x%02X' % v)
            if reg == 0x2C:
                v = data[0]
                return ('TH=0x%02X' % v, 'TH=0x%02X' % v)
            if reg == 0x15:
                v = data[0]
                return ('WTM=%d' % v, 'WTM=%d' % v)
        if reg == 0x10:
            v = data[0]
            odr = (v >> 3) & 0x0F
            avg = v & 0x07
            return ('0x%02X (ODR=%s AVG=%s samples)' % (v, ODR_NAMES[odr], AVG_NAMES[avg]),
                    'ODR=%s AVG=%s' % (ODR_NAMES[odr], AVG_NAMES[avg]))
        if reg == 0x11:
            v = data[0]
            fs  = (v >> 6) & 0x01
            lfpcfg = (v >> 5) & 0x01
            lfpen  = (v >> 4) & 0x01
            bdu    = (v >> 3) & 0x01
            swrst  = (v >> 1) & 0x01
            onesht = v & 0x01
            return ('0x%02X (FS=%s LFPF_CFG=%s EN_LPFP=%d BDU=%d SWRESET=%d ONESHOT=%d)' %
                    (v, FS_NAMES[fs], LFPF_NAMES[lfpcfg], lfpen, bdu, swrst, onesht),
                    'FS=%s BDU=%d' % (FS_NAMES[fs], bdu))
        if reg == 0x12:
            v = data[0]
            hl  = (v >> 3) & 0x01
            pp  = (v >> 1) & 0x01
            inc = v & 0x01
            return ('0x%02X (INT_H_L=%d PP_OD=%d IF_ADD_INC=%d)' % (v, hl, pp, inc),
                    'INT_H_L=%d PP_OD=%d IF_ADD_INC=%d' % (hl, pp, inc))
        if reg == 0x13:
            v = data[0]
            return ('0x%02X' % v, '0x%02X' % v)
        if reg == 0x14:
            v = data[0]
            stop = (v >> 3) & 0x01
            trig = (v >> 2) & 0x01
            fmode = v & 0x03
            names = {0: 'Bypass', 1: 'FIFO', 2: 'Continuous', 3: 'Bypass-to-FIFO/Continuous-to-FIFO'}
            return ('0x%02X (F_MODE=%s TRIG=%d STOP_ON_WTM=%d)' % (v, names.get(fmode, '?'), trig, stop),
                    'F_MODE=%s' % names.get(fmode, '?'))
        if reg == 0x0B:
            v = data[0]
            aref  = (v >> 7) & 0x01
            rst_a = (v >> 6) & 0x01
            azero = (v >> 5) & 0x01
            rst_z = (v >> 4) & 0x01
            lir   = (v >> 2) & 0x01
            ple   = (v >> 1) & 0x01
            phe   = v & 0x01
            return ('0x%02X (AUTOREFP=%d RESET_ARP=%d AUTOZERO=%d RESET_AZ=%d LIR=%d PLE=%d PHE=%d)' %
                    (v, aref, rst_a, azero, rst_z, lir, ple, phe),
                    'AUTOREFP=%d AUTOZERO=%d PLE=%d PHE=%d' % (aref, azero, ple, phe))
        if reg == 0x0E:
            return ('0x%02X' % data[0], '0x%02X' % data[0])
        if reg == 0x19:
            return ('0x%02X' % data[0], '0x%02X' % data[0])
        if len(data) == 1:
            return ('0x%02X' % data[0], '0x%02X' % data[0])
        return (' '.join('0x%02X' % b for b in data), '%d B' % len(data))

    def _last_l(self):
        return getattr(self, '_last_rpds_l', None) if self.reg_ptr in (0x17, 0x1B) else None

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state   = 'GET_DATA_READ' if self.is_read else 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)