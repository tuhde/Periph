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

class Decoder(srd.Decoder):
    api_version = 3
    id = 'ens160'
    name = 'ENS160'
    longname = 'ScioSense ENS160 digital multi-gas sensor'
    desc = 'Digital multi-gas sensor with I2C/SPI interface.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = []
    tags = ['IC', 'Sensor']
    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('regs', 'Registers', (0, 1)),
        ('warnings', 'Warnings', (2,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.reg = None
        self.data = []
        self.ss = None
        self.es = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def putx(self, data):
        self.put(self.ss, self.es, self.out_ann, data)

    def putw(self, msg):
        self.putx([2, [msg]])

    def decode(self, ss, es, data):
        cmd, databyte = data

        self.ss, self.es = ss, es

        if cmd == 'START':
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
            elif self.state == 'REGISTER READ' and len(self.data) > 0:
                self.handle_read()
            self.reset()

    def handle_write(self):
        reg_name = self.get_reg_name(self.reg)
        if len(self.data) == 1:
            val = self.data[0]
            desc = self.get_write_desc(self.reg, val)
            self.putx([0, ['%s: 0x%02X (%s)' % (reg_name, val, desc)]])
        elif len(self.data) == 2:
            val = self.data[0] | (self.data[1] << 8)
            desc = self.get_write_desc_16(self.reg, val)
            self.putx([0, ['%s: 0x%04X (%s)' % (reg_name, val, desc)]])
        else:
            self.putx([0, ['%s: %d bytes' % (reg_name, len(self.data))]])

    def handle_read(self):
        reg_name = self.get_reg_name(self.reg)
        if len(self.data) == 1:
            val = self.data[0]
            desc = self.get_read_desc(self.reg, val)
            self.putx([1, ['%s: 0x%02X (%s)' % (reg_name, val, desc)]])
        elif len(self.data) == 2:
            val = self.data[0] | (self.data[1] << 8)
            desc = self.get_read_desc_16(self.reg, val)
            self.putx([1, ['%s: 0x%04X (%s)' % (reg_name, val, desc)]])
        else:
            self.putx([1, ['%s: %d bytes' % (reg_name, len(self.data))]])

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
