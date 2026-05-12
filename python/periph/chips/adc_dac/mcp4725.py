import struct


class MCP4725Minimal:
    """MCP4725 single-channel 12-bit voltage-output DAC — minimal interface.

    Provides simple voltage output as a fraction of V_DD with no configuration
    beyond the transport. Uses Fast Write (2-byte) for DAC register updates.

    Args:
        transport: Configured I²C transport pointing at the device (0x60–0x61).
    """

    _CMD_FAST_WRITE = 0x00

    def __init__(self, transport):
        """Initialize MCP4725Minimal and store the transport.

        Args:
            transport: Configured I²C transport pointing at the device.
        """
        self._transport = transport

    def set_voltage(self, fraction):
        """Set the DAC output as a fraction of V_DD.

        Clamps the input to [0.0, 1.0] and uses Fast Write to update the DAC
        register only (EEPROM unchanged).

        Args:
            fraction: Output voltage as a fraction of V_DD (0.0–1.0).
        """
        code = int(round(max(0.0, min(1.0, fraction)) * 4095))
        self._fast_write(code, pd_mode=0)

    def set_raw(self, code):
        """Set the raw 12-bit DAC code directly.

        Clamps the input to [0, 4095] and uses Fast Write to update the DAC
        register only (EEPROM unchanged).

        Args:
            code: Raw 12-bit DAC code (0–4095).
        """
        code = int(max(0, min(4095, code)))
        self._fast_write(code, pd_mode=0)

    def _fast_write(self, code, pd_mode=0):
        byte1 = ((pd_mode & 0x03) << 4) | ((code >> 8) & 0x0F)
        byte2 = code & 0xFF
        self._transport.write(bytes([byte1, byte2]))


class MCP4725Full(MCP4725Minimal):
    """MCP4725 full interface — extends MCP4725Minimal with EEPROM, power-down, and read-back.

    Adds write-with-EEPROM persistence, power-down modes, General Call reset/wake,
    and full register read-back of both DAC and EEPROM contents.

    Args:
        transport: Configured I²C transport pointing at the device (0x60–0x61).
    """

    _CMD_WRITE_DAC_EEPROM = 0x60
    _CMD_WRITE_DAC        = 0x40
    _ADDR_GENERAL_CALL    = 0x00

    _GC_RESET = 0x06
    _GC_WAKE  = 0x09

    PD_NORMAL      = 0
    PD_1K_GND      = 1
    PD_100K_GND    = 2
    PD_500K_GND    = 3

    def __init__(self, transport):
        """Initialize MCP4725Full and store the transport.

        Args:
            transport: Configured I²C transport pointing at the device.
        """
        super().__init__(transport)

    def set_voltage_eeprom(self, fraction):
        """Set the DAC output and persist to EEPROM.

        Writes both the DAC register and EEPROM so the value survives power cycles.
        Uses a 3-byte Write DAC + EEPROM command.

        Args:
            fraction: Output voltage as a fraction of V_DD (0.0–1.0).
        """
        code = int(round(max(0.0, min(1.0, fraction)) * 4095))
        self._write_dac_eeprom(code, pd_mode=0)

    def set_raw_eeprom(self, code):
        """Set the raw 12-bit DAC code and persist to EEPROM.

        Writes both the DAC register and EEPROM so the value survives power cycles.
        Uses a 3-byte Write DAC + EEPROM command.

        Args:
            code: Raw 12-bit DAC code (0–4095).
        """
        code = int(max(0, min(4095, code)))
        self._write_dac_eeprom(code, pd_mode=0)

    def read(self):
        """Read the current DAC register and EEPROM contents.

        Returns:
            dict: Contains code, voltage_fraction, power_down, eeprom_code,
                  eeprom_power_down, and eeprom_ready (bool).
        """
        buf = self._transport.write_read(bytes([0x00]), 5)
        rdy_bsy = bool(buf[0] & 0x80)
        por = bool(buf[0] & 0x40)
        pd_dac = (buf[0] >> 2) & 0x03
        code = ((buf[1] << 4) | ((buf[2] >> 4) & 0x0F))
        pd_eeprom = (buf[3] >> 6) & 0x03
        eeprom_code = ((buf[3] & 0x0F) << 8) | buf[4]
        return {
            'code': code,
            'voltage_fraction': code / 4095.0,
            'power_down': pd_dac,
            'eeprom_code': eeprom_code,
            'eeprom_power_down': pd_eeprom,
            'eeprom_ready': rdy_bsy,
        }

    def set_power_down(self, mode):
        """Set the power-down mode and preserve the current DAC code.

        Args:
            mode: Power-down mode 0–3 (0 = normal, 1 = 1 kΩ to GND,
                  2 = 100 kΩ to GND, 3 = 500 kΩ to GND).
        """
        mode = int(max(0, min(3, mode)))
        code = self._read_dac_code()
        self._fast_write(code, pd_mode=mode)

    def wake_up(self):
        """Send General Call Wake-Up (0x00, 0x09) to clear power-down bits."""
        self._transport.write(bytes([self._ADDR_GENERAL_CALL, self._GC_WAKE]))

    def reset(self):
        """Send General Call Reset (0x00, 0x06) to trigger internal POR."""
        self._transport.write(bytes([self._ADDR_GENERAL_CALL, self._GC_RESET]))

    def is_eeprom_ready(self):
        """Check if the EEPROM write operation is complete.

        Returns:
            bool: True when a pending EEPROM write has finished.
        """
        buf = self._transport.write_read(bytes([0x00]), 1)
        return bool(buf[0] & 0x80)

    def _write_dac_eeprom(self, code, pd_mode=0):
        byte1 = self._CMD_WRITE_DAC_EEPROM | ((pd_mode & 0x03) << 1)
        byte2 = (code >> 4) & 0xFF
        byte3 = (code & 0x0F) << 4
        self._transport.write(bytes([byte1, byte2, byte3]))

    def _read_dac_code(self):
        buf = self._transport.write_read(bytes([0x00]), 2)
        return ((buf[0] & 0x0F) << 8) | buf[1]