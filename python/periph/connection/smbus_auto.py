import os

# Address validation before bus creation so invalid addresses are caught
# without opening /dev/i2c-N or initialising hardware.
def _validate(addr):
    if not (0x08 <= addr <= 0x77):
        raise ValueError("SMBus address must be in range 0x08-0x77")


try:
    from machine import I2C as _MachineI2C
    from .smbus_micropython import SMBusConnection as _SMBusConnection

    def SMBusConnection(addr, bus=0, pec=False, freq=400_000, int_pin=None, en_pin=None):
        """Create an SMBus connection for MicroPython.

        Args:
            addr: 7-bit device address (0x08–0x77).
            bus:  I2C bus id (default 0).
            pec:  Enable Packet Error Code checking (default False).
            freq: Bus frequency in Hz (default 400_000).
            int_pin: Optional InputPin for INT-line delivery.
            en_pin: Optional OutputPin for hardware enable/power control.
        """
        _validate(addr)
        return _SMBusConnection(_MachineI2C(bus, freq=freq), addr, pec=pec,
                                int_pin=int_pin, en_pin=en_pin)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        from .smbus_circuitpython import SMBusConnection as _SMBusConnection

        def SMBusConnection(addr, bus=None, pec=False, freq=400_000, int_pin=None, en_pin=None):
            """Create an SMBus connection for CircuitPython.

            Args:
                addr: 7-bit device address (0x08–0x77).
                bus:  busio.I2C instance; defaults to board.SCL / board.SDA.
                pec:  Enable Packet Error Code checking (default False).
                freq: Bus frequency in Hz (default 400_000); ignored if bus is provided.
                int_pin: Optional InputPin for INT-line delivery.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            _validate(addr)
            if bus is None:
                bus = _busio.I2C(_board.SCL, _board.SDA, frequency=freq)
            return _SMBusConnection(bus, addr, pec=pec, int_pin=int_pin, en_pin=en_pin)

    except ImportError:
        from .smbus_linux import SMBusConnection as _SMBusConnection

        def SMBusConnection(addr, bus=None, pec=False, freq=None, int_pin=None, en_pin=None):
            """Create an SMBus connection for Linux.

            Args:
                addr: 7-bit device address (0x08–0x77).
                bus:  Bus number (int); defaults to LINUX_I2C_BUS env var, then 1.
                pec:  Enable Packet Error Code checking (default False).
                freq: Ignored (kernel-controlled).
                int_pin: Optional InputPin for INT-line delivery.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            _validate(addr)
            if bus is None:
                bus = int(os.environ.get('LINUX_I2C_BUS', '1'))
            return _SMBusConnection(bus, addr, pec=pec, int_pin=int_pin, en_pin=en_pin)
