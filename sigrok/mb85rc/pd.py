import sigrokdecode as srd

# Type code 1010 + A2 A1 A0 -> 0x50-0x57; the Device ID uses the reserved
# slave address 0x7C (0xF8 write / 0xF9 read on the wire).
ADDRS = set(range(0x50, 0x58))
ID_ADDR = 0x7C
SIZE = 0x8000

ANN_MEM_WRITE = 0
ANN_MEM_READ  = 1
ANN_PTR_WRITE = 2
ANN_DEVICE_ID = 3
ANN_WARNING   = 4


def _hex(data):
    return ' '.join('0x%02X' % b for b in data)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'mb85rc'
    name = 'MB85RC'
    longname = 'MB85RC256V 256 Kbit I2C FRAM'
    desc = 'Decode MB85RC I2C FRAM byte/sequential writes, random/sequential/current-address reads and the Device ID sequence.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mb85rc']
    tags = ['IC', 'Memory']

    annotations = (
        ('mem-write', 'Memory write'),
        ('mem-read', 'Memory read'),
        ('ptr-write', 'Address pointer write'),
        ('device-id', 'Device ID'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_MEM_WRITE, ANN_MEM_READ, ANN_PTR_WRITE, ANN_DEVICE_ID)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'      # IDLE, WRITE, READ
        self.devid = False       # transaction addressed to 0x7C
        self.a210 = 0
        self.wbytes = []         # bytes written in the current write phase
        self.rbytes = []         # bytes read in the current read phase
        self.ptr = None          # address pointer set by this transaction
        self.cur = None          # next address after the previous access
        self.ss_txn = None       # start of the whole transaction
        self.ss_phase = None     # start of the current phase
        self.ss_first = None     # first data byte of the phase
        self.es_last = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _flush_write(self, es):
        """Finish a write phase that is not followed by a repeated-START read."""
        w = self.wbytes
        ss = self.ss_phase
        if self.devid or not w:
            return
        if len(w) < 2:
            self._warn(ss, es, 'Incomplete address (%d of 2 bytes)' % len(w), 'ADDR?')
            return
        addr = (w[0] << 8) | w[1]
        if w[0] & 0x80:
            self._warn(ss, es, 'Address 0x%04X has bit 15 set (must be 0)' % addr, 'ADDR?')
            addr &= 0x7FFF
        data = w[2:]
        if not data:
            self.ptr = addr
            self.put(ss, es, self.out_ann, [ANN_PTR_WRITE, [
                'Address pointer → 0x%04X' % addr, 'PTR 0x%04X' % addr, 'PTR']])
            self.put(ss, es, self.out_python, ('REG_WRITE', (addr, b'')))
            return
        n = len(data)
        kind = 'Byte write' if n == 1 else 'Sequential write'
        self.put(ss, es, self.out_ann, [ANN_MEM_WRITE, [
            '%s @ 0x%04X +%d: %s' % (kind, addr, n, _hex(data)),
            'W 0x%04X %d B' % (addr, n), 'W']])
        self.put(ss, es, self.out_python, ('REG_WRITE', (addr, bytes(data))))
        if addr + n > SIZE:
            self._warn(ss, es, 'Write wraps past 0x7FFF to 0x0000', 'WRAP')
        self.cur = (addr + n) & 0x7FFF

    def _flush_read(self, es):
        r = self.rbytes
        ss = self.ss_phase
        if not r:
            return
        n = len(r)
        if self.devid:
            m = (r[0] << 4) | (r[1] >> 4) if n > 1 else None
            if n >= 3:
                d = r[1] & 0x0F
                p = r[2]
                ok = (m, d, p) == (0x00A, 0x5, 0x10)
                self.put(ss, es, self.out_ann, [ANN_DEVICE_ID, [
                    'Device ID: manufacturer 0x%03X, density 0x%X, product 0x%02X%s' % (
                        m, d, p, ' (MB85RC256V OK)' if ok else ' (expected 0x00A510!)'),
                    'ID 0x%03X%X%02X' % (m, d, p), 'ID']])
                self.put(ss, es, self.out_python, ('DEVICE_ID', (m, d, p)))
                if not ok:
                    self._warn(ss, es, 'Device ID 0x%03X%X%02X is not 0x00A510' % (m, d, p), 'ID?')
            else:
                self._warn(ss, es, 'Device ID read too short (%d of 3 bytes)' % n, 'LEN?')
            return
        if self.ptr is not None:
            addr = self.ptr
            kind = 'Random read' if n == 1 else 'Sequential read'
        elif self.cur is not None:
            addr = self.cur
            kind = 'Current address read'
        else:
            addr = None
            kind = 'Current address read'
        if addr is None:
            self.put(ss, es, self.out_ann, [ANN_MEM_READ, [
                '%s (address unknown) +%d: %s' % (kind, n, _hex(r)), 'R %d B' % n, 'R']])
            self.put(ss, es, self.out_python, ('REG_READ', (None, bytes(r))))
            return
        self.put(ss, es, self.out_ann, [ANN_MEM_READ, [
            '%s @ 0x%04X +%d: %s' % (kind, addr, n, _hex(r)),
            'R 0x%04X %d B' % (addr, n), 'R']])
        self.put(ss, es, self.out_python, ('REG_READ', (addr, bytes(r))))
        if addr + n > SIZE:
            self._warn(ss, es, 'Read wraps past 0x7FFF to 0x0000', 'WRAP')
        self.cur = (addr + n) & 0x7FFF

    def _finish(self, es, restart=False):
        if self.state == 'WRITE':
            if restart and not self.devid and len(self.wbytes) == 2:
                # Address phase of a random read: keep the pointer for the read.
                w = self.wbytes
                addr = (w[0] << 8) | w[1]
                if w[0] & 0x80:
                    self._warn(self.ss_phase, es, 'Address 0x%04X has bit 15 set (must be 0)' % addr, 'ADDR?')
                self.ptr = addr & 0x7FFF
            elif not (restart and self.devid):
                self._flush_write(es)
        elif self.state == 'READ':
            self._flush_read(es)
        if not restart:
            self.ptr = None
        self.state = 'IDLE'

    def decode(self, ss, es, data):
        ptype, pdata = data
        if ptype == 'START':
            self._finish(ss)
            self.devid = False
            self.ptr = None
            self.wbytes = []
            self.rbytes = []
            self.ss_txn = ss
        elif ptype == 'START REPEAT':
            self._finish(ss, restart=True)
            self.rbytes = []
        elif ptype in ('ADDRESS WRITE', 'ADDRESS READ'):
            if pdata == ID_ADDR:
                self.devid = True
            elif pdata in ADDRS:
                self.devid = False
                self.a210 = pdata & 7
            else:
                self.state = 'IDLE'
                return
            self.ss_phase = ss
            if ptype == 'ADDRESS WRITE':
                self.state = 'WRITE'
                self.wbytes = []
            else:
                self.state = 'READ'
                self.rbytes = []
        elif ptype == 'DATA WRITE' and self.state == 'WRITE':
            self.wbytes.append(pdata)
        elif ptype == 'DATA READ' and self.state == 'READ':
            self.rbytes.append(pdata)
        elif ptype == 'STOP':
            self._finish(es)
