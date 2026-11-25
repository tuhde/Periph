"""
SD card (SPI mode) sigrok protocol decoder.

Decodes SD / SDHC / SDXC / microSD card traffic in SPI mode (SPI Mode 0). One
CS-framed transaction usually holds a command, its response and a data block,
but the decoder keeps state across transactions, so a command and its data may
also be split over several CS assertions. The decoder annotates:

- each 6-byte command frame: command name (CMDn / ACMDn after CMD55), the 32-bit
  argument and the CRC7 byte (checked), plus a one-line summary;
- the R1 response (idle / erase reset / illegal command / CRC error / erase
  sequence / address / parameter flags) and the extra bytes of R3 (OCR) and R7
  (voltage and check pattern), with the OCR decoded (CCS bit, 3.3 V window);
- start / stop tokens, the data response token after a write (accepted, CRC
  error, write error) and the data block itself with its CRC16 checked; CSD,
  CID and a 512-byte block get a one-line decoding (capacity, product name,
  boot signature);
- warnings for a command CRC7 / data CRC16 mismatch, an R1 error flag, a missing
  response, an error token and a short frame.

The decoder sits on top of the ``spi`` protocol decoder, which must be given
the CS, MOSI and MISO channels. Clocks sent with CS high (the 74+ init clocks)
are not seen by the ``spi`` decoder and therefore not annotated.
"""

import sigrokdecode as srd


CMD_NAMES = {
    0: 'GO_IDLE_STATE', 1: 'SEND_OP_COND', 8: 'SEND_IF_COND', 9: 'SEND_CSD', 10: 'SEND_CID',
    12: 'STOP_TRANSMISSION', 13: 'SEND_STATUS', 16: 'SET_BLOCKLEN', 17: 'READ_SINGLE_BLOCK',
    18: 'READ_MULTIPLE_BLOCK', 23: 'SET_BLOCK_COUNT', 24: 'WRITE_BLOCK', 25: 'WRITE_MULTIPLE_BLOCK',
    32: 'ERASE_WR_BLK_START', 33: 'ERASE_WR_BLK_END', 38: 'ERASE', 55: 'APP_CMD', 58: 'READ_OCR',
    59: 'CRC_ON_OFF',
}
ACMD_NAMES = {13: 'SD_STATUS', 22: 'SEND_NUM_WR_BLOCKS', 23: 'SET_WR_BLK_ERASE_COUNT', 41: 'SD_SEND_OP_COND',
              51: 'SEND_SCR'}

R1_FLAGS = (
    (0x01, 'idle'), (0x02, 'erase reset'), (0x04, 'illegal command'), (0x08, 'command CRC error'),
    (0x10, 'erase sequence error'), (0x20, 'address error'), (0x40, 'parameter error'),
)
R1_ERRORS = 0x7C  # everything but idle / erase reset

BLOCK_SIZE = 512
READ_CMDS = {9: 16, 10: 16, 17: BLOCK_SIZE, 18: BLOCK_SIZE, 51: 8}
WRITE_CMDS = (24, 25)

ANN_SUMMARY = 0
ANN_CMD     = 1
ANN_ARG     = 2
ANN_CRC     = 3
ANN_RESP    = 4
ANN_TOKEN   = 5
ANN_DATA    = 6
ANN_INFO    = 7
ANN_WARN    = 8

PREVIEW_BYTES = 8


def _hex(values, limit=PREVIEW_BYTES):
    shown = ' '.join('%02X' % v for v in values[:limit])
    return shown + (' …' if len(values) > limit else '')


def crc7(data):
    crc = 0
    for b in data:
        for _ in range(8):
            crc = (crc << 1) & 0xFF
            if (b ^ crc) & 0x80:
                crc ^= 0x09
            b = (b << 1) & 0xFF
    return crc & 0x7F


def crc16(data):
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def _r1_text(r1):
    flags = [name for bit, name in R1_FLAGS if r1 & bit]
    return ', '.join(flags) if flags else 'ready'


def _csd_text(csd):
    if csd[0] >> 6 == 1:
        csize = ((csd[7] & 0x3F) << 16) | (csd[8] << 8) | csd[9]
        blocks = (csize + 1) * 1024
        version = 'v2'
    else:
        read_bl_len = csd[5] & 0x0F
        csize = ((csd[6] & 0x03) << 10) | (csd[7] << 2) | (csd[8] >> 6)
        mult = ((csd[9] & 0x03) << 1) | (csd[10] >> 7)
        blocks = ((csize + 1) << (mult + 2) << read_bl_len) // BLOCK_SIZE
        version = 'v1'
    return 'CSD %s: %d blocks = %.1f MiB' % (version, blocks, blocks / 2048.0)


