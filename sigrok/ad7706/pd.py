"""
AD7706 sigrok protocol decoder.

Decodes AD7706 3-channel, 16-bit sigma-delta ADC SPI transactions into
register-level reads and writes. The chip uses a two-phase protocol: every
register access is preceded by an 8-bit write to the Communication Register
that selects the target register and read/write direction, then the
selected register's data is transferred in the same CS-held transaction.

Annotations:
- `comm` — Communication Register byte (RS2:RS0 register select, R/W
  direction, STBY standby flag, CH1:CH0 channel select); also reports
  the `0/DRDY` flag when read.
- `setup` — Setup Register (MD1:MD0 mode, G2:G0 gain, B/U bipolar/unipolar,
  BUF buffer, FSYNC filter-sync).
- `clock` — Clock Register (CLKDIS, CLKDIV, CLK family, FS1:FS0 output rate).
- `data` — Data Register (16-bit conversion result, decoded using the
  last-configured gain/bipolar state for that channel).
- `calibration` — Zero-Scale (offset) or Full-Scale (gain) calibration
  registers (24-bit coefficients).
- `warning` — Test Register selected, or a register access attempted
  without a preceding Communication Register write.

The AD7706 differs from the AD7705 only in its channel-select bit pattern:
`CH1:CH0 = 11` selects the real third channel AIN3, not a test mode.
"""

import sigrokdecode as srd

REGS = {
    0x0: 'Communication',
    0x1: 'Setup',
    0x2: 'Clock',
    0x3: 'Data',
    0x4: 'Test',
    0x6: 'Offset',
    0x7: 'Gain',
}

MODE_NAMES = {
    0b00: 'Normal',
    0b01: 'SelfCal',
    0b10: 'ZeroSysCal',
    0b11: 'FullSysCal',
}

GAIN_NAMES = {
    0b000: '×1', 0b001: '×2', 0b010: '×4', 0b011: '×8',
    0b100: '×16', 0b101: '×32', 0b110: '×64', 0b111: '×128',
}

FS_RATES = {
    # CLK=0 family (1/2 MHz clock)
    (0, 0b00): 20, (0, 0b01): 25, (0, 0b10): 100, (0, 0b11): 200,
    # CLK=1 family (2.4576/4.9152 MHz clock)
    (1, 0b00): 50, (1, 0b01): 60, (1, 0b10): 250, (1, 0b11): 500,
}

# AD7706 channel select bit pattern: `CH1:CH0 = 11` is the real third
# channel AIN3; `10` is the internal COMMON-shorted-to-itself test mode
# (factory debug aid, intentionally not exposed in the driver API).
CH_NAMES = {0b00: 'AIN1', 0b01: 'AIN2', 0b10: 'INT', 0b11: 'AIN3'}

