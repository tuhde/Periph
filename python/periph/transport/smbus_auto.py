import os

# Address validation before bus creation so invalid addresses are caught
# without opening /dev/i2c-N or initialising hardware.
def _validate(addr):
    if not (0x08 <= addr <= 0x77):
        raise ValueError("SMBus address must be in range 0x08-0x77")


try:
    from machine import I2C as _MachineI2C
    from .smbus_micropython import SMBusTransport as _SMBusTransport

    def SMBusTransport(addr, bus=0, pec=False, freq=400_000):
        """Create an SMBus transport for MicroPython.

        Args:
            addr: 7-bit device address (0x08–0x77).
            bus:  I2C bus id (default 0).
            pec:  Enable Packet Error Code checking (default False).
            freq: Bus frequency in Hz (default 400_000).
        """
        _validate(addr)
        return _SMBusTransport(_MachineI2C(bus, freq=freq), addr, pec=pec)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        from .smbus_circuitpython import SMBusTransport as _SMBusTransport

        def SMBusTransport(addr, bus=None, pec=False, freq=400_000):
            """Create an SMBus transport for CircuitPython.

            Args:
                addr: 7-bit device address (0x08–0x77).
                bus:  busio.I2C instance; defaults to board.SCL / board.SDA.
                pec:  Enable Packet Error Code checking (default False).
                freq: Bus frequency in Hz (default 400_000); ignored if bus is provided.
            """
            _validate(addr)
            if bus is None:
                bus = _busio.I2C(_board.SCL, _board.SDA, frequency=freq)
            return _SMBusTransport(bus, addr, pec=pec)

    except ImportError:
        from .smbus_linux import SMBusTransport as _SMBusTransport

        def SMBusTransport(addr, bus=None, pec=False, freq=None):
            """Create an SMBus transport for Linux.

            Args:
                addr: 7-bit device address (0x08–0x77).
                bus:  Bus number (int); defaults to LINUX_I2C_BUS env var, then 1.
                pec:  Enable Packet Error Code checking (default False).
                freq: Ignored (kernel-controlled).
            """
            _validate(addr)
            if bus is None:
                bus = int(os.environ.get('LINUX_I2C_BUS', '1'))
            return _SMBusTransport(bus, addr, pec=pec)
