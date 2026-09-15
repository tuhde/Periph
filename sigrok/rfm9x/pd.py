"""
RFM9x sigrok protocol decoder — implementation.

Decodes HopeRF RFM9x (RFM95/96/97/98W) SPI transactions into register-level
reads and writes. Special handling for FIFO (0x00) bursts, RegOpMode (0x01)
mode changes, RegPaConfig (0x09) TX-power programming, RegModemConfig1
(0x1D) / RegModemConfig2 (0x1E) / RegModemConfig3 (0x26) modulation parameter
writes, RegIrqFlags (0x12) IRQ flag decoding, and RegFrfMsb/Mid/Lsb (0x06/7/8)
frequency programming.
"""

import sigrokdecode as srd

# The SPI address byte layout: bit 7 = WNR (1 = write, 0 = read),
# bits 6:0 = register address (0x00–0x7F).
ADDRS_HINT = 'CS selects the chip; RFM9x has no per-instance I²C address.'

REGS = {
    0x00: 'RegFifo',
    0x01: 'RegOpMode',
    0x06: 'RegFrfMsb',
    0x07: 'RegFrfMid',
    0x08: 'RegFrfLsb',
    0x09: 'RegPaConfig',
    0x0B: 'RegOcp',
    0x0C: 'RegLna',
    0x0D: 'RegFifoAddrPtr',
    0x0E: 'RegFifoTxBaseAddr',
    0x0F: 'RegFifoRxBaseAddr',
    0x10: 'RegFifoRxCurrentAddr',
    0x11: 'RegIrqFlagsMask',
    0x12: 'RegIrqFlags',
    0x13: 'RegRxNbBytes',
    0x18: 'RegModemStat',
    0x19: 'RegPktSnrValue',
    0x1A: 'RegPktRssiValue',
    0x1B: 'RegRssiValue',
    0x1C: 'RegHopChannel',
    0x1D: 'RegModemConfig1',
    0x1E: 'RegModemConfig2',
    0x1F: 'RegSymbTimeoutLsb',
    0x20: 'RegPreambleMsb',
    0x21: 'RegPreambleLsb',
    0x22: 'RegPayloadLength',
    0x23: 'RegMaxPayloadLength',
    0x26: 'RegModemConfig3',
    0x31: 'RegDetectionOptimize',
    0x37: 'RegDetectionThreshold',
    0x40: 'RegDioMapping1',
    0x42: 'RegVersion',
    0x4D: 'RegPaDac',
}

OP_MODE_NAMES = {
    0b000: 'SLEEP',
    0b001: 'STDBY',
    0b010: 'FSTX',
    0b011: 'TX',
    0b100: 'FSRX',
    0b101: 'RXCONT',
    0b110: 'RXSINGLE',
    0b111: 'CAD',
}

DIO0_MAP = {0x00: 'RxDone', 0x40: 'TxDone', 0x80: 'CadDone'}

IRQ_NAMES = [
    (0x80, 'RxTimeout'),
    (0x40, 'RxDone'),
    (0x20, 'PayloadCrcError'),
    (0x10, 'ValidHeader'),
    (0x08, 'TxDone'),
    (0x04, 'CadDone'),
    (0x02, 'FhssChangeChannel'),
    (0x01, 'CadDetected'),
]

BW_TABLE = {
    0: '7.8 kHz', 1: '10.4 kHz', 2: '15.6 kHz', 3: '20.8 kHz',
    4: '31.25 kHz', 5: '41.7 kHz', 6: '62.5 kHz', 7: '125 kHz',
    8: '250 kHz', 9: '500 kHz',
}

ANN_REG_WRITE   = 0
ANN_REG_READ    = 1
ANN_OP_MODE     = 2
ANN_PA_CONFIG   = 3
ANN_MODEM       = 4
ANN_IRQ_FLAGS   = 5
ANN_FREQUENCY   = 6
ANN_WARNING     = 7


def _reg_name(addr_byte):
    is_write = (addr_byte & 0x80) != 0
    reg = addr_byte & 0x7F
    return is_write, reg, REGS.get(reg, 'Reg[0x%02X]' % reg)


def _decode_op_mode(raw):
    long_range  = (raw >> 7) & 1
    lf_band     = (raw >> 3) & 1
    mode        = raw & 0x07
    parts = [
        'RegOpMode 0x%02X' % raw,
        ('LoRa' if long_range else 'FSK/OOK'),
        ('LF band' if lf_band else 'HF band'),
        OP_MODE_NAMES.get(mode, 'mode=%d' % mode),
    ]
    return ', '.join(parts)


def _decode_pa_config(raw):
    pa_select = (raw >> 7) & 1
    max_power = (raw >> 4) & 0x07
    out_power = raw & 0x0F
    pin = 'PA_BOOST' if pa_select else 'RFO'
    if pa_select:
        pmax = 17.0
    else:
        pmax = 10.8 + 0.6 * max_power
    pout = (pmax - 15.0) + out_power if not pa_select else (2.0 + out_power)
    return 'RegPaConfig 0x%02X: pin=%s OutputPower=%d → %.1f dBm' % (
        raw, pin, out_power, pout)


def _decode_modem_config1(raw):
    bw = (raw >> 4) & 0x0F
    cr = (raw >> 1) & 0x07
    ih = raw & 0x01
    parts = [
        'RegModemConfig1 0x%02X' % raw,
        'BW=' + BW_TABLE.get(bw, '0x%X' % bw),
        'CR=4/%d' % (4 + cr),
        ('implicit' if ih else 'explicit') + ' header',
    ]
    return ', '.join(parts)


