class PCF8576Minimal:
    _CMD_MODE_SET   = 0x80
    _CMD_LOAD_DP    = 0x20
    _CMD_DEV_SEL    = 0x40
    _CMD_BANK       = 0x60
    _CMD_BLINK      = 0x70

    SEG_7SEG = (
        0xED, 0x60, 0xA7, 0xE3, 0x6A, 0xCB, 0xCF, 0xE0, 0xEF, 0xEB, 0x00
    )

    def __init__(self, transport, address=0x38):
        self._transport = transport
        self._address = address
        self._subaddress = 0
        self._write_cmd(bytes([self._CMD_DEV_SEL | 0x01 | (self._subaddress << 1)]))
        self._write_cmd(bytes([0x88]))
        self._write_cmd(bytes([0x20]))
        self._transport.write(bytes([0] * 40))

    def _write_cmd(self, data):
        self._transport.write(data)

    def clear(self):
        self._transport.write(bytes([0x20, 0x00]))
        self._transport.write(bytes([0] * 40))

    def write_raw(self, address, data):
        if address < 0 or address + len(data) > 40:
            return
        cmd = bytes([self._CMD_LOAD_DP | address])
        self._transport.write(cmd)
        self._transport.write(data)

    def set_digit_7seg(self, position, segments):
        if position < 0 or position > 19:
            return
        addr = position * 2
        cmd = bytes([self._CMD_LOAD_DP | addr])
        self._transport.write(cmd)
        self._transport.write(bytes([segments]))


class PCF8576Full(PCF8576Minimal):
    MUX_STATIC = 0
    MUX_1_2    = 1
    MUX_1_3    = 3
    MUX_1_4    = 2

    BLINK_OFF  = 0
    BLINK_2HZ  = 1
    BLINK_1HZ  = 2
    BLINK_05HZ = 3

    def __init__(self, transport, address=0x38):
        self._transport = transport
        self._address = address
        self._subaddress = 0
        self._write_cmd(bytes([self._CMD_DEV_SEL | 0x01 | (self._subaddress << 1)]))
        self._write_cmd(bytes([0x88]))
        self._write_cmd(bytes([0x20]))
        self._transport.write(bytes([0]))

    def _build_mode_set(self, backplanes, bias, enable):
        e = 0x08 if enable else 0x00
        b = 0x04 if bias else 0x00
        m = (0 if backplanes == 4 else
             1 if backplanes == 1 else
             2 if backplanes == 2 else
             3)
        return 0x80 | e | b | m

    def enable(self):
        mode = self._build_mode_set(4, 0, True)
        self._write_cmd(bytes([mode]))

    def disable(self):
        mode = self._build_mode_set(4, 0, False)
        self._write_cmd(bytes([mode]))

    def set_mode(self, backplanes, bias=0):
        mode = self._build_mode_set(backplanes, bias, True)
        self._write_cmd(bytes([mode]))

    def set_blink(self, frequency, alternate_bank=False):
        ab = 0x04 if alternate_bank else 0x00
        cmd = self._CMD_BLINK | ab | (frequency & 0x03)
        self._write_cmd(bytes([cmd]))

    def set_bank(self, input_bank, output_bank):
        i = 0x02 if input_bank else 0x00
        o = 0x01 if output_bank else 0x00
        cmd = self._CMD_BANK | i | o
        self._write_cmd(bytes([cmd]))

    def device_select(self, subaddress):
        if subaddress < 0 or subaddress > 7:
            return
        self._subaddress = subaddress
        cmd = self._CMD_DEV_SEL | 0x01 | (subaddress << 1)
        self._write_cmd(bytes([cmd]))