##
## This file is part of the libsigrokdecode project.
##
## Copyright (C) 2024 Periph Project
##
## This program is free software; you can redistribute it and/or modify
## it under the terms of the GNU General Public License as published by
## the Free Software Foundation; either version 2 of the License, or
## (at your option) any later version.
##
## This program is distributed in the hope that it will be useful,
## but WITHOUT ANY WARRANTY; without even the implied warranty of
## MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
## GNU General Public License for more details.
##
## You should have received a copy of the GNU General Public License
## along with this program; if not, see <http://www.gnu.org/licenses/>.
##

import sigrokdecode as srd

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2
ANN_STATUS  = 3
ANN_WARMUP_START  = 4
ANN_WARMUP_DONE   = 5
ANN_MEASUREMENT   = 6

VALIDITY_NAMES = ['OK', 'Warm-up', 'Initial Start-up', 'No valid output']
VALIDITY_SHORT = ['OK', 'WARM', 'INIT', 'INVLD']
PART_ID_ENS160 = 0x0160


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'part id' in m:
        return 'ID?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text
    (leading name token, <= 8 chars when it becomes the shortest tier) so a
    narrow PulseView zoom still has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_\u2192'):
            n += 1
        return text[:n]

    first = strings[0]
    words = first.split()
    for cand in (lead(strings[-1])[:8], lead(first), ' '.join(words[:2]).rstrip(':,'),
                 ' '.join(words[:3]).rstrip(':,'), lead(first)[:8]):
        if len(strings) >= 3:
            break
        if cand and cand not in strings and len(cand) < len(first):
            i = 0
            while i < len(strings) and len(strings[i]) > len(cand):
                i += 1
            strings.insert(i, cand)
    return strings