def _cid_text(cid):
    name = ''.join(chr(c) if 32 <= c < 127 else '?' for c in cid[3:8])
    serial = (cid[9] << 24) | (cid[10] << 16) | (cid[11] << 8) | cid[12]
    return 'CID: manufacturer 0x%02X, product %s, rev %d.%d, serial 0x%08X' % (
        cid[0], name, cid[8] >> 4, cid[8] & 0x0F, serial)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'sd_spi'
    name = 'SD card (SPI)'
    longname = 'SD / SDHC / SDXC / microSD card in SPI mode'
    desc = 'Decode SD card SPI-mode commands, responses and data blocks.'
    license = 'gplv2+'
    inputs = ['spi']
    outputs = ['sd_spi']
    tags = ['IC', 'Memory']

    annotations = (
        ('summary',  'Command summary'),
        ('cmd',      'Command'),
        ('arg',      'Argument'),
        ('crc',      'Command CRC7'),
        ('resp',     'Response'),
        ('token',    'Token'),
        ('data',     'Data block'),
        ('info',     'Informational'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_SUMMARY, ANN_INFO)),
        ('fields',   'Fields',   (ANN_CMD, ANN_ARG, ANN_CRC, ANN_RESP, ANN_TOKEN, ANN_DATA)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.app_next = False   # previous command was CMD55: the next one is an ACMD
        self.pending_read = None    # (cmd, block length) awaiting a data token
        self.pending_write = None   # cmd (24/25) awaiting a start token on MOSI
        self.write_state = None     # 'data-response' after a write block, until the response byte is seen

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _ann(self, ss, es, cls, strings):
        self.put(ss, es, self.out_ann, [cls, list(strings)])

    def _warn(self, ss, es, msg, tag):
        self._ann(ss, es, ANN_WARN, [msg, tag])

    def _py(self, ss, es, kind, value):
        self.put(ss, es, self.out_python, (kind, value))

    def decode(self, ss, es, data):
        if data[0] != 'TRANSFER':
            return
        mosi, miso = data[1], data[2]
        if not mosi:
            self._warn(ss, es, 'MOSI channel required to decode commands', 'MOSI?')
            return
        if not miso:
            self._warn(ss, es, 'MISO channel required to decode responses', 'MISO?')
            return
        self._transaction(ss, es, mosi, miso)

    # ------------------------------------------------------------------

    def _transaction(self, ss, es, mosi, miso):
        n = min(len(mosi), len(miso))
        i = 0
        while i < n:
            mo = mosi[i].val
            if self.pending_write is not None and mo in (0xFE, 0xFC, 0xFD):
                i = self._write_token(mosi, miso, i, n)
            elif self.pending_read is not None and mo == 0xFF and miso[i].val != 0xFF:
                i = self._read_token(mosi, miso, i, n)
            elif (mo & 0xC0) == 0x40:
                i = self._command(mosi, miso, i, n)
            else:
                i += 1

    def _command(self, mosi, miso, i, n):
        if i + 6 > n:
            self._warn(mosi[i].ss, mosi[n - 1].es, 'Short command frame (%d of 6 bytes)' % (n - i), 'SHORT')
            return n
        frame = [mosi[i + k].val for k in range(6)]
        num = frame[0] & 0x3F
        arg = (frame[1] << 24) | (frame[2] << 16) | (frame[3] << 8) | frame[4]
        is_app = self.app_next
        self.app_next = False
        names = ACMD_NAMES if is_app else CMD_NAMES
        label = ('ACMD%d' if is_app else 'CMD%d') % num
        title = '%s %s' % (label, names.get(num, '')) if num in names else label
        cmd_ss, cmd_es = mosi[i].ss, mosi[i].es
        self._ann(cmd_ss, cmd_es, ANN_CMD, [title, label, '%d' % num])
        self._ann(mosi[i + 1].ss, mosi[i + 4].es, ANN_ARG, ['Argument 0x%08X' % arg, 'arg=0x%08X' % arg, '0x%08X' % arg])
        want = (crc7(frame[:5]) << 1) | 1
        self._ann(mosi[i + 5].ss, mosi[i + 5].es, ANN_CRC,
                  ['CRC7 0x%02X%s' % (frame[5], '' if frame[5] == want else ' (expected 0x%02X)' % want),
                   'CRC 0x%02X' % frame[5]])
        if frame[5] != want:
            self._warn(mosi[i + 5].ss, mosi[i + 5].es, 'Command CRC7 0x%02X, expected 0x%02X' % (frame[5], want), 'CRC7')
        j = i + 6
        if num == 12 and not is_app:
            j += 1  # stuff byte
        # R1: first byte with bit 7 clear within 8 bytes after the command
        r1 = None
        k = j
        while k < min(n, j + 9):
            if miso[k].val & 0x80 == 0:
                r1 = k
                break
            k += 1
        summary = '%s(0x%08X)' % (label, arg)
        if r1 is None:
            self._warn(mosi[i].ss, mosi[min(n, j) - 1].es, '%s: no response' % label, 'NO-RESP')
            self._ann(cmd_ss, mosi[min(n, j) - 1].es, ANN_SUMMARY, [summary + ' → no response', summary])
            self.pending_read = self.pending_write = None
            return min(n, j)
        r1v = miso[r1].val
        self._ann(miso[r1].ss, miso[r1].es, ANN_RESP, ['R1 0x%02X: %s' % (r1v, _r1_text(r1v)), 'R1 0x%02X' % r1v])
        if r1v & R1_ERRORS:
            self._warn(miso[r1].ss, miso[r1].es, '%s: R1 error: %s' % (label, _r1_text(r1v)), 'R1')
        end = r1 + 1
        extra = ''
        if not is_app and num in (8, 58) and r1 + 5 <= n:
            b = [miso[r1 + 1 + t].val for t in range(4)]
            word = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
            if num == 8:
                text = 'R7 0x%08X: voltage %s, check pattern 0x%02X' % (word, '2.7-3.6 V' if b[2] & 0x0F == 1 else '0x%X' % (b[2] & 0x0F), b[3])
                if b[3] != (arg & 0xFF):
                    self._warn(miso[r1 + 1].ss, miso[r1 + 4].es, 'CMD8 check pattern 0x%02X does not echo 0x%02X' % (b[3], arg & 0xFF), 'ECHO')
            else:
                ccs = 'SDHC/SDXC (block addressing)' if b[0] & 0x40 else 'SDSC (byte addressing)'
                text = 'R3 OCR 0x%08X: %s%s, 3.3 V window %s' % (
                    word, 'powered up, ' if b[0] & 0x80 else 'busy, ', ccs, 'yes' if b[1] & 0x80 or b[2] & 0x80 else 'no')
            self._ann(miso[r1 + 1].ss, miso[r1 + 4].es, ANN_RESP, [text, 'R3/R7 0x%08X' % word])
            extra = ' ' + text.split(':')[0]
            end = r1 + 5
        elif not is_app and num == 13 and r1 + 2 <= n:
            r2 = miso[r1 + 1].val
            self._ann(miso[r1 + 1].ss, miso[r1 + 1].es, ANN_RESP, ['R2 0x%02X' % r2, 'R2'])
            end = r1 + 2
        self._ann(cmd_ss, miso[end - 1].es, ANN_SUMMARY, ['%s → R1 0x%02X (%s)%s' % (summary, r1v, _r1_text(r1v), extra), summary])
        self._py(cmd_ss, miso[end - 1].es, label, (arg, r1v))

        self.pending_read = None
        self.pending_write = None
        if num == 55 and not is_app:
            self.app_next = True
        elif r1v & R1_ERRORS == 0 and not is_app and num in READ_CMDS:
            self.pending_read = (num, READ_CMDS[num])
        elif r1v & R1_ERRORS == 0 and not is_app and num in WRITE_CMDS:
            self.pending_write = num
        return end

    def _read_token(self, mosi, miso, i, n):
        cmd, length = self.pending_read
        tok = miso[i].val
        if tok != 0xFE:
            if tok & 0xE0 == 0:
                reasons = [t for bit, t in ((1, 'error'), (2, 'CC error'), (4, 'card ECC failed'), (8, 'out of range')) if tok & bit]
                self._ann(miso[i].ss, miso[i].es, ANN_TOKEN, ['Error token 0x%02X: %s' % (tok, ', '.join(reasons)), 'ERR'])
                self._warn(miso[i].ss, miso[i].es, 'Data error token 0x%02X' % tok, 'ERR-TOKEN')
                self.pending_read = None
            return i + 1
        if i + 1 + length + 2 > n:
            self._warn(miso[i].ss, miso[n - 1].es, 'Short data block (%d of %d bytes)' % (n - i - 1, length), 'SHORT')
            self.pending_read = None
            return n
        data = [miso[i + 1 + k].val for k in range(length)]
        crc = (miso[i + 1 + length].val << 8) | miso[i + 2 + length].val
        self._ann(miso[i].ss, miso[i].es, ANN_TOKEN, ['Start block 0xFE', 'START'])
        text = '%d byte(s): %s' % (length, _hex(data))
        if cmd == 9:
            text = _csd_text(data)
        elif cmd == 10:
            text = _cid_text(data)
        elif length == BLOCK_SIZE and data[510] == 0x55 and data[511] == 0xAA:
            text += ' (boot signature 55 AA)'
        self._ann(miso[i + 1].ss, miso[i + length].es, ANN_DATA, [text, '%d B' % length])
        want = crc16(data)
        self._ann(miso[i + 1 + length].ss, miso[i + 2 + length].es, ANN_CRC,
                  ['CRC16 0x%04X%s' % (crc, '' if crc == want else ' (expected 0x%04X)' % want), 'CRC16'])
        if crc != want and crc != 0xFFFF:
            self._warn(miso[i + 1 + length].ss, miso[i + 2 + length].es, 'Data CRC16 0x%04X, expected 0x%04X' % (crc, want), 'CRC16')
        self._py(miso[i].ss, miso[i + 2 + length].es, 'READ-BLOCK', bytes(data))
        if cmd != 18:
            self.pending_read = None
        return i + 3 + length

    def _write_token(self, mosi, miso, i, n):
        tok = mosi[i].val
        if tok == 0xFD:
            self._ann(mosi[i].ss, mosi[i].es, ANN_TOKEN, ['Stop tran 0xFD', 'STOP'])
            self.pending_write = None
            return i + 1
        if i + 1 + BLOCK_SIZE + 2 > n:
            self._warn(mosi[i].ss, mosi[n - 1].es, 'Short write block (%d of %d bytes)' % (n - i - 1, BLOCK_SIZE), 'SHORT')
            return n
        data = [mosi[i + 1 + k].val for k in range(BLOCK_SIZE)]
        crc = (mosi[i + 1 + BLOCK_SIZE].val << 8) | mosi[i + 2 + BLOCK_SIZE].val
        self._ann(mosi[i].ss, mosi[i].es, ANN_TOKEN, ['Start block 0x%02X' % tok, 'START'])
        self._ann(mosi[i + 1].ss, mosi[i + BLOCK_SIZE].es, ANN_DATA, ['%d byte(s): %s' % (BLOCK_SIZE, _hex(data)), '%d B' % BLOCK_SIZE])
        want = crc16(data)
        self._ann(mosi[i + 1 + BLOCK_SIZE].ss, mosi[i + 2 + BLOCK_SIZE].es, ANN_CRC,
                  ['CRC16 0x%04X%s' % (crc, '' if crc == want else ' (expected 0x%04X)' % want), 'CRC16'])
        if crc != want and crc != 0xFFFF:
            self._warn(mosi[i + 1 + BLOCK_SIZE].ss, mosi[i + 2 + BLOCK_SIZE].es, 'Data CRC16 0x%04X, expected 0x%04X' % (crc, want), 'CRC16')
        j = i + 3 + BLOCK_SIZE
        if j < n:
            resp = miso[j].val
            status = {0x05: 'data accepted', 0x0B: 'CRC error', 0x0D: 'write error'}.get(resp & 0x1F, 'unknown')
            self._ann(miso[j].ss, miso[j].es, ANN_RESP, ['Data response 0x%02X: %s' % (resp, status), 'DR 0x%02X' % resp])
            if resp & 0x1F != 0x05:
                self._warn(miso[j].ss, miso[j].es, 'Write block rejected: %s' % status, 'WRITE')
            j += 1
        self._py(mosi[i].ss, mosi[min(j, n) - 1].es, 'WRITE-BLOCK', bytes(data))
        if tok == 0xFE:
            self.pending_write = None
        return j
