"""DRV8830 — Low-voltage motor driver with I2C interface (Texas Instruments).

H-bridge driver for a single brushed DC motor, controlled entirely over I2C.
Instead of a duty cycle, the host commands a target output voltage; the chip
PWM-regulates the bridge to hold that average voltage across the winding
regardless of supply sag. Nine selectable addresses (0x60-0x68) via the
tri-state A0/A1 strap pins. The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

Args:
    connection: Configured I2C connection pointing at the device
        (0x60-0x68, per the board's A0/A1 strapping).
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x60

# Internal reference voltage, typical (datasheet: 1.235-1.335 V).
VREF = 1.285

# Valid VSET DAC codes (0x00-0x05 are reserved).
VSET_MIN = 6
VSET_MAX = 63

# Direction names returned by read_output().
DIRECTION_COAST = 'coast'
DIRECTION_REVERSE = 'reverse'
DIRECTION_FORWARD = 'forward'
DIRECTION_BRAKE = 'brake'

_DIRECTIONS = (DIRECTION_COAST, DIRECTION_FORWARD, DIRECTION_REVERSE, DIRECTION_BRAKE)


def _voltage_to_vset(voltage):
    """Map |voltage| to a VSET code; 0 means coast (below the vset=6 floor)."""
    vset = int(abs(voltage) * 16.0 / VREF + 0.5)
    if vset < VSET_MIN:
        return 0
    if vset > VSET_MAX:
        return VSET_MAX
    return vset


def _vset_to_voltage(vset):
    if vset < VSET_MIN:
        return 0.0
    return VREF * vset / 16.0


class DRV8830Minimal:
    """DRV8830 low-voltage motor driver — minimal interface.

    Drives the motor at a regulated voltage in either direction; no
    configuration required beyond the connection.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    _REG_CONTROL = 0x00
    _REG_FAULT   = 0x01

    # CONTROL (0x00) bits.
    _CTRL_IN1 = 0x01
    _CTRL_IN2 = 0x02

    # FAULT (0x01) bits.
    _FAULT_FAULT  = 0x01
    _FAULT_OCP    = 0x02
    _FAULT_UVLO   = 0x04
    _FAULT_OTS    = 0x08
    _FAULT_ILIMIT = 0x10
    _FAULT_CLEAR  = 0x80

    def __init__(self, connection):
        self._connection = connection
        self._read_reg(self._REG_CONTROL)  # presence check; no writes needed

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([reg]), 1)[0]

    def drive(self, voltage):
        """Drive the motor at a regulated output voltage.

        Writes VSET and IN1/IN2 together in one CONTROL write. A magnitude
        below the chip's ~0.48 V floor is treated as 0 V (coast); above
        ~5.06 V it is clamped to the maximum code.

        Args:
            voltage: Signed target voltage in volts — positive = forward,
                negative = reverse, 0.0 = standby/coast.
        """
        vset = _voltage_to_vset(voltage)
        if vset == 0:
            self._write_reg(self._REG_CONTROL, 0x00)
        elif voltage > 0:
            self._write_reg(self._REG_CONTROL, (vset << 2) | self._CTRL_IN1)
        else:
            self._write_reg(self._REG_CONTROL, (vset << 2) | self._CTRL_IN2)

    def brake(self):
        """Short-brake the motor (IN1 = IN2 = 1, both outputs high)."""
        self._write_reg(self._REG_CONTROL, self._CTRL_IN1 | self._CTRL_IN2)

    def stop(self):
        """Put the bridge in standby/coast (IN1 = IN2 = 0) — same as drive(0.0)."""
        self._write_reg(self._REG_CONTROL, 0x00)


class DRV8830Full(DRV8830Minimal):
    """DRV8830 full interface — extends Minimal with raw CONTROL access,
    output read-back, fault reporting/clearing, and the FAULTn interrupt API.

    Faults are never cleared implicitly: a latched OCP/ILIMIT fault also
    disables the H-bridge, so clearing is always an explicit clear_fault().

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None

    def set_output(self, vset, in1, in2):
        """Write the CONTROL register from raw fields.

        Args:
            vset: VSET DAC code, 6-63 (0-5 are reserved).
            in1: H-bridge input 1.
            in2: H-bridge input 2.

        Raises:
            ValueError: if vset is outside 6-63.
        """
        if vset < VSET_MIN or vset > VSET_MAX:
            raise ValueError('vset must be 6-63')
        value = (vset << 2) | (self._CTRL_IN1 if in1 else 0) | (self._CTRL_IN2 if in2 else 0)
        self._write_reg(self._REG_CONTROL, value)

    def read_output(self):
        """Read back and decode the CONTROL register.

        Returns:
            tuple: (voltage, direction) — voltage is the commanded magnitude
            in volts (0.0 for a reserved/zero VSET code); direction is one of
            'forward', 'reverse', 'coast', 'brake'.
        """
        ctrl = self._read_reg(self._REG_CONTROL)
        return (_vset_to_voltage(ctrl >> 2), _DIRECTIONS[ctrl & 0x03])

    def read_fault(self):
        """Read the FAULT register without clearing it.

        Returns:
            tuple: (fault, ocp, uvlo, ots, ilimit), all bool.
        """
        f = self._read_reg(self._REG_FAULT)
        return (bool(f & self._FAULT_FAULT), bool(f & self._FAULT_OCP),
                bool(f & self._FAULT_UVLO), bool(f & self._FAULT_OTS),
                bool(f & self._FAULT_ILIMIT))

    def clear_fault(self):
        """Clear all fault status bits (CLEAR = 1).

        Re-enables the H-bridge if an OCP/ILIMIT fault had latched it off.
        """
        self._write_reg(self._REG_FAULT, self._FAULT_CLEAR)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to fault interrupts (FAULTn, active-low).

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 5 ms polling thread on Linux that
        fires on each new fault (FAULT bit rising). Does not clear the fault.

        Args:
            callback: Callable((fault, ocp, uvlo, ots, ilimit)) — same tuple
                as read_fault().
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        if self._int_pin_used is not None:
            from periph.connection.input_pin import InputPin
            self._int_pin_used.off_edge(self._int_handler)
            self._int_pin_used = None
        elif _LINUX:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if status[0] and self._callback:
            self._callback(status)

    def _poll_loop(self):
        import time
        was_fault = False
        while not self._poll_stop:
            status = self.poll_interrupt()
            if status[0] and not was_fault and self._callback:
                self._callback(status)
            was_fault = status[0]
            time.sleep(0.005)

    def poll_interrupt(self):
        """Read the fault status — equivalent to read_fault(), does not clear.

        Returns:
            tuple: (fault, ocp, uvlo, ots, ilimit), all bool.
        """
        return self.read_fault()
