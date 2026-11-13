"""DS3502 — 7-bit nonvolatile I²C digital potentiometer, high voltage (Maxim).

A single 128-position potentiometer (10 kΩ end to end, ±20 %) whose terminals
RH / RL / RW accept voltages up to 15.5 V (V+ = 4.5–15.5 V) while the logic
runs from VCC = 2.5–5.5 V. The wiper position lives in the Wiper Register (WR,
SRAM); the Initial Value Register (IVR, EEPROM) holds the position loaded at
power-up (factory default 0x40). The I²C address is 0x28 (A1 = A0 = GND) to
0x2B (A1 = A0 = VCC). The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

Two registers exist: ``0x00`` is WR (and, depending on the MODE bit of CR, also
IVR; reads always return WR) and ``0x02`` is the Control Register whose bit 7
selects MODE (1 = writes to 0x00 only change WR, 0 = they also program the
EEPROM). The driver uses ``RegisterConnection`` (1-byte register address) and
runs with MODE = 1 so that routine wiper writes never wear the EEPROM;
``store_wiper`` (Full) is the only operation that writes the IVR.

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x28–0x2B).
"""

import time

I2C_ADDRESS = 0x28

_REG_WR = 0x00
_REG_CR = 0x02

_CR_MODE_SRAM = 0x80     # MODE = 1: writes to 0x00 update WR only
_CR_MODE_EEPROM = 0x00   # MODE = 0: writes to 0x00 update WR and IVR

_MAX_CODE = 127
_POWER_UP_S = 0.003      # tD: power-up recall of the IVR into WR
_POLL_S = 0.001
_R_TOTAL = 10000.0       # nominal end-to-end resistance, ±20 %


def _clamp_code(code):
    code = int(code)
    if code < 0:
        return 0
    if code > _MAX_CODE:
        return _MAX_CODE
    return code


class DS3502Minimal:
    """DS3502 digital potentiometer — minimal interface.

    Sets and reads back the wiper position, or the divider output voltage.
    Defaults: MODE = 1 (SRAM-only wiper writes), wiper left at the power-up
    (IVR) position, divider ends ``v_high`` = 1.0 V (a ratio) and ``v_low`` =
    0 V until the application states the real RH / RL voltages; the wiper is
    assumed unloaded (≤ 1 mA).

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        v_high: Voltage applied to RH, in volts (default 1.0).
        v_low: Voltage applied to RL, in volts (default 0.0).
    """

    def __init__(self, connection, v_high=1.0, v_low=0.0):
        """Initialize DS3502Minimal and run the initialization sequence.

        Writes CR ← 0x80 (MODE = 1), retrying once after 3 ms if the chip
        NACKs because it is still in its power-up recall, then reads WR.

        Args:
            connection: RegisterConnection (I²C) pointing at the device.
            v_high: Voltage applied to RH, in volts.
            v_low: Voltage applied to RL, in volts.
        """
        self._connection = connection
        self._v_high = float(v_high)
        self._v_low = float(v_low)
        try:
            self._write_reg(_REG_CR, _CR_MODE_SRAM)
        except OSError:
            time.sleep(_POWER_UP_S)
            self._write_reg(_REG_CR, _CR_MODE_SRAM)
        self._code = self._read_reg(_REG_WR) & _MAX_CODE

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([value & 0xFF]))

    def _write_code(self, code):
        code = _clamp_code(code)
        self._write_reg(_REG_WR, code)
        self._code = code

    def _read_code(self):
        code = self._read_reg(_REG_WR) & _MAX_CODE
        self._code = code
        return code

    def _code_for_voltage(self, volts):
        span = self._v_high - self._v_low
        if span == 0:
            return 0
        return _clamp_code(int((volts - self._v_low) / span * _MAX_CODE + 0.5))

    def set_wiper(self, code):
        """Set the wiper position (volatile, SRAM only).

        Args:
            code: Wiper position 0–127 (clamped); 0 = at RL, 127 = at RH.
        """
        self._write_code(code)

    def get_wiper(self):
        """Read back the wiper position (WR).

        Returns:
            int: Wiper position 0–127.
        """
        return self._read_code()

    def set_voltage(self, volts):
        """Set the unloaded RW output voltage to the nearest reachable code.

        Args:
            volts: Target voltage in volts; clamped to the range between
                ``v_low`` and ``v_high``.
        """
        self._write_code(self._code_for_voltage(volts))

    def get_voltage(self):
        """Read the unloaded RW voltage for the current wiper code.

        Returns:
            float: Voltage in volts, ``v_low + (v_high - v_low) * code / 127``.
        """
        return self._v_low + (self._v_high - self._v_low) * self._read_code() / _MAX_CODE


