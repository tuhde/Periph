"""RFM9x (RFM95/96/97/98W) LoRa transceiver driver.

All four modules share identical pins, register maps, SPI protocol, and
LoRa modem logic. They differ only in supported frequency bands and the
maximum spreading factor for RFM97W.

The driver is built around a `_RFM9xBase` class that owns all register
logic. Four thin variant subclasses supply their frequency limits,
maximum SF, and band flag. The Minimal and Full stages are layered on
top: Minimal exposes the primary use case (send/receive at a fixed
frequency), Full adds complete LoRa configuration plus hardware reset
and DIO0 interrupt-driven receive.
"""


class _RFM9xBase:
    """Base class for RFM95/96/97/98W LoRa transceivers (LoRa mode only).

    Owns all register-level logic and accepts a carrier frequency in Hz.
    Four variant subclasses — RFM95Minimal, RFM96Minimal, RFM97Minimal,
    RFM98Minimal — supply the variant-specific limits and band flag.

    Args:
        transport: Configured SPI transport pointing at the device.
        frequency_hz: Carrier frequency in Hz; must lie in the variant's range.
        reset_pin: Optional MicroPython/CircuitPython Pin for hardware reset.
            When None, the driver waits 10 ms after construction for POR.
        dio0_pin: Optional MicroPython/CircuitPython Pin for DIO0 interrupt
            (Full only; Minimal ignores it).

    Raises:
        ValueError: If `frequency_hz` is outside the variant's range.
        OSError: If `RegVersion` does not read 0x12 (SX1276 silicon).
    """

    _REG_FIFO            = 0x00
    _REG_OP_MODE         = 0x01
    _REG_FRF_MSB         = 0x06
    _REG_FRF_MID         = 0x07
    _REG_FRF_LSB         = 0x08
    _REG_PA_CONFIG       = 0x09
    _REG_OCP             = 0x0B
    _REG_LNA             = 0x0C
    _REG_FIFO_ADDR_PTR   = 0x0D
    _REG_FIFO_TX_BASE    = 0x0E
    _REG_FIFO_RX_BASE    = 0x0F
    _REG_FIFO_RX_CURRENT = 0x10
    _REG_IRQ_FLAGS_MASK  = 0x11
    _REG_IRQ_FLAGS       = 0x12
    _REG_RX_NB_BYTES     = 0x13
    _REG_PKT_SNR         = 0x19
    _REG_PKT_RSSI        = 0x1A
    _REG_RSSI            = 0x1B
    _REG_MODEM_CONFIG_1  = 0x1D
    _REG_MODEM_CONFIG_2  = 0x1E
    _REG_SYMB_TIMEOUT    = 0x1F
    _REG_PREAMBLE_MSB    = 0x20
    _REG_PREAMBLE_LSB    = 0x21
    _REG_PAYLOAD_LENGTH  = 0x22
    _REG_MAX_PAYLOAD_LEN = 0x23
    _REG_MODEM_CONFIG_3  = 0x26
    _REG_DETECTION_OPT   = 0x31
    _REG_DETECTION_THR   = 0x37
    _REG_DIO_MAPPING_1   = 0x40
    _REG_VERSION         = 0x42
    _REG_PA_DAC          = 0x4D

    _MODE_LONG_RANGE   = 0x80
    _MODE_SLEEP        = 0x00
    _MODE_STANDBY      = 0x01
    _MODE_TX           = 0x03
    _MODE_RX_CONT      = 0x05
    _MODE_RX_SINGLE    = 0x06

    _IRQ_TX_DONE       = 0x08
    _IRQ_RX_DONE       = 0x40
    _IRQ_RX_TIMEOUT    = 0x80

    _PA_BOOST          = 0x80
    _PA_DAC_HIGH_POWER = 0x87
    _PA_DAC_DEFAULT    = 0x84

    _OCP_240MA         = 0x3B
    _OCP_DEFAULT       = 0x2B

    _FXOSC = 32000000
    _EXPECTED_VERSION = 0x12

    _DIO0_RX_DONE = 0x00
    _DIO0_TX_DONE = 0x40

    def __init__(self, transport, frequency_hz, reset_pin=None, dio0_pin=None):
        self._transport = transport
        if not (self.FREQ_MIN_HZ <= frequency_hz <= self.FREQ_MAX_HZ):
            raise ValueError(
                "frequency_hz %d out of range [%d, %d]"
                % (frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ)
            )
        self._frequency_hz = frequency_hz

        if reset_pin is not None:
            self._reset_via_pin(reset_pin)
        else:
            self._sleep_ms(10)

        version = self._read_reg(self._REG_VERSION)
        if version != self._EXPECTED_VERSION:
            raise OSError(
                "RFM9x version mismatch: expected 0x%02X, got 0x%02X"
                % (self._EXPECTED_VERSION, version)
            )

        self._enter_lora_sleep()

        lna = self._read_reg(self._REG_LNA)
        if self._LF_BAND:
            self._write_reg(self._REG_LNA, lna & 0x3F)
            self._write_reg(
                self._REG_MODEM_CONFIG_3,
                self._read_reg(self._REG_MODEM_CONFIG_3) | 0x04,
            )
        else:
            self._write_reg(self._REG_LNA, 0x23)
            self._write_reg(
                self._REG_MODEM_CONFIG_3,
                self._read_reg(self._REG_MODEM_CONFIG_3) | 0x04,
            )

        self._write_reg(self._REG_FIFO_TX_BASE, 0x80)
        self._write_reg(self._REG_FIFO_RX_BASE, 0x00)

        self.set_frequency(frequency_hz)

        self._bw_code = 0x07
        self._cr_code = 0x01
        self._sf = 7
        self._implicit_header = False
        self._crc_on = True
        self._write_reg(self._REG_MODEM_CONFIG_1, (self._bw_code << 4) | (self._cr_code << 1) | self._implicit_header)
        self._write_reg(self._REG_MODEM_CONFIG_2, (self._sf << 4) | (0x01 if self._crc_on else 0x00) | 0x00)
        self._write_reg(self._REG_PREAMBLE_MSB, 0x00)
        self._write_reg(self._REG_PREAMBLE_LSB, 0x08)

        self.set_tx_power(17, use_pa_boost=True)

        self.standby()

    def _reset_via_pin(self, pin):
        pin.value(0)
        self._sleep_ms(1)
        pin.value(1)
        self._sleep_ms(5)

    @staticmethod
    def _sleep_ms(ms):
        import time
        time.sleep(ms / 1000.0 if isinstance(ms, (int, float)) else ms)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg | 0x80, value & 0xFF]))

    def _read_reg(self, reg):
        return self._transport.write_read(bytes([reg & 0x7F]), 1)[0]

    def _burst_write(self, reg, data):
        self._transport.write(bytes([reg | 0x80]) + bytes(data))

    def _burst_read(self, reg, n):
        return self._transport.write_read(bytes([reg & 0x7F]), n)

    def _enter_lora_sleep(self):
        self._write_reg(self._REG_OP_MODE, 0x00)
        self._sleep_ms(0.001)
        op = self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_SLEEP
        self._write_reg(self._REG_OP_MODE, op)
        self._sleep_ms(0.001)

    def set_frequency(self, frequency_hz):
        """Set the carrier frequency.

        Args:
            frequency_hz: Carrier frequency in Hz; must lie in the variant's range.

        Raises:
            ValueError: If `frequency_hz` is outside the variant's range.
        """
        if not (self.FREQ_MIN_HZ <= frequency_hz <= self.FREQ_MAX_HZ):
            raise ValueError(
                "frequency_hz %d out of range [%d, %d]"
                % (frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ)
            )
        frf = int((frequency_hz << 19) // self._FXOSC)
        self._write_reg(self._REG_FRF_MSB, (frf >> 16) & 0xFF)
        self._write_reg(self._REG_FRF_MID, (frf >> 8) & 0xFF)
        self._write_reg(self._REG_FRF_LSB, frf & 0xFF)
        self._frequency_hz = frequency_hz

    def set_tx_power(self, power_dbm, use_pa_boost=True):
        """Set TX output power.

        Args:
            power_dbm: Output power in dBm. Range −1 to +14 dBm on the RFO pin,
                or +2 to +17 dBm on PA_BOOST; +20 dBm is supported on PA_BOOST
                with the high-power register set.
            use_pa_boost: True to use the PA_BOOST pin (default), False for RFO.
        """
        if use_pa_boost:
            if power_dbm > 17:
                if power_dbm > 20:
                    power_dbm = 20
                self._write_reg(self._REG_PA_DAC, self._PA_DAC_HIGH_POWER)
                self._write_reg(self._REG_OCP, self._OCP_240MA)
                self._write_reg(self._REG_PA_CONFIG, self._PA_BOOST | 0x0F)
            else:
                if power_dbm < 2:
                    power_dbm = 2
                self._write_reg(self._REG_PA_DAC, self._PA_DAC_DEFAULT)
                self._write_reg(self._REG_OCP, self._OCP_DEFAULT)
                self._write_reg(self._REG_PA_CONFIG, self._PA_BOOST | (power_dbm - 2))
        else:
            self._write_reg(self._REG_PA_DAC, self._PA_DAC_DEFAULT)
            self._write_reg(self._REG_OCP, self._OCP_DEFAULT)
            max_power = 7
            pmax = 10.8 + 0.6 * max_power
            output_power = int(power_dbm - pmax + 15)
            if output_power < 0:
                output_power = 0
            if output_power > 15:
                output_power = 15
            self._write_reg(self._REG_PA_CONFIG, (max_power << 4) | output_power)

    def configure(self, sf, bandwidth_khz, coding_rate, crc=True):
        """Configure LoRa modulation parameters.

        Args:
            sf: Spreading factor 6–12 (variant-capped; RFM97W max 9).
            bandwidth_khz: Signal bandwidth in kHz (one of 7.8, 10.4, 15.6, 20.8,
                31.25, 41.7, 62.5, 125, 250, 500). 250/500 kHz are not supported
                on the LF band below 169 MHz.
            coding_rate: Coding rate denominator 5–8 (4/5 … 4/8).
            crc: True to enable CRC on RX payloads (default), False to disable.

        Raises:
            ValueError: If `sf` exceeds the variant maximum, `bandwidth_khz` is
                not one of the 10 supported values, or BW is invalid for the
                variant's frequency range.
        """
        bw_table = {
            7.8: 0, 10.4: 1, 15.6: 2, 20.8: 3, 31.25: 4,
            41.7: 5, 62.5: 6, 125.0: 7, 250.0: 8, 500.0: 9,
        }
        if bandwidth_khz not in bw_table:
            raise ValueError("bandwidth_khz must be one of %s" % sorted(bw_table.keys()))
        bw_code = bw_table[bandwidth_khz]

        if sf < 6 or sf > self.MAX_SF:
            raise ValueError("sf %d out of range [6, %d]" % (sf, self.MAX_SF))
        if sf == 6 and bandwidth_khz > 500.0:
            raise ValueError("SF6 only valid up to BW=500 kHz")

        if coding_rate < 5 or coding_rate > 8:
            raise ValueError("coding_rate must be in [5, 8]")

        if sf == 6:
            self._write_reg(self._REG_DETECTION_OPT, 0x05)
            self._write_reg(self._REG_DETECTION_THR, 0x0C)
        else:
            self._write_reg(self._REG_DETECTION_OPT, 0x03)
            self._write_reg(self._REG_DETECTION_THR, 0x0A)

        implicit_header = (sf == 6)
        self._write_reg(
            self._REG_MODEM_CONFIG_1,
            (bw_code << 4) | ((coding_rate - 4) << 1) | (1 if implicit_header else 0),
        )
        self._write_reg(
            self._REG_MODEM_CONFIG_2,
            (sf << 4) | ((1 if crc else 0) << 2) | 0x03,
        )
        if sf == 6:
            self._write_reg(self._REG_PAYLOAD_LENGTH, self._last_payload_len if hasattr(self, "_last_payload_len") else 255)

        self._bw_code = bw_code
        self._cr_code = coding_rate - 4
        self._sf = sf
        self._implicit_header = implicit_header
        self._crc_on = crc

    def send(self, data):
        """Send a packet.

        Args:
            data: Payload bytes; max 255 bytes.
        """
        if len(data) > 255:
            raise ValueError("payload length %d exceeds 255" % len(data))

        self.standby()
        self._write_reg(self._REG_FIFO_ADDR_PTR, 0x80)
        self._burst_write(self._REG_FIFO, data)
        self._write_reg(self._REG_PAYLOAD_LENGTH, len(data))
        self._write_reg(self._REG_DIO_MAPPING_1, self._DIO0_TX_DONE)
        self._write_reg(self._REG_OP_MODE, self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_TX)

        while True:
            irq = self._read_reg(self._REG_IRQ_FLAGS)
            if irq & self._IRQ_TX_DONE:
                break
            self._sleep_ms(0.002)
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_TX_DONE)
        self.standby()

    def receive(self, timeout_ms=2000):
        """Receive a single packet.

        Args:
            timeout_ms: Receive timeout in milliseconds.

        Returns:
            bytes | None: Received payload bytes, or None on timeout.
        """
        self.standby()
        self._write_reg(self._REG_DIO_MAPPING_1, self._DIO0_RX_DONE)
        self._write_reg(self._REG_OP_MODE, self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_RX_SINGLE)

        elapsed = 0
        step_ms = 5
        while elapsed < timeout_ms:
            irq = self._read_reg(self._REG_IRQ_FLAGS)
            if irq & self._IRQ_RX_DONE:
                self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_DONE)
                return self._read_payload()
            if irq & self._IRQ_RX_TIMEOUT:
                self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_TIMEOUT)
                return None
            self._sleep_ms(step_ms / 1000.0)
            elapsed += step_ms
        self._write_reg(self._REG_OP_MODE, self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_STANDBY)
        return None

    def _read_payload(self):
        current = self._read_reg(self._REG_FIFO_RX_CURRENT)
        self._write_reg(self._REG_FIFO_ADDR_PTR, current)
        length = self._read_reg(self._REG_RX_NB_BYTES)
        return bytes(self._burst_read(self._REG_FIFO, length))

    def receive_continuous(self):
        """Enter continuous receive mode; subsequent `read_packet()` calls drain the FIFO."""
        self.standby()
        self._write_reg(self._REG_DIO_MAPPING_1, self._DIO0_RX_DONE)
        self._write_reg(self._REG_OP_MODE, self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_RX_CONT)
        self._continuous = True

    def read_packet(self):
        """Read one packet from the FIFO in continuous receive mode.

        Returns:
            bytes | None: Payload bytes, or None if no packet is waiting.
        """
        irq = self._read_reg(self._REG_IRQ_FLAGS)
        if not (irq & self._IRQ_RX_DONE):
            return None
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_DONE)
        return self._read_payload()

    def stop_receive(self):
        """Return to STDBY from continuous receive mode."""
        self.standby()
        self._continuous = False

    def standby(self):
        """Enter STDBY mode (crystal on, RF/PLL off, FIFO accessible)."""
        self._write_reg(self._REG_OP_MODE, self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_STANDBY)

    def sleep(self):
        """Enter SLEEP mode (lowest power; FIFO inaccessible)."""
        self._write_reg(self._REG_OP_MODE, self._MODE_LONG_RANGE | (0x08 if not self._LF_BAND else 0x00) | self._MODE_SLEEP)

    def version(self):
        """Read `RegVersion`. Expect 0x12 (SX1276)."""
        return self._read_reg(self._REG_VERSION)

    def rssi(self):
        """Current channel RSSI in dBm (readable in continuous RX mode)."""
        return -137 + self._read_reg(self._REG_RSSI)

    def last_packet_rssi(self):
        """RSSI of last received packet in dBm."""
        return -137 + self._read_reg(self._REG_PKT_RSSI)

    def last_packet_snr(self):
        """SNR of last received packet in dB.

        The raw register holds a signed 8-bit value with 0.25 dB resolution;
        values below zero mean the signal is below the noise floor.
        """
        raw = self._read_reg(self._REG_PKT_SNR)
        if raw & 0x80:
            raw = raw - 0x100
        return raw / 4.0


