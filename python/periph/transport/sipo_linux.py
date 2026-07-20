from gpiod.line import Value

try:
    import spidev
except ImportError:
    spidev = None


class SiPoTransport:
    """SiPo (serial-in/parallel-out shift register) transport for Linux.

    Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
    SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Shifts data over
    either a hardware SPI device (spidev) or a bit-banged GPIO pair, and always
    drives RCK — plus SRCLR/G, if configured — as plain gpiod GPIO lines,
    independent of which SPI mode is used.

    Write-only: there is no read() or write_read().

    Exactly one of (bus_num, device_num) [hardware SPI] or (ser_in_offset,
    srck_offset) [software bit-bang] must be given.

    Args:
        gpio_request: gpiod.LineRequest owning rck_offset and any of
            ser_in_offset/srck_offset/srclr_offset/g_offset.
        rck_offset: GPIO line offset for RCK (register clock).
        bus_num: SPI bus number for hardware mode (opens /dev/spidevBUS.DEVICE).
        device_num: SPI chip-select line for hardware mode.
        max_speed_hz: Hardware SPI clock in Hz; default 1 000 000.
        ser_in_offset: GPIO line offset for SER IN in software (bit-bang) mode.
        srck_offset: GPIO line offset for SRCK in software (bit-bang) mode.
        srclr_offset: GPIO line offset for SRCLR; None (default) disables it.
        g_offset: GPIO line offset for G (output enable); None (default) disables it.
    """

    def __init__(self, gpio_request, rck_offset, bus_num=None, device_num=None,
                 max_speed_hz=1_000_000, ser_in_offset=None, srck_offset=None,
                 srclr_offset=None, g_offset=None):
        hardware = bus_num is not None
        software = ser_in_offset is not None
        if hardware == software:
            raise ValueError(
                'specify exactly one of (bus_num, device_num) or (ser_in_offset, srck_offset)')

        self._gpio = gpio_request
        self._rck = rck_offset
        self._srclr = srclr_offset
        self._g = g_offset
        self._ser_in = ser_in_offset
        self._srck = srck_offset

        if hardware:
            self._spi = spidev.SpiDev()
            self._spi.open(bus_num, device_num)
            self._spi.mode = 0
            self._spi.max_speed_hz = max_speed_hz
        else:
            self._spi = None

        self._gpio.set_value(self._rck, Value.INACTIVE)
        if self._srclr is not None:
            self._gpio.set_value(self._srclr, Value.ACTIVE)
        if self._g is not None:
            self._gpio.set_value(self._g, Value.INACTIVE)

    def write(self, data):
        """Shift data out MSB-first, then latch it into the output register.

        In hardware mode this transfers data over spidev; in software mode it
        bit-bangs SER IN/SRCK. Either way, RCK is then pulsed HIGH then LOW to
        latch the shifted data into the storage register that drives the outputs.

        Args:
            data: Bytes to shift out, one byte per cascaded device.
        """
        if self._spi is not None:
            self._spi.writebytes2(list(data))
        else:
            for byte in data:
                for bit in range(7, -1, -1):
                    value = Value.ACTIVE if (byte >> bit) & 1 else Value.INACTIVE
                    self._gpio.set_value(self._ser_in, value)
                    self._gpio.set_value(self._srck, Value.ACTIVE)
                    self._gpio.set_value(self._srck, Value.INACTIVE)
        self._gpio.set_value(self._rck, Value.ACTIVE)
        self._gpio.set_value(self._rck, Value.INACTIVE)

    def clear(self):
        """Pulse SRCLR LOW then HIGH to clear the shift register.

        The storage register (and therefore the outputs) is unaffected until
        the next write().

        Raises:
            RuntimeError: If srclr_offset was not configured.
        """
        if self._srclr is None:
            raise RuntimeError('SRCLR not configured')
        self._gpio.set_value(self._srclr, Value.INACTIVE)
        self._gpio.set_value(self._srclr, Value.ACTIVE)

    def set_output_enable(self, enabled):
        """Drive G LOW (enabled) or HIGH (disabled).

        Args:
            enabled: True drives G LOW, letting the storage register drive the
                outputs. False drives G HIGH, forcing every output off without
                disturbing the storage register's contents.

        Raises:
            RuntimeError: If g_offset was not configured.
        """
        if self._g is None:
            raise RuntimeError('G not configured')
        self._gpio.set_value(self._g, Value.INACTIVE if enabled else Value.ACTIVE)

    def close(self):
        """Release the spidev device (if opened) and the GPIO lines."""
        if self._spi is not None:
            self._spi.close()
        self._gpio.release()
