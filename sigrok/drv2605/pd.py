import sigrokdecode as srd

ADDR = 0x5A
ADDR_BROADCAST = 0x58

REG_STATUS = 0x00
REG_MODE = 0x01
REG_RTP_INPUT = 0x02
REG_LIBRARY = 0x03
REG_SEQ1 = 0x04
REG_SEQ8 = 0x0B
REG_GO = 0x0C
REG_ODT = 0x0D
REG_BRT = 0x10
REG_ATH_CTRL = 0x11
REG_ATH_MIN_INPUT = 0x12
REG_ATH_MAX_INPUT = 0x13
REG_ATH_MIN_DRIVE = 0x14
REG_ATH_MAX_DRIVE = 0x15
REG_RATED_VOLTAGE = 0x16
REG_OD_CLAMP = 0x17
REG_A_CAL_COMP = 0x18
REG_A_CAL_BEMF = 0x19
REG_FEEDBACK = 0x1A
REG_CONTROL1 = 0x1B
REG_CONTROL2 = 0x1C
REG_CONTROL3 = 0x1D
REG_CONTROL4 = 0x1E
REG_VBAT = 0x21
REG_LRA_PERIOD = 0x22

NAMES = {
    REG_STATUS: 'STATUS', REG_MODE: 'MODE', REG_RTP_INPUT: 'RTP_INPUT', REG_LIBRARY: 'LIBRARY_SELECTION',
    REG_GO: 'GO', 0x0D: 'ODT', 0x0E: 'SPT', 0x0F: 'SNT', 0x10: 'BRT', REG_ATH_CTRL: 'ATH_CTRL',
    REG_ATH_MIN_INPUT: 'ATH_MIN_INPUT', REG_ATH_MAX_INPUT: 'ATH_MAX_INPUT',
    REG_ATH_MIN_DRIVE: 'ATH_MIN_DRIVE', REG_ATH_MAX_DRIVE: 'ATH_MAX_DRIVE',
    REG_RATED_VOLTAGE: 'RATED_VOLTAGE', REG_OD_CLAMP: 'OD_CLAMP', REG_A_CAL_COMP: 'A_CAL_COMP',
    REG_A_CAL_BEMF: 'A_CAL_BEMF', REG_FEEDBACK: 'FEEDBACK_CONTROL', REG_CONTROL1: 'CONTROL1',
    REG_CONTROL2: 'CONTROL2', REG_CONTROL3: 'CONTROL3', REG_CONTROL4: 'CONTROL4',
    REG_VBAT: 'VBAT', REG_LRA_PERIOD: 'LRA_PERIOD',
}
for _i in range(8):
    NAMES[REG_SEQ1 + _i] = 'WAV_FRM_SEQ%d' % (_i + 1)

MODES = {0: 'internal trigger', 1: 'external edge', 2: 'external level', 3: 'PWM/analog',
         4: 'audio-to-vibe', 5: 'RTP', 6: 'diagnostics', 7: 'auto-calibration'}
LIBRARIES = {0: 'empty', 1: 'A', 2: 'B', 3: 'C', 4: 'D', 5: 'E', 6: 'LRA', 7: 'reserved'}
DEVICE_IDS = {3: 'DRV2605', 4: 'DRV2604', 6: 'DRV2604L', 7: 'DRV2605L'}
BRAKE_FACTORS = {0: '1x', 1: '2x', 2: '3x', 3: '4x', 4: '6x', 5: '8x', 6: '16x', 7: 'off'}
LOOP_GAINS = {0: 'low', 1: 'medium', 2: 'high', 3: 'very high'}
BEMF_ERM = {0: 0.33, 1: 1.0, 2: 1.8, 3: 4.0}
BEMF_LRA = {0: 5.0, 1: 10.0, 2: 20.0, 3: 30.0}
PEAK_MS = {0: 10, 1: 20, 2: 30, 3: 40}
FILTER_HZ = {0: 100, 1: 125, 2: 150, 3: 200}
SAMPLE_US = {0: 150, 1: 200, 2: 250, 3: 300}
NOISE_GATE = {0: 'off', 1: '2%', 2: '4%', 3: '8%'}
AUTO_CAL_MS = {0: '150-350 ms', 1: '250-450 ms', 2: '500-700 ms', 3: '1000-1200 ms'}

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
ANN_CAL_START = 3
ANN_CAL_DONE = 4


def _s8(b):
    return b - 256 if b > 127 else b