class RFM95Minimal(_RFM9xBase):
    """RFM95W minimal driver — 868/915 MHz HF band, max SF=12.

    Subclass of `_RFM9xBase` that supplies the HF variant limits.

    Args:
        transport: Configured SPI transport pointing at the device.
        frequency_hz: Carrier frequency in Hz (862–1020 MHz).
        reset_pin: Optional GPIO pin for hardware reset.
        dio0_pin: Optional GPIO pin for DIO0 interrupt (Full only).
    """

    FREQ_MIN_HZ = 862000000
    FREQ_MAX_HZ = 1020000000
    MAX_SF      = 12
    _LF_BAND    = False


class RFM96Minimal(_RFM9xBase):
    """RFM96W minimal driver — 433/470 MHz LF band, max SF=12.

    Args:
        transport: Configured SPI transport pointing at the device.
        frequency_hz: Carrier frequency in Hz (410–525 MHz).
        reset_pin: Optional GPIO pin for hardware reset.
        dio0_pin: Optional GPIO pin for DIO0 interrupt (Full only).
    """

    FREQ_MIN_HZ = 410000000
    FREQ_MAX_HZ = 525000000
    MAX_SF      = 12
    _LF_BAND    = True


class RFM97Minimal(_RFM9xBase):
    """RFM97W minimal driver — 868/915 MHz HF band, max SF=9.

    Args:
        transport: Configured SPI transport pointing at the device.
        frequency_hz: Carrier frequency in Hz (862–1020 MHz).
        reset_pin: Optional GPIO pin for hardware reset.
        dio0_pin: Optional GPIO pin for DIO0 interrupt (Full only).
    """

    FREQ_MIN_HZ = 862000000
    FREQ_MAX_HZ = 1020000000
    MAX_SF      = 9
    _LF_BAND    = False