class Decoder(srd.Decoder):
    api_version = 3
    id = 'ens160'
    name = 'ENS160'
    longname = 'ScioSense ENS160 digital multi-gas sensor'
    desc = 'Digital multi-gas sensor with I2C/SPI interface.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ens160']
    tags = ['IC', 'Sensor']
    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('warning', 'Warning'),
        ('validity', 'DEVICE_STATUS validity'),
        ('warmup-start', 'Warm-up observed'),
        ('warmup-done', 'Warm-up finished (validity OK)'),
        ('measurement', 'DATA_AQI read'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_WRITE, ANN_READ)),
        ('status', 'Status', (ANN_STATUS,)),
        ('timing', 'Timing', (ANN_WARMUP_START, ANN_WARMUP_DONE, ANN_MEASUREMENT)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.reg = None
        self.data = []
        self.ss = None
        self.es = None
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def putx(self, ann, strings):
        self.put(self.ss_block, self.es, self.out_ann, [ann, strings])

    def putw(self, msg):
        self.putx(ANN_WARNING, [msg, _warn_tag(msg)])

    def decode(self, ss, es, data):
        cmd, databyte = data

        self.ss, self.es = ss, es

        if cmd == 'START':
            self.ss_block = ss
            self.state = 'ADDRESS'
            self.data = []
        elif cmd == 'ADDRESS READ':
            if databyte in (0x52, 0x53):
                self.state = 'REGISTER READ'
            else:
                self.state = 'IDLE'
        elif cmd == 'ADDRESS WRITE':
            if databyte in (0x52, 0x53):
                self.state = 'REGISTER WRITE'
            else:
                self.state = 'IDLE'
        elif cmd == 'DATA WRITE':
            if self.state == 'REGISTER WRITE':
                self.reg = databyte
                self.state = 'DATA WRITE VALUE'
            elif self.state == 'DATA WRITE VALUE':
                self.data.append(databyte)
        elif cmd == 'DATA READ':
            if self.state == 'REGISTER READ':
                self.data.append(databyte)
        elif cmd == 'STOP':
            if self.state == 'DATA WRITE VALUE' and self.reg is not None:
                self.handle_write()
            elif self.state == 'REGISTER READ' and len(self.data) > 0 and self.reg is not None:
                self.handle_read()
            self.reset()

    def handle_write(self):
        reg_name = self.get_reg_name(self.reg)
        if len(self.data) == 1:
            val = self.data[0]
            desc = self.get_write_desc(self.reg, val)
            self.putx(ANN_WRITE, ['%s: 0x%02X (%s)' % (reg_name, val, desc),
                                  '%s \u2190 0x%02X' % (reg_name, val), 'W 0x%02X' % val])
            self.put(self.ss_block, self.es, self.out_python, ('REG_WRITE', (self.reg, val)))
        elif len(self.data) == 2:
            val = self.data[0] | (self.data[1] << 8)
            desc = self.get_write_desc_16(self.reg, val)
            self.putx(ANN_WRITE, ['%s: 0x%04X (%s)' % (reg_name, val, desc),
                                  '%s \u2190 0x%04X' % (reg_name, val), 'W 0x%04X' % val])
            self.put(self.ss_block, self.es, self.out_python, ('REG_WRITE', (self.reg, val)))
        elif len(self.data) == 0:
            self.putw('Write to %s with no data bytes' % reg_name)
        else:
            self.putx(ANN_WRITE, ['%s: %d bytes' % (reg_name, len(self.data)),
                                  '%s %dB' % (reg_name, len(self.data)), 'W %dB' % len(self.data)])
            self.put(self.ss_block, self.es, self.out_python, ('REG_WRITE', (self.reg, bytes(self.data))))

    def handle_read(self):
        reg_name = self.get_reg_name(self.reg)
        if len(self.data) == 1:
            val = self.data[0]
            desc = self.get_read_desc(self.reg, val)
            self.putx(ANN_READ, ['%s: 0x%02X (%s)' % (reg_name, val, desc),
                                 '%s = 0x%02X' % (reg_name, val), 'R 0x%02X' % val])
            self.put(self.ss_block, self.es, self.out_python, ('REG_READ', (self.reg, val)))
            if self.reg == 0x20:
                self.emit_status(val)
            elif self.reg == 0x21:
                self.emit_measurement()
        elif len(self.data) == 2:
            val = self.data[0] | (self.data[1] << 8)
            desc = self.get_read_desc_16(self.reg, val)
            self.putx(ANN_READ, ['%s: 0x%04X (%s)' % (reg_name, val, desc),
                                 '%s = 0x%04X' % (reg_name, val), 'R 0x%04X' % val])
            self.put(self.ss_block, self.es, self.out_python, ('REG_READ', (self.reg, val)))
            if self.reg == 0x00 and val != PART_ID_ENS160:
                self.putw('Unexpected part ID 0x%04X (expected 0x%04X)' % (val, PART_ID_ENS160))
        else:
            self.putx(ANN_READ, ['%s: %d bytes' % (reg_name, len(self.data)),
                                 '%s %dB' % (reg_name, len(self.data)), 'R %dB' % len(self.data)])
            self.put(self.ss_block, self.es, self.out_python, ('REG_READ', (self.reg, bytes(self.data))))

    def emit_status(self, val):
        """DEVICE_STATUS read: validity flag -> status row, warm-up edges -> timing row."""
        validity = (val >> 2) & 0x03
        flags = []
        if val & 0x02: flags.append('NEWDAT')
        if val & 0x01: flags.append('NEWGPR')
        if val & 0x80: flags.append('STATAS')
        if val & 0x40: flags.append('STATER')
        text = 'Validity: %s' % VALIDITY_NAMES[validity]
        long_text = text + (', ' + ', '.join(flags) if flags else '')
        self.putx(ANN_STATUS, [long_text, text, VALIDITY_SHORT[validity]])
        self.put(self.ss_block, self.es, self.out_python, ('STATUS', (VALIDITY_NAMES[validity], tuple(flags))))
        if validity == 1:
            self.putx(ANN_WARMUP_START, ['warmup_time_start: validity reports Warm-up',
                                         'warmup_time_start', '\u2192WARM'])
        elif validity == 0:
            self.putx(ANN_WARMUP_DONE, ['warmup_time_done: validity reports OK',
                                        'warmup_time_done', 'WARM\u2713'])

    def emit_measurement(self):
        self.putx(ANN_MEASUREMENT, ['measurement_cycle: DATA_AQI read',
                                    'measurement_cycle', 'AQI'])

    def get_reg_name(self, reg):
        regs = {
            0x00: 'PART_ID',
            0x10: 'OPMODE',
            0x11: 'CONFIG',
            0x12: 'COMMAND',
            0x13: 'TEMP_IN',
            0x15: 'RH_IN',
            0x20: 'DEVICE_STATUS',
            0x21: 'DATA_AQI',
            0x22: 'DATA_TVOC',
            0x24: 'DATA_ECO2',
            0x30: 'DATA_T',
            0x32: 'DATA_RH',
            0x38: 'DATA_MISR',
            0x40: 'GPR_WRITE',
            0x48: 'GPR_READ',
        }
        return regs.get(reg, 'REG[0x%02X]' % reg)

    def get_write_desc(self, reg, val):
        if reg == 0x10:  # OPMODE
            modes = {0x00: 'DEEP SLEEP', 0x01: 'IDLE', 0x02: 'STANDARD', 0xF0: 'RESET'}
            return modes.get(val, 'UNKNOWN')
        elif reg == 0x11:  # CONFIG
            parts = []
            if val & 0x01: parts.append('INT_EN')
            if val & 0x02: parts.append('INT_DAT')
            if val & 0x08: parts.append('INT_GPR')
            if val & 0x20: parts.append('INT_PP')
            if val & 0x40: parts.append('INT_POL')
            return ', '.join(parts) if parts else 'No interrupts'
        elif reg == 0x12:  # COMMAND
            cmds = {0x00: 'NOP', 0x0E: 'GET_APPVER', 0xCC: 'CLRGPR'}
            return cmds.get(val, 'UNKNOWN')
        return '0x%02X' % val

    def get_write_desc_16(self, reg, val):
        if reg == 0x13:  # TEMP_IN
            temp_c = (val / 64.0) - 273.15
            return '%.1f°C' % temp_c
        elif reg == 0x15:  # RH_IN
            rh = val / 512.0
            return '%.1f%%RH' % rh
        return '0x%04X' % val

    def get_read_desc(self, reg, val):
        if reg == 0x00:  # PART_ID (low byte)
            return 'PART_ID low byte'
        elif reg == 0x20:  # DEVICE_STATUS
            parts = []
            validity = (val >> 2) & 0x03
            validity_names = ['OK', 'Warm-up', 'Initial Start-up', 'No valid output']
            parts.append('Validity: %s' % validity_names[validity])
            if val & 0x02: parts.append('NEWDAT')
            if val & 0x01: parts.append('NEWGPR')
            if val & 0x80: parts.append('STATAS')
            if val & 0x40: parts.append('STATER')
            return ', '.join(parts)
        elif reg == 0x21:  # DATA_AQI
            aqi = val & 0x07
            aqi_names = ['', 'Excellent', 'Good', 'Moderate', 'Poor', 'Unhealthy']
            name = aqi_names[aqi] if 1 <= aqi <= 5 else 'Unknown'
            return 'AQI=%d (%s)' % (aqi, name)
        elif reg == 0x38:  # DATA_MISR
            return 'CRC=0x%02X' % val
        return '0x%02X' % val

    def get_read_desc_16(self, reg, val):
        if reg == 0x00:  # PART_ID
            return 'PART_ID=0x%04X' % val
        elif reg == 0x22:  # DATA_TVOC
            return 'TVOC=%d ppb' % val
        elif reg == 0x24:  # DATA_ECO2
            return 'eCO2=%d ppm' % val
        elif reg == 0x30:  # DATA_T
            temp_c = (val / 64.0) - 273.15
            return '%.1f°C' % temp_c
        elif reg == 0x32:  # DATA_RH
            rh = val / 512.0
            return '%.1f%%RH' % rh
        return '0x%04X' % val
