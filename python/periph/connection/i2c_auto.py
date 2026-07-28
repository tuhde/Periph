import os

try:
    from machine import I2C as _MachineI2C
    from .i2c_micropython import I2CConnection as _I2CConnection

    def I2CConnection(addr, bus=0, freq=400_000, int_pin=None, en_pin=None):
        """Create an I²C connection for MicroPython.

        Args:
            addr: 7-bit device address.
            bus:  I2C bus id (default 0).
            freq: Bus frequency in Hz (default 400_000).
            int_pin: Optional InputPin for INT-line delivery.
            en_pin: Optional OutputPin for hardware enable/power control.
        """
        return _I2CConnection(_MachineI2C(bus, freq=freq), addr, int_pin=int_pin, en_pin=en_pin)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        from .i2c_circuitpython import I2CConnection as _I2CConnection

        def I2CConnection(addr, bus=None, freq=400_000, int_pin=None, en_pin=None):
            """Create an I²C connection for CircuitPython.

            Args:
                addr: 7-bit device address.
                bus:  busio.I2C instance; defaults to board.SCL / board.SDA.
                freq: Bus frequency in Hz (default 400_000); ignored if bus is provided.
                int_pin: Optional InputPin for INT-line delivery.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if bus is None:
                bus = _busio.I2C(_board.SCL, _board.SDA, frequency=freq)
            return _I2CConnection(bus, addr, int_pin=int_pin, en_pin=en_pin)

    except ImportError:
        from .i2c_linux import I2CConnection as _I2CConnection

        def I2CConnection(addr, bus=None, freq=None, int_pin=None, en_pin=None):
            """Create an I²C connection for Linux.

            Args:
                addr: 7-bit device address.
                bus:  Bus number (int); defaults to LINUX_I2C_BUS env var, then 1.
                freq: Ignored (kernel-controlled).
                int_pin: Optional InputPin for INT-line delivery.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if bus is None:
                bus = int(os.environ.get('LINUX_I2C_BUS', '1'))
            return _I2CConnection(bus, addr, int_pin=int_pin, en_pin=en_pin)