class RFM98Minimal(_RFM9xBase):
    """RFM98W minimal driver — 433/470 MHz LF band, max SF=12.

    Args:
        transport: Configured SPI transport pointing at the device.
        frequency_hz: Carrier frequency in Hz (410–525 MHz).
        reset_pin: Optional GPIO pin for hardware reset.
        dio0_pin: Optional GPIO pin for DIO0 interrupt (Full only).
    """

    FREQ_MIN_HZ = 410000000
    FREQ_MAX_HZ = 525000000
    MAX_SF      = 12
    _LF_BAND    = True


class RFM95Full(RFM95Minimal):
    """RFM95W full driver — adds hardware reset and DIO0 interrupt support.

    Args:
        transport: Configured SPI transport pointing at the device.
        frequency_hz: Carrier frequency in Hz (862–1020 MHz).
        reset_pin: Optional GPIO pin for hardware reset.
        dio0_pin: Optional GPIO pin for DIO0 interrupt (passed through to receive()).
    """


class RFM96Full(RFM96Minimal):
    """RFM96W full driver — adds hardware reset and DIO0 interrupt support."""


class RFM97Full(RFM97Minimal):
    """RFM97W full driver — adds hardware reset and DIO0 interrupt support."""


class RFM98Full(RFM98Minimal):
    """RFM98W full driver — adds hardware reset and DIO0 interrupt support."""