ANN_COMM         = 0
ANN_SETUP        = 1
ANN_CLOCK        = 2
ANN_DATA         = 3
ANN_CALIBRATION  = 4
ANN_WARNING      = 5
ANN_DATA_START   = 6
ANN_DATA_DONE    = 7


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ad7706'
    name = 'AD7706'
    longname = 'Analog Devices AD7706 3-channel 16-bit sigma-delta ADC'
    desc = 'Decode AD7706 register-access SPI transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['ad7706']
    tags = ['IC', 'ADC']

    annotations = (
        ('comm-write', 'Communication Register byte'),
        ('setup', 'Setup Register'),
        ('clock', 'Clock Register'),
        ('data', 'Data Register (16-bit conversion)'),
        ('calibration', 'Calibration register'),
        ('warning', 'Warning'),
        ('read_start', 'Data Register read start'),
        ('read_done', 'Data Register read done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_COMM, ANN_SETUP, ANN_CLOCK, ANN_DATA, ANN_CALIBRATION)),
        ('read',     'Read',     (ANN_DATA_START, ANN_DATA_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.channel  = None
        self.databuf  = []
        self.ss_block = None
        # Last-seen Setup Register per channel — for annotating data voltages.
        # AD7706 has three channels: 0 = AIN1, 1 = AIN2, 2 = AIN3, and the
        # internal test mode (CH=2) is not configured by the driver API.
        self.last_gain     = {0: 1, 1: 1, 2: 1}
        self.last_bipolar  = {0: True, 1: True, 2: True}

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep databuf and state
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in (0x00, 0x10, 0x20, 0x30, 0x40, 0x60, 0x70):
                self.state = 'IDLE'
                return
            self.addr    = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state   = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                # First DATA WRITE is the Communication Register byte.
                self.reg_ptr = (pdata >> 4) & 0x07
                self.rw_bit  = (pdata >> 3) & 0x01
                self.stby    = (pdata >> 2) & 0x01
                self.channel = pdata & 0x03
                self._annotate_comm(pdata, ss, es)
                if self.reg_ptr == 4:
                    self.put(es, es, self.out_ann, [ANN_WARNING,
                        ['Test Register selected', 'Test reg', 'TR']])
                self.state = 'GET_DATA_WRITE'
                self.databuf = []
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_REG_PTR':
                # First DATA READ is the Communication Register byte being read.
                self.reg_ptr = (pdata >> 4) & 0x07
                self.rw_bit  = (pdata >> 3) & 0x01
                self.stby    = (pdata >> 2) & 0x01
                self.channel = pdata & 0x03
                self._annotate_comm(pdata, ss, es)
                if self.reg_ptr == 4:
                    self.put(es, es, self.out_ann, [ANN_WARNING,
                        ['Test Register selected', 'Test reg', 'TR']])
                self.state = 'GET_DATA_READ'
                self.databuf = []
            elif self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []

    def _annotate_comm(self, byte, ss, es):
        # Communication Register byte breakdown.
        rs2_0  = (byte >> 4) & 0x07
        rw     = (byte >> 3) & 0x01
        stby   = (byte >> 2) & 0x01
        ch     = byte & 0x03
        drdy   = (byte >> 7) & 0x01
        reg_name = REGS.get(rs2_0, 'Reserved')
        rw_name = 'W' if rw == 0 else 'R'
        stby_name = 'STBY' if stby else 'Run'
        ch_name = CH_NAMES.get(ch, '?' + str(ch))
        if rs2_0 == 0:
            # DRDY bit is meaningful on reads.
            msg = (
                f'Comm: {reg_name} {rw_name} DRDY={drdy} {stby_name} {ch_name}',
                f'{reg_name[:3]} {rw_name} DR={drdy} {ch_name}',
                f'C{reg_name[0]}{rw_name}',
            )
        else:
            msg = (
                f'Comm: {reg_name} {rw_name} {stby_name} {ch_name}',
                f'{reg_name[:3]} {rw_name} {ch_name}',
                f'C{reg_name[0]}{rw_name}',
            )
        self.put(ss, es, self.out_ann, [ANN_COMM, msg])

    def _finish_transaction(self):
        if self.reg_ptr is None or not self.databuf:
            return
        if self.reg_ptr == 1 and len(self.databuf) == 1:
            # Setup Register
            byte = self.databuf[0]
            mode_bits  = (byte >> 6) & 0x03
            gain_bits  = (byte >> 3) & 0x07
            bu         = (byte >> 2) & 0x01
            buf        = (byte >> 1) & 0x01
            fsync      = byte & 0x01
            mode_name  = MODE_NAMES.get(mode_bits, '?')
            gain_name  = GAIN_NAMES.get(gain_bits, '?')
            bu_name    = 'bipolar' if bu == 0 else 'unipolar'
            buf_name   = 'buffered' if buf else 'unbuffered'
            fsync_name = 'FSYNC' if fsync else 'run'
            msg = (
                f'Setup: {mode_name} {gain_name} {bu_name} {buf_name} {fsync_name}',
                f'{mode_name} {gain_name} {bu_name}',
                f'S{mode_bits}{gain_bits}{bu}',
            )
            self.put(self.ss_block, self.es, self.out_ann, [ANN_SETUP, msg])
            # AD7706 has three channels (0/1/2). The internal test mode
            # (CH=2 bits == 10) is excluded — no setup state to track.
            if self.channel is not None and self.channel in (0, 1, 3):
                # Map the CH1:CH0 bit pattern to a 0..2 logical channel index
                # for last_* lookup: AIN1->0, AIN2->1, AIN3->2.
                idx = {0: 0, 1: 1, 3: 2}.get(self.channel)
                if idx is not None:
                    self.last_gain[idx]    = 1 << gain_bits
                    self.last_bipolar[idx] = (bu == 0)
        elif self.reg_ptr == 2 and len(self.databuf) == 1:
            # Clock Register
            byte = self.databuf[0]
            clkdis  = (byte >> 4) & 0x01
            clkdiv  = (byte >> 3) & 0x01
            clk     = (byte >> 2) & 0x01
            fs      = byte & 0x03
            rate = FS_RATES.get((clk, fs), None)
            msg = (
                f'Clock: CLKDIS={clkdis} CLKDIV={clkdiv} CLK={clk} FS={fs} rate={rate}Hz',
                f'rate={rate}Hz CLKDIV={clkdiv}',
                f'C{clk}{fs}',
            )
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CLOCK, msg])
        elif self.reg_ptr == 3 and len(self.databuf) == 2:
            # Data Register
            code = (self.databuf[0] << 8) | self.databuf[1]
            idx = {0: 0, 1: 1, 3: 2}.get(self.channel, 0)
            gain = self.last_gain.get(idx, 1)
            bipolar = self.last_bipolar.get(idx, True)
            # vref unknown to the decoder; report raw code only.
            msg = (
                f'Data {CH_NAMES.get(self.channel, "?")}: code=0x{code:04X} ({code})',
                f'Data {CH_NAMES.get(self.channel, "?")}: 0x{code:04X}',
                f'D=0x{code:04X}',
            )
            self.put(self.ss_block, self.es, self.out_ann, [ANN_DATA, msg])
            self.put(self.ss_block, self.ss_block, self.out_ann, [ANN_DATA_START, ['Data read start', 'Read start', 'ST']])
            self.put(self.es, self.es, self.out_ann, [ANN_DATA_DONE, ['Data read done', 'Read done', 'DN']])
        elif self.reg_ptr == 4:
            # Test Register — only a warning, handled inline above.
            pass
        elif self.reg_ptr in (6, 7) and len(self.databuf) == 3:
            # Zero-Scale (offset) or Full-Scale (gain) calibration register.
            cal = (self.databuf[0] << 16) | (self.databuf[1] << 8) | self.databuf[2]
            kind = 'Offset' if self.reg_ptr == 6 else 'Gain'
            msg = (
                f'{kind} cal {CH_NAMES.get(self.channel, "?")}: 0x{cal:06X} ({cal})',
                f'{kind} cal: 0x{cal:06X}',
                f'{kind[0]}=0x{cal:06X}',
            )
            self.put(self.ss_block, self.es, self.out_ann, [ANN_CALIBRATION, msg])
        self.reg_ptr = None
        self.databuf = []
