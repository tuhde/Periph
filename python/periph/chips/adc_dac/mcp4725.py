class _MCP47xxBase:
    _GENERAL_CALL_ADDR = 0x00
    _GEN_CALL_RESET = 0x06
    _GEN_CALL_WAKEUP = 0x09

    def wake_up(self):
        self._transport.write(bytes([self._GENERAL_CALL_ADDR, self._GEN_CALL_WAKEUP]))

    def reset(self):
        self._transport.write(bytes([self._GENERAL_CALL_ADDR, self._GEN_CALL_RESET]))

    def is_eeprom_ready(self):
        raw = self._transport.write_read(bytes([0x00]), 1)
        return not bool(raw[0] & 0x80)


class MCP4725Minimal:
    _FAST_WRITE = 0x00
    _WRITE_DAC = 0x40
    _WRITE_DAC_EEPROM = 0x60

    def __init__(self, transport):
        self._transport = transport

    def set_voltage(self, fraction):
        code = int(max(0.0, min(1.0, fraction)) * 4095)
        self.set_raw(code)

    def set_raw(self, code):
        code = max(0, min(4095, code))
        byte1 = (code >> 8) & 0x0F
        byte2 = code & 0xFF
        self._transport.write(bytes([byte1, byte2]))


class MCP4725Full(MCP4725Minimal, _MCP47xxBase):
    def set_voltage_eeprom(self, fraction):
        code = int(max(0.0, min(1.0, fraction)) * 4095)
        self.set_raw_eeprom(code)

    def set_raw_eeprom(self, code):
        code = max(0, min(4095, code))
        byte1 = 0x60 | ((code >> 8) & 0x0F)
        byte2 = code & 0xFF
        self._transport.write(bytes([byte1, byte2, 0x00]))

    def read(self):
        raw = self._transport.write_read(bytes([0x00]), 5)
        dac_code = ((raw[1] << 8) | raw[2]) >> 4
        eeprom_code = ((raw[3] & 0x0F) << 8) | raw[4]
        return {
            "code": dac_code,
            "voltage_fraction": dac_code / 4095.0,
            "power_down": (raw[0] >> 2) & 0x03,
            "eeprom_code": eeprom_code,
            "eeprom_power_down": (raw[3] >> 4) & 0x03,
            "eeprom_ready": bool(raw[0] & 0x80),
            "por": bool(raw[0] & 0x40),
        }

    def set_power_down(self, mode):
        raw = self._transport.write_read(bytes([0x00]), 1)
        current_dac = raw[0] & 0x0F
        pd_bits = (mode & 0x03) << 4
        self._transport.write(bytes([current_dac | pd_bits, 0x00]))