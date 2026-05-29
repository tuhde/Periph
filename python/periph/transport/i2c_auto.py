import os

try:
    from machine import I2C as _MachineI2C
    from .i2c_micropython import I2CTransport as _I2CTransport

    def I2CTransport(addr, bus=0, freq=400_000):
        """Create an I²C transport for MicroPython.

        Args:
            addr: 7-bit device address.
            bus:  I2C bus id (default 0).
            freq: Bus frequency in Hz (default 400_000).
        """
        return _I2CTransport(_MachineI2C(bus, freq=freq), addr)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        from .i2c_circuitpython import I2CTransport as _I2CTransport

        def I2CTransport(addr, bus=None, freq=400_000):
            """Create an I²C transport for CircuitPython.

            Args:
                addr: 7-bit device address.
                bus:  busio.I2C instance; defaults to board.SCL / board.SDA.
                freq: Bus frequency in Hz (default 400_000); ignored if bus is provided.
            """
            if bus is None:
                bus = _busio.I2C(_board.SCL, _board.SDA, frequency=freq)
            return _I2CTransport(bus, addr)

    except ImportError:
        from .i2c_linux import I2CTransport as _I2CTransport

        def I2CTransport(addr, bus=None, freq=None):
            """Create an I²C transport for Linux.

            Args:
                addr: 7-bit device address.
                bus:  Bus number (int); defaults to LINUX_I2C_BUS env var, then 1.
                freq: Ignored (kernel-controlled).
            """
            if bus is None:
                bus = int(os.environ.get('LINUX_I2C_BUS', '1'))
            return _I2CTransport(bus, addr)