class DS3502Full(DS3502Minimal):
    """DS3502 full interface — extends Minimal with ratio / resistance helpers,
    increment, EEPROM storage of the power-up value, MODE control and
    acknowledge polling.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        v_high: Voltage applied to RH, in volts (default 1.0).
        v_low: Voltage applied to RL, in volts (default 0.0).
    """

    def set_ratio(self, ratio):
        """Set the wiper by divider ratio V(RW−RL) / V(RH−RL).

        Args:
            ratio: 0.0–1.0; the code is ``round(ratio * 127)``.
        """
        self._write_code(int(ratio * _MAX_CODE + 0.5))

    def get_ratio(self):
        """Read the divider ratio.

        Returns:
            float: ``code / 127``.
        """
        return self._read_code() / _MAX_CODE

    def get_resistance(self, terminal='L'):
        """Nominal wiper resistance for the current code (RTOTAL = 10 kΩ ±20 %).

        Args:
            terminal: ``'L'`` for wiper-to-RL, ``'H'`` for wiper-to-RH.

        Returns:
            float: Resistance in ohms (approximate; excludes the wiper
            resistance).

        Raises:
            ValueError: if terminal is not ``'L'`` or ``'H'``.
        """
        if terminal not in ('L', 'H'):
            raise ValueError("terminal must be 'L' or 'H'")
        code = self._read_code()
        if terminal == 'L':
            return code / _MAX_CODE * _R_TOTAL
        return (_MAX_CODE - code) / _MAX_CODE * _R_TOTAL

    def increment(self, steps=1):
        """Move the wiper relative to its current position (SRAM only).

        Args:
            steps: Signed step count (negative to decrement).

        Returns:
            int: The new wiper code, clamped to 0–127.
        """
        code = _clamp_code(self._read_code() + int(steps))
        self._write_code(code)
        return code

    def store_wiper(self, code=None):
        """Write the Initial Value Register (EEPROM power-up position).

        Sets MODE = 0, writes WR / IVR, waits for the EEPROM write
        (acknowledge polling, ≤ 20 ms) and restores MODE = 1, also on error.
        The wiper moves to ``code``. Each call counts toward the EEPROM
        endurance (50 000 writes at 70 °C, 200 000 at 25 °C).

        Args:
            code: Position 0–127 to store; default the current wiper position.

        Raises:
            TimeoutError: if the EEPROM write does not finish in time.
        """
        if code is None:
            code = self._read_code()
        code = _clamp_code(code)
        self._write_reg(_REG_CR, _CR_MODE_EEPROM)
        try:
            self._write_reg(_REG_WR, code)
            self._code = code
            self.wait_ready()
        finally:
            self._write_reg(_REG_CR, _CR_MODE_SRAM)

    def set_mode(self, eeprom):
        """Select what a write to the wiper register updates.

        Advanced use — ``store_wiper`` handles the mode itself.

        Args:
            eeprom: True for MODE = 0 (writes also program the IVR), False
                for MODE = 1 (SRAM only).
        """
        self._write_reg(_REG_CR, _CR_MODE_EEPROM if eeprom else _CR_MODE_SRAM)

    def get_mode(self):
        """Read the MODE bit of the Control Register.

        Returns:
            bool: True if MODE = 0 (writes reach the EEPROM).
        """
        return (self._read_reg(_REG_CR) & _CR_MODE_SRAM) == 0

    def wait_ready(self, timeout_ms=30):
        """Acknowledge polling: repeat a read of CR until the chip ACKs.

        Args:
            timeout_ms: Maximum time to wait, in milliseconds.

        Raises:
            TimeoutError: if the chip still NACKs after ``timeout_ms``.
        """
        attempts = int(timeout_ms / (_POLL_S * 1000.0)) + 1
        for _ in range(attempts):
            try:
                self._read_reg(_REG_CR)
                return
            except OSError:
                time.sleep(_POLL_S)
        raise TimeoutError('DS3502 busy: EEPROM write did not finish')

    def set_endpoints(self, v_high, v_low=0.0):
        """Change the RH / RL voltages used by the conversions (no I²C traffic).

        Args:
            v_high: Voltage applied to RH, in volts.
            v_low: Voltage applied to RL, in volts.
        """
        self._v_high = float(v_high)
        self._v_low = float(v_low)