def _decode_modem_config2(raw):
    sf = (raw >> 4) & 0x0F
    tx_cont = (raw >> 3) & 1
    crc_on  = (raw >> 2) & 1
    parts = [
        'RegModemConfig2 0x%02X' % raw,
        'SF=%d' % sf,
        ('TxContinuous' if tx_cont else 'no TxContinuous'),
        ('CRC on' if crc_on else 'CRC off'),
    ]
    return ', '.join(parts)


def _decode_modem_config3(raw):
    mobile = (raw >> 3) & 1
    agc    = (raw >> 2) & 1
    parts = [
        'RegModemConfig3 0x%02X' % raw,
        ('mobile node' if mobile else 'static node'),
        ('AGC auto on' if agc else 'AGC manual'),
    ]
    return ', '.join(parts)


def _decode_irq_flags(raw):
    names = [name for mask, name in IRQ_NAMES if raw & mask]
    return 'RegIrqFlags 0x%02X: %s' % (raw, ', '.join(names) if names else 'none')


def _decode_frf(mid, lsb):
    # The 24-bit value here is the *new* Frf that the driver is writing,
    # not the carrier frequency — converting back to Hz requires FXOSC=32 MHz
    # and the rest of the bytes (we'd see only two of three in this decoder
    # unless MSB is the same frame's first byte, which is not how the
    # driver writes them).
    return 'FrfMid=0x%02X FrfLsb=0x%02X' % (mid, lsb)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'rfm9x'
    name = 'RFM9x'
    longname = 'HopeRF RFM95/96/97/98W LoRa transceiver'
    desc = 'Decode RFM9x SPI register transactions.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['rfm9x']
    tags = ['IC', 'Comms', 'LoRa']

    annotations = (
        ('reg-write',    'Register write'),
        ('reg-read',     'Register read'),
        ('op-mode',      'Operating mode change'),
        ('pa-config',    'TX power configuration'),
        ('modem-config', 'Modem configuration'),
        ('irq-flags',    'IRQ flags'),
        ('frequency',    'Carrier frequency change'),
        ('warning',      'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_OP_MODE,
                                   ANN_PA_CONFIG, ANN_MODEM, ANN_IRQ_FLAGS,
                                   ANN_FREQUENCY)),
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
        ss, es = self.ss_block, self.es
        first = self.mosi_buf[0]
        is_write, reg, name = _reg_name(first)

        if not is_write:
            # Read: addr byte + N dummy data; the miso_buf holds the response
            resp = self.miso_buf[1:] if len(self.miso_buf) > 1 else []
            if reg == 0x42 and len(resp) >= 1:
                ver = resp[0]
                ok = ' (SX1276 ✓)' if ver == 0x12 else ' (expected 0x12!)'
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read %s: 0x%02X%s' % (name, ver, ok),
                           '%s=0x%02X' % (name, ver)]])
                return
            if reg == 0x00 and len(resp) >= 1:
                # FIFO read — annotate as payload
                short = ' '.join('0x%02X' % b for b in resp[:16])
                self.put(ss, es, self.out_ann,
                         [ANN_REG_READ,
                          ['FIFO read (%d B): %s' % (len(resp), short),
                           'FIFO[%d]' % len(resp)]])
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
                      ['Read %s: %s' % (name, short),
                       '%s[%d]' % (name, len(resp))]])
            return

        # Write: addr byte + 1 or more data bytes
        if len(self.mosi_buf) < 2:
            self._warn(ss, es, 'Write to %s without data byte' % name)
            return

        val = self.mosi_buf[1]

        if reg == 0x01:
            self.put(ss, es, self.out_ann,
                     [ANN_OP_MODE, [_decode_op_mode(val),
                                     '%s' % OP_MODE_NAMES.get(val & 0x07, 'mode')]])
            return
        if reg == 0x09:
            self.put(ss, es, self.out_ann,
                     [ANN_PA_CONFIG, [_decode_pa_config(val),
                                       'PA=0x%02X' % val]])
            return
        if reg == 0x1D:
            self.put(ss, es, self.out_ann,
                     [ANN_MODEM, [_decode_modem_config1(val),
                                  'ModemCfg1=0x%02X' % val]])
            return
        if reg == 0x1E:
            self.put(ss, es, self.out_ann,
                     [ANN_MODEM, [_decode_modem_config2(val),
                                  'ModemCfg2=0x%02X' % val]])
            return
        if reg == 0x26:
            self.put(ss, es, self.out_ann,
                     [ANN_MODEM, [_decode_modem_config3(val),
                                  'ModemCfg3=0x%02X' % val]])
            return
        if reg == 0x12:
            self.put(ss, es, self.out_ann,
                     [ANN_IRQ_FLAGS, [_decode_irq_flags(val),
                                       'IRQ=0x%02X' % val]])
            return
        if reg in (0x07, 0x08):
            self.put(ss, es, self.out_ann,
                     [ANN_FREQUENCY, [_decode_frf(self.mosi_buf[1] if reg == 0x07 else 0,
                                                   val if reg == 0x08 else 0),
                                      '%s=0x%02X' % (name, val)]])
            return

        if len(self.mosi_buf) == 2:
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['Write %s: 0x%02X' % (name, val),
                       '%s=0x%02X' % (name, val)]])
            return

        # Multi-byte write — usually FIFO (0x00) or FIFO base addresses.
        if reg == 0x00:
            payload = self.mosi_buf[1:]
            short = ' '.join('0x%02X' % b for b in payload[:16])
            self.put(ss, es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['FIFO write (%d B): %s' % (len(payload), short),
                       'FIFO[%d]' % len(payload)]])
            return

        data = ' '.join('0x%02X' % b for b in self.mosi_buf[1:9])
        self.put(ss, es, self.out_ann,
                 [ANN_REG_WRITE,
                  ['Write %s: %s' % (name, data),
                   '%s[%d]' % (name, len(self.mosi_buf) - 1)]])

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
