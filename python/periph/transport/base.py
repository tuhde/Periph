class Transport:
    """Abstract base class for all transport implementations.

    A transport instance represents one device on the bus. Subclasses wrap a
    platform-specific bus and a device address.
    """

    def write(self, data):
        """Send bytes to the device.

        Args:
            data: Bytes to write.

        Raises:
            NotImplementedError: Subclasses must implement this method.
        """
        raise NotImplementedError

    def read(self, n):
        """Read bytes from the device.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.

        Raises:
            NotImplementedError: Subclasses must implement this method.
        """
        raise NotImplementedError

    def write_read(self, data, n):
        """Write then read without releasing the bus between phases.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of bytes to read back.

        Returns:
            bytes: Data received from the device.

        Raises:
            NotImplementedError: Subclasses must implement this method.
        """
        raise NotImplementedError

    def close(self):
        """Release any resources held by this transport. No-op by default."""
        pass