class Decoder(srd.Decoder):
    api_version = 3
    id = 'drv2605'
    name = 'DRV2605'
    longname = 'DRV2605 haptic driver for ERM and LRA actuators'
    desc = 'Decode DRV2605 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['drv2605']
    tags = ['IC']

    annotations = (
        ('data',      'Register'),
        ('status',    'STATUS register'),
        ('warning',   'Warning'),
        ('cal-start', 'Calibration start'),
        ('cal-done',  'Calibration done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_CAL_START, ANN_CAL_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'     # IDLE | GET_REG | WRITE | READ | IGNORE
        self.reg_ptr = None     # register pointer, kept across transactions
        self.nwrite = 0
        self.nread = 0
        self.addr = None
        self.last_mode = None
        self.lra = False
        self.bemf_gain = 2
        self.cal_pending = False
        self.is_read = False
        self.databuf = ()
        self._py_span = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    @staticmethod
    def _tiers(long_text, med_text):
        short = med_text.split()[0][:8] if med_text else long_text[:8]
        out = [long_text, med_text]
        if short and short not in out:
            out.append(short)
        else:
            out.append(long_text[:8])
        return out

    def _data(self, ss, es, reg, direction, byte, text, row=ANN_DATA):
        name = NAMES.get(reg, '?')
        long_text = '%s %s [0x%02X] %s' % (direction[0], name, byte, text)
        med = '%s %s 0x%02X' % (direction[0], name, byte)
        self.is_read = direction == 'Read'
        self.databuf = (byte,)
        self.reg_ptr_for_mirror = reg
        self.put(ss, es, self.out_ann, [row, [long_text, med, '%s %s' % (direction[0], name[:6])]])
        if self.out_python is not None:
            self.put(ss, es, self.out_python, ('REG_' + direction.upper(), (reg, bytes([byte]))))

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    # -- register decoding ---------------------------------------------------

    def _decode(self, reg, b):
        if REG_SEQ1 <= reg <= REG_SEQ8:
            if b & 0x80:
                return 'wait %d ms' % (10 * (b & 0x7F))
            return 'effect %d' % b if b & 0x7F else 'end of sequence'
        if reg == REG_STATUS:
            did = b >> 5
            return ('DEVICE_ID=%d (%s) DIAG_RESULT=%d FB_STS=%d OVER_TEMP=%d OC_DETECT=%d'
                    % (did, DEVICE_IDS.get(did, '?'), (b >> 3) & 1, (b >> 2) & 1, (b >> 1) & 1, b & 1))
        if reg == REG_MODE:
            return 'DEV_RESET=%d STANDBY=%d MODE=%d (%s)' % (b >> 7, (b >> 6) & 1, b & 7, MODES[b & 7])
        if reg == REG_RTP_INPUT:
            return '%.1f %% of full scale (signed)' % (_s8(b) / 127.0 * 100.0)
        if reg == REG_LIBRARY:
            return 'HI_Z=%d LIBRARY_SEL=%d (%s)' % ((b >> 4) & 1, b & 7, LIBRARIES[b & 7])
        if reg == REG_GO:
            return 'GO=%d' % (b & 1)
        if REG_ODT <= reg <= REG_BRT:
            return '%+d ms' % (_s8(b) * 5)
        if reg == REG_ATH_CTRL:
            return 'ATH_PEAK_TIME=%d ms ATH_FILTER=%d Hz' % (PEAK_MS[(b >> 2) & 3], FILTER_HZ[b & 3])
        if reg in (REG_ATH_MIN_INPUT, REG_ATH_MAX_INPUT):
            return '%.2f V pk-pk' % (b * 1.8 / 255)
        if reg in (REG_ATH_MIN_DRIVE, REG_ATH_MAX_DRIVE):
            return '%.1f %%' % (b / 255.0 * 100)
        if reg == REG_RATED_VOLTAGE:
            return '~%.2f V (%s)' % (b * (20.71e-3 if self.lra else 21.33e-3), 'LRA rms' if self.lra else 'ERM')
        if reg == REG_OD_CLAMP:
            return '~%.2f V (%s)' % (b * 21.96e-3, 'LRA' if self.lra else 'ERM')
        if reg == REG_A_CAL_COMP:
            return 'coefficient %.3f' % (1 + b / 255.0)
        if reg == REG_A_CAL_BEMF:
            gain = (BEMF_LRA if self.lra else BEMF_ERM)[self.bemf_gain]
            return 'back-EMF ~%.3f V (BEMF_GAIN %.2fx)' % (b / 255.0 * 1.22 / gain, gain)
        if reg == REG_FEEDBACK:
            return ('N_ERM_LRA=%d (%s) FB_BRAKE_FACTOR=%s LOOP_GAIN=%s BEMF_GAIN=%d'
                    % (b >> 7, 'LRA' if b & 0x80 else 'ERM', BRAKE_FACTORS[(b >> 4) & 7],
                       LOOP_GAINS[(b >> 2) & 3], b & 3))
        if reg == REG_CONTROL1:
            return ('STARTUP_BOOST=%d AC_COUPLE=%d DRIVE_TIME=%d (%.1f ms)'
                    % (b >> 7, (b >> 5) & 1, b & 0x1F,
                       0.5 + 0.1 * (b & 0x1F) if self.lra else 1 + 0.2 * (b & 0x1F)))
        if reg == REG_CONTROL2:
            return ('BIDIR_INPUT=%d BRAKE_STABILIZER=%d SAMPLE_TIME=%d us BLANKING_TIME=%d IDISS_TIME=%d'
                    % (b >> 7, (b >> 6) & 1, SAMPLE_US[(b >> 4) & 3], (b >> 2) & 3, b & 3))
        if reg == REG_CONTROL3:
            return ('NG_THRESH=%s ERM_OPEN_LOOP=%d SUPPLY_COMP_DIS=%d DATA_FORMAT_RTP=%s LRA_DRIVE_MODE=%d '
                    'N_PWM_ANALOG=%s LRA_OPEN_LOOP=%d'
                    % (NOISE_GATE[b >> 6], (b >> 5) & 1, (b >> 4) & 1, 'unsigned' if b & 8 else 'signed',
                       (b >> 2) & 1, 'analog' if b & 2 else 'PWM', b & 1))
        if reg == REG_CONTROL4:
            return 'AUTO_CAL_TIME=%s OTP_STATUS=%d OTP_PROGRAM=%d' % (AUTO_CAL_MS[(b >> 4) & 3], (b >> 2) & 1, b & 1)
        if reg == REG_VBAT:
            return 'VDD = %.2f V' % (b * 5.6 / 255)
        if reg == REG_LRA_PERIOD:
            if b == 0:
                return 'no LRA period measured'
            us = b * 98.46
            return 'period %.0f us (%.1f Hz)' % (us, 1e6 / us)
        return ''

    def _track(self, reg, b, ss, es, is_write):
        """Update the decoder's model of the chip from a register value."""
        if reg == REG_FEEDBACK:
            self.lra = bool(b & 0x80)
            self.bemf_gain = b & 3
        if is_write and reg == REG_MODE:
            self.last_mode = b & 7
        if is_write and reg == REG_GO and (b & 1) and self.last_mode == 7:
            self.cal_pending = True
            self.put(ss, es, self.out_ann,
                     [ANN_CAL_START, ['cal_start: GO set in auto-calibration mode', 'cal_start', 'CAL▶']])
        if not is_write and reg == REG_GO and not (b & 1) and self.cal_pending:
            self.cal_pending = False
            self.put(ss, es, self.out_ann,
                     [ANN_CAL_DONE, ['cal_done: GO read back as 0', 'cal_done', 'CAL✓']])

    def _byte(self, ss, es, direction, reg, b):
        if reg not in NAMES:
            self._warn(ss, es, '%s of undocumented register 0x%02X' % (direction, reg), 'REG?')
            return
        text = self._decode(reg, b)
        row = ANN_STATUS if reg == REG_STATUS and direction == 'Read' else ANN_DATA
        self._data(ss, es, reg, direction, b, text, row)
        if reg == REG_STATUS and direction == 'Read' and (b >> 5) != 3:
            self._warn(ss, es, 'STATUS DEVICE_ID=%d, expected 3 (DRV2605)' % (b >> 5), 'ID?')
        if reg == REG_LIBRARY and (b & 7) == 7:
            self._warn(ss, es, 'LIBRARY_SEL 7 is reserved', 'LIB?')
        self._track(reg, b, ss, es, direction == 'Write')

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
        elif ptype == 'START REPEAT':
            self.state = 'IDLE'
        elif ptype == 'ADDRESS WRITE':
            if pdata in (ADDR, ADDR_BROADCAST):
                self.state = 'GET_REG'
                self.addr = pdata
                self.nwrite = 0
            else:
                self.state = 'IGNORE'
        elif ptype == 'ADDRESS READ':
            if pdata == ADDR:
                self.state = 'READ'
                self.addr = pdata
                self.nread = 0
            else:
                self.state = 'IGNORE'
        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG':
                self.reg_ptr = pdata
                self.state = 'WRITE'
                self.nwrite = 0
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, self._tiers('Pointer -> %s (0x%02X)' % (NAMES.get(pdata, '?'), pdata),
                                                'PTR 0x%02X' % pdata)])
            elif self.state == 'WRITE' and self.reg_ptr is not None:
                self._byte(ss, es, 'Write', self.reg_ptr + self.nwrite, pdata)
                self.nwrite += 1
        elif ptype == 'DATA READ':
            if self.state == 'READ' and self.reg_ptr is not None:
                self._byte(ss, es, 'Read', self.reg_ptr + self.nread, pdata)
                self.nread += 1
        elif ptype == 'STOP':
            self.state = 'IDLE'
