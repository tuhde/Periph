import struct
import time


class _RFM9xBase:
    """RFM9x LoRa transceiver base class — all register logic.

    Subclasses supply variant-specific frequency limits, max SF, and band flag.
    Validates frequency_hz against these limits at construction.

    Args:
        transport: Configured SPI transport.
        frequency_hz: Carrier frequency in Hz (validated against variant range).
    """

    _REG_FIFO             = 0x00
    _REG_OP_MODE          = 0x01
    _REG_FRF_MSB          = 0x06
    _REG_FRF_MID          = 0x07
    _REG_FRF_LSB          = 0x08
    _REG_PA_CONFIG        = 0x09
    _REG_OCP              = 0x0B
    _REG_LNA              = 0x0C
    _REG_FIFO_ADDR_PTR    = 0x0D
    _REG_FIFO_TX_BASE_ADDR = 0x0E
    _REG_FIFO_RX_BASE_ADDR = 0x0F
    _REG_FIFO_RX_CURRENT_ADDR = 0x10
    _REG_IRQ_FLAGS_MASK   = 0x11
    _REG_IRQ_FLAGS       = 0x12
    _REG_RX_NB_BYTES     = 0x13
    _REG_MODEM_STAT       = 0x18
    _REG_PKT_SNR_VALUE   = 0x19
    _REG_PKT_RSSI_VALUE  = 0x1A
    _REG_RSSI_VALUE      = 0x1B
    _REG_HOP_CHANNEL     = 0x1C
    _REG_MODEM_CONFIG1   = 0x1D
    _REG_MODEM_CONFIG2   = 0x1E
    _REG_SYMB_TIMEOUT_LSB = 0x1F
    _REG_PREAMBLE_MSB    = 0x20
    _REG_PREAMBLE_LSB    = 0x21
    _REG_PAYLOAD_LENGTH  = 0x22
    _REG_MAX_PAYLOAD_LENGTH = 0x23
    _REG_MODEM_CONFIG3   = 0x26
    _REG_VERSION          = 0x42
    _REG_PA_DAC           = 0x4D

    _FXOSC = 32_000_000

    _MODE_SLEEP        = 0
    _MODE_STDBY        = 1
    _MODE_FSTX         = 2
    _MODE_TX           = 3
    _MODE_FSRX         = 4
    _MODE_RXCONTINUOUS = 5
    _MODE_RXSINGLE     = 6
    _MODE_CAD          = 7

    _IRQ_TX_DONE       = 0x08
    _IRQ_RX_DONE       = 0x40
    _IRQ_RX_TIMEOUT    = 0x80

    _BW_MAP = {
        7.8:   0x00,
        10.4:  0x01,
        15.6:  0x02,
        20.8:  0x03,
        31.25: 0x04,
        41.7:  0x05,
        62.5:  0x06,
        125.0: 0x07,
        250.0: 0x08,
        500.0: 0x09,
    }

    _CR_MAP = {5: 1, 6: 2, 7: 3, 8: 4}

    def __init__(self, transport, frequency_hz):
        if frequency_hz < self.FREQ_MIN_HZ or frequency_hz > self.FREQ_MAX_HZ:
            raise ValueError(
                'frequency {} Hz out of range [{}, {}] for {}'.format(
                    frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ,
                    self.__class__.__name__
                )
            )
        self._transport = transport
        self._frequency_hz = frequency_hz
        self._sf = 7
        self._bw = 125_000
        self._cr = 5
        self._crc = True
        self._init()

    def _write_reg(self, reg, value):
        addr = (reg & 0x7F) | 0x80
        self._transport.write(bytes([addr, (value >> 8) & 0xFF]))
        self._transport.write(bytes([value & 0xFF]))

    def _write_reg_burst(self, reg, data):
        addr = (reg & 0x7F) | 0x80
        self._transport.write(bytes([addr]) + bytes(data))

    def _read_reg(self, reg):
        addr = reg & 0x7F
        result = self._transport.write_read(bytes([addr]), 2)
        return (result[0] << 8) | result[1]

    def _set_mode(self, mode):
        current = self._read_reg(self._REG_OP_MODE)
        new_val = (current & 0xF8) | (mode & 0x07)
        self._write_reg(self._REG_OP_MODE, new_val)

    def _init(self):
        self._transport.write(bytes([self._REG_OP_MODE, 0x00]))
        self._transport.write(bytes([self._REG_OP_MODE, 0x80]))
        self._set_mode(self._MODE_SLEEP)
        op_mode = 0x80 | (0x01 if self._LF_BAND else 0x00)
        self._write_reg(self._REG_OP_MODE, op_mode)
        self._write_reg(self._REG_FIFO_TX_BASE_ADDR, 0x80)
        self._write_reg(self._REG_FIFO_RX_BASE_ADDR, 0x00)
        if not self._LF_BAND:
            self._write_reg(self._REG_LNA, 0x23)
        self._write_reg(self._REG_MODEM_CONFIG3, 0x04)
        self._set_frequency(self._frequency_hz)
        self._write_reg(self._REG_MODEM_CONFIG1, 0x72 | 0x00)
        self._write_reg(self._REG_MODEM_CONFIG2, (self._sf << 4) | (0x04 if self._crc else 0x00))
        self._set_mode(self._MODE_STDBY)

    def _set_frequency(self, frequency_hz):
        frf = round(frequency_hz * 524_288 / self._FXOSC)
        self._write_reg(self._REG_FRF_MSB, (frf >> 16) & 0xFF)
        self._write_reg(self._REG_FRF_MID, (frf >> 8) & 0xFF)
        self._write_reg(self._REG_FRF_LSB, frf & 0xFF)

    def _poll_irq(self, irq_mask, timeout_ms):
        deadline = time.ticks_add(time.ticks_ms(), timeout_ms)
        while time.ticks_diff(deadline, time.ticks_ms()) > 0:
            flags = self._read_reg(self._REG_IRQ_FLAGS)
            if flags & irq_mask:
                return True
        return False

    def send(self, data):
        """Transmit a packet.

        Args:
            data: Bytes to transmit (max 255 bytes).
        """
        self._set_mode(self._MODE_STDBY)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        self._write_reg(self._REG_FIFO_ADDR_PTR, 0x80)
        payload = bytes(data)
        if len(payload) > 255:
            raise ValueError('payload exceeds 255 bytes')
        self._write_reg_burst(self._REG_FIFO, [len(payload)] + list(payload))
        self._set_mode(self._MODE_TX)
        self._poll_irq(self._IRQ_TX_DONE, 10000)
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_TX_DONE)
        self._set_mode(self._MODE_STDBY)

    def receive(self, timeout_ms=2000):
        """Receive a packet (single shot).

        Args:
            timeout_ms: Timeout in milliseconds (default 2000).

        Returns:
            bytes: Received payload, or None on timeout.
        """
        self._set_mode(self._MODE_STDBY)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        self._write_reg(self._REG_FIFO_RX_CURRENT_ADDR, 0x00)
        self._set_mode(self._MODE_RXSINGLE)
        if not self._poll_irq(self._IRQ_TX_DONE | self._IRQ_RX_TIMEOUT, timeout_ms):
            self._set_mode(self._MODE_STDBY)
            return None
        flags = self._read_reg(self._REG_IRQ_FLAGS)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        if flags & self._IRQ_RX_TIMEOUT:
            self._set_mode(self._MODE_STDBY)
            return None
        if not (flags & self._IRQ_RX_DONE):
            return None
        nb_bytes = self._read_reg(self._REG_RX_NB_BYTES)
        self._write_reg(self._REG_FIFO_ADDR_PTR, self._read_reg(self._REG_FIFO_RX_CURRENT_ADDR))
        fifo_data = self._transport.write_read(bytes([self._REG_FIFO]), nb_bytes)
        self._set_mode(self._MODE_STDBY)
        return bytes(fifo_data)

    def version(self):
        """Read the silicon revision.

        Returns:
            int: RegVersion value (expect 0x12 for SX1276).
        """
        return self._read_reg(self._REG_VERSION)

    def standby(self):
        """Enter STANDBY mode."""
        self._set_mode(self._MODE_STDBY)

    def sleep(self):
        """Enter SLEEP mode."""
        self._set_mode(self._MODE_SLEEP)


class RFM95Minimal(_RFM9xBase):
    """RFM95W — 868 / 915 MHz HF band, max SF 12."""

    FREQ_MIN_HZ = 862_000_000
    FREQ_MAX_HZ = 1_020_000_000
    MAX_SF = 12
    _LF_BAND = False


class RFM96Minimal(_RFM9xBase):
    """RFM96W — 433 / 470 MHz LF band, max SF 12."""

    FREQ_MIN_HZ = 410_000_000
    FREQ_MAX_HZ = 525_000_000
    MAX_SF = 12
    _LF_BAND = True


class RFM97Minimal(_RFM9xBase):
    """RFM97W — 868 / 915 MHz HF band, max SF 9."""

    FREQ_MIN_HZ = 862_000_000
    FREQ_MAX_HZ = 1_020_000_000
    MAX_SF = 9
    _LF_BAND = False


class RFM98Minimal(_RFM9xBase):
    """RFM98W — 433 / 470 MHz LF band, max SF 12."""

    FREQ_MIN_HZ = 410_000_000
    FREQ_MAX_HZ = 525_000_000
    MAX_SF = 12
    _LF_BAND = True


class RFM95Full(RFM95Minimal):
    """RFM95W full interface — extends RFM95Minimal with configuration and GPIO support.

    Args:
        transport: Configured SPI transport.
        frequency_hz: Carrier frequency in Hz.
        reset_pin: Optional machine.Pin for hardware reset.
        dio0_pin: Optional machine.Pin for DIO0 interrupt.
    """

    def __init__(self, transport, frequency_hz, reset_pin=None, dio0_pin=None):
        self._reset_pin = reset_pin
        self._dio0_pin = dio0_pin
        if reset_pin:
            reset_pin.value(0)
            time.sleep_us(100)
            reset_pin.value(1)
            time.sleep_ms(5)
        super().__init__(transport, frequency_hz)

    def reset(self):
        """Hardware reset via NRESET pin."""
        if self._reset_pin is None:
            raise RuntimeError('reset_pin not configured')
        self._reset_pin.value(0)
        time.sleep_us(100)
        self._reset_pin.value(1)
        time.sleep_ms(5)
        self._init()

    def configure(self, sf, bandwidth_khz, coding_rate, crc=True):
        """Configure modem parameters.

        Args:
            sf: Spreading factor 6–12 (capped at variant MAX_SF).
            bandwidth_khz: Signal bandwidth in kHz (7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500).
            coding_rate: Coding rate denominator 5–8 (4/5, 4/6, 4/7, 4/8).
            crc: Enable CRC generation and verification.
        """
        if sf < 6 or sf > self.MAX_SF:
            raise ValueError('sf {} out of range 6-{}'.format(sf, self.MAX_SF))
        if self._LF_BAND and bandwidth_khz > 62.5:
            raise ValueError('bandwidth {} kHz not supported on LF band'.format(bandwidth_khz))
        if bandwidth_khz not in self._BW_MAP:
            raise ValueError('unsupported bandwidth {}'.format(bandwidth_khz))
        if coding_rate not in self._CR_MAP:
            raise ValueError('coding_rate {} must be 5-8'.format(coding_rate))
        self._sf = sf
        self._bw = bandwidth_khz * 1000
        self._cr = coding_rate
        self._crc = crc
        bw_bits = self._BW_MAP[bandwidth_khz]
        cr_bits = self._CR_MAP[coding_rate]
        self._write_reg(self._REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1) | 0x00)
        implicit = 1 if sf == 6 else 0
        self._write_reg(self._REG_MODEM_CONFIG2, (sf << 4) | (0x04 if crc else 0x00) | (implicit << 0))
        if sf == 6:
            self._write_reg(0x31, 0x05)
            self._write_reg(0x37, 0x0C)

    def set_frequency(self, frequency_hz):
        """Change carrier frequency.

        Args:
            frequency_hz: New carrier frequency in Hz.
        """
        if frequency_hz < self.FREQ_MIN_HZ or frequency_hz > self.FREQ_MAX_HZ:
            raise ValueError('frequency {} Hz out of range [{}, {}]'.format(
                frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ))
        self._frequency_hz = frequency_hz
        self._set_frequency(frequency_hz)

    def set_tx_power(self, power_dbm, use_pa_boost=True):
        """Set TX output power.

        Args:
            power_dbm: Output power in dBm.
            use_pa_boost: Use PA_BOOST pin (max +20 dBm) if True, RFO pin (max +14 dBm) if False.
        """
        if use_pa_boost:
            if power_dbm < 2 or power_dbm > 20:
                raise ValueError('PA_BOOST power {} dBm out of range 2-20'.format(power_dbm))
            if power_dbm > 17:
                self._write_reg(self._REG_PA_DAC, 0x87)
                self._write_reg(self._REG_OCP, 0x3B)
            else:
                self._write_reg(self._REG_PA_DAC, 0x84)
                self._write_reg(self._REG_OCP, 0x2B)
            output_power = power_dbm - 2
            pa_config = 0x80 | (output_power & 0x0F)
        else:
            if power_dbm < -1 or power_dbm > 14:
                raise ValueError('RFO power {} dBm out of range -1-14'.format(power_dbm))
            self._write_reg(self._REG_PA_DAC, 0x84)
            self._write_reg(self._REG_OCP, 0x2B)
            max_power = 7
            pmax = 10.8 + 0.6 * max_power
            output_power = int(power_dbm - pmax + 15)
            output_power = max(0, min(15, output_power))
            pa_config = (max_power << 4) | (output_power & 0x0F)
        self._write_reg(self._REG_PA_CONFIG, pa_config)

    def receive_continuous(self):
        """Enter continuous receive mode."""
        self._set_mode(self._MODE_STDBY)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        self._set_mode(self._MODE_RXCONTINUOUS)

    def read_packet(self):
        """Read one packet from FIFO in continuous mode.

        Returns:
            bytes: Received payload, or None if no packet available.
        """
        flags = self._read_reg(self._REG_IRQ_FLAGS)
        if not (flags & self._IRQ_RX_DONE):
            return None
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_DONE)
        nb_bytes = self._read_reg(self._REG_RX_NB_BYTES)
        self._write_reg(self._REG_FIFO_ADDR_PTR, self._read_reg(self._REG_FIFO_RX_CURRENT_ADDR))
        fifo_data = self._transport.write_read(bytes([self._REG_FIFO]), nb_bytes)
        return bytes(fifo_data)

    def stop_receive(self):
        """Return to STANDBY from RXCONTINUOUS."""
        self._set_mode(self._MODE_STDBY)

    def rssi(self):
        """Read current channel RSSI.

        Returns:
            float: RSSI in dBm.
        """
        rssi_raw = self._read_reg(self._REG_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_rssi(self):
        """Read RSSI of last received packet.

        Returns:
            float: Packet RSSI in dBm.
        """
        rssi_raw = self._read_reg(self._REG_PKT_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_snr(self):
        """Read SNR of last received packet.

        Returns:
            float: Packet SNR in dB.
        """
        snr_raw = self._read_reg(self._REG_PKT_SNR_VALUE)
        return (snr_raw if snr_raw < 128 else snr_raw - 256) / 4.0


class RFM96Full(RFM96Minimal):
    def __init__(self, transport, frequency_hz, reset_pin=None, dio0_pin=None):
        self._reset_pin = reset_pin
        self._dio0_pin = dio0_pin
        if reset_pin:
            reset_pin.value(0)
            time.sleep_us(100)
            reset_pin.value(1)
            time.sleep_ms(5)
        super().__init__(transport, frequency_hz)

    def reset(self):
        if self._reset_pin is None:
            raise RuntimeError('reset_pin not configured')
        self._reset_pin.value(0)
        time.sleep_us(100)
        self._reset_pin.value(1)
        time.sleep_ms(5)
        self._init()

    def configure(self, sf, bandwidth_khz, coding_rate, crc=True):
        if sf < 6 or sf > self.MAX_SF:
            raise ValueError('sf {} out of range 6-{}'.format(sf, self.MAX_SF))
        if self._LF_BAND and bandwidth_khz > 62.5:
            raise ValueError('bandwidth {} kHz not supported on LF band'.format(bandwidth_khz))
        if bandwidth_khz not in self._BW_MAP:
            raise ValueError('unsupported bandwidth {}'.format(bandwidth_khz))
        if coding_rate not in self._CR_MAP:
            raise ValueError('coding_rate {} must be 5-8'.format(coding_rate))
        self._sf = sf
        self._bw = bandwidth_khz * 1000
        self._cr = coding_rate
        self._crc = crc
        bw_bits = self._BW_MAP[bandwidth_khz]
        cr_bits = self._CR_MAP[coding_rate]
        self._write_reg(self._REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1) | 0x00)
        implicit = 1 if sf == 6 else 0
        self._write_reg(self._REG_MODEM_CONFIG2, (sf << 4) | (0x04 if crc else 0x00) | (implicit << 0))
        if sf == 6:
            self._write_reg(0x31, 0x05)
            self._write_reg(0x37, 0x0C)

    def set_frequency(self, frequency_hz):
        if frequency_hz < self.FREQ_MIN_HZ or frequency_hz > self.FREQ_MAX_HZ:
            raise ValueError('frequency {} Hz out of range [{}, {}]'.format(
                frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ))
        self._frequency_hz = frequency_hz
        self._set_frequency(frequency_hz)

    def set_tx_power(self, power_dbm, use_pa_boost=True):
        if use_pa_boost:
            if power_dbm < 2 or power_dbm > 20:
                raise ValueError('PA_BOOST power {} dBm out of range 2-20'.format(power_dbm))
            if power_dbm > 17:
                self._write_reg(self._REG_PA_DAC, 0x87)
                self._write_reg(self._REG_OCP, 0x3B)
            else:
                self._write_reg(self._REG_PA_DAC, 0x84)
                self._write_reg(self._REG_OCP, 0x2B)
            output_power = power_dbm - 2
            pa_config = 0x80 | (output_power & 0x0F)
        else:
            if power_dbm < -1 or power_dbm > 14:
                raise ValueError('RFO power {} dBm out of range -1-14'.format(power_dbm))
            self._write_reg(self._REG_PA_DAC, 0x84)
            self._write_reg(self._REG_OCP, 0x2B)
            max_power = 7
            pmax = 10.8 + 0.6 * max_power
            output_power = int(power_dbm - pmax + 15)
            output_power = max(0, min(15, output_power))
            pa_config = (max_power << 4) | (output_power & 0x0F)
        self._write_reg(self._REG_PA_CONFIG, pa_config)

    def receive_continuous(self):
        self._set_mode(self._MODE_STDBY)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        self._set_mode(self._MODE_RXCONTINUOUS)

    def read_packet(self):
        flags = self._read_reg(self._REG_IRQ_FLAGS)
        if not (flags & self._IRQ_RX_DONE):
            return None
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_DONE)
        nb_bytes = self._read_reg(self._REG_RX_NB_BYTES)
        self._write_reg(self._REG_FIFO_ADDR_PTR, self._read_reg(self._REG_FIFO_RX_CURRENT_ADDR))
        fifo_data = self._transport.write_read(bytes([self._REG_FIFO]), nb_bytes)
        return bytes(fifo_data)

    def stop_receive(self):
        self._set_mode(self._MODE_STDBY)

    def rssi(self):
        rssi_raw = self._read_reg(self._REG_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_rssi(self):
        rssi_raw = self._read_reg(self._REG_PKT_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_snr(self):
        snr_raw = self._read_reg(self._REG_PKT_SNR_VALUE)
        return (snr_raw if snr_raw < 128 else snr_raw - 256) / 4.0


class RFM97Full(RFM97Minimal):
    def __init__(self, transport, frequency_hz, reset_pin=None, dio0_pin=None):
        self._reset_pin = reset_pin
        self._dio0_pin = dio0_pin
        if reset_pin:
            reset_pin.value(0)
            time.sleep_us(100)
            reset_pin.value(1)
            time.sleep_ms(5)
        super().__init__(transport, frequency_hz)

    def reset(self):
        if self._reset_pin is None:
            raise RuntimeError('reset_pin not configured')
        self._reset_pin.value(0)
        time.sleep_us(100)
        self._reset_pin.value(1)
        time.sleep_ms(5)
        self._init()

    def configure(self, sf, bandwidth_khz, coding_rate, crc=True):
        if sf < 6 or sf > self.MAX_SF:
            raise ValueError('sf {} out of range 6-{}'.format(sf, self.MAX_SF))
        if self._LF_BAND and bandwidth_khz > 62.5:
            raise ValueError('bandwidth {} kHz not supported on LF band'.format(bandwidth_khz))
        if bandwidth_khz not in self._BW_MAP:
            raise ValueError('unsupported bandwidth {}'.format(bandwidth_khz))
        if coding_rate not in self._CR_MAP:
            raise ValueError('coding_rate {} must be 5-8'.format(coding_rate))
        self._sf = sf
        self._bw = bandwidth_khz * 1000
        self._cr = coding_rate
        self._crc = crc
        bw_bits = self._BW_MAP[bandwidth_khz]
        cr_bits = self._CR_MAP[coding_rate]
        self._write_reg(self._REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1) | 0x00)
        implicit = 1 if sf == 6 else 0
        self._write_reg(self._REG_MODEM_CONFIG2, (sf << 4) | (0x04 if crc else 0x00) | (implicit << 0))
        if sf == 6:
            self._write_reg(0x31, 0x05)
            self._write_reg(0x37, 0x0C)

    def set_frequency(self, frequency_hz):
        if frequency_hz < self.FREQ_MIN_HZ or frequency_hz > self.FREQ_MAX_HZ:
            raise ValueError('frequency {} Hz out of range [{}, {}]'.format(
                frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ))
        self._frequency_hz = frequency_hz
        self._set_frequency(frequency_hz)

    def set_tx_power(self, power_dbm, use_pa_boost=True):
        if use_pa_boost:
            if power_dbm < 2 or power_dbm > 20:
                raise ValueError('PA_BOOST power {} dBm out of range 2-20'.format(power_dbm))
            if power_dbm > 17:
                self._write_reg(self._REG_PA_DAC, 0x87)
                self._write_reg(self._REG_OCP, 0x3B)
            else:
                self._write_reg(self._REG_PA_DAC, 0x84)
                self._write_reg(self._REG_OCP, 0x2B)
            output_power = power_dbm - 2
            pa_config = 0x80 | (output_power & 0x0F)
        else:
            if power_dbm < -1 or power_dbm > 14:
                raise ValueError('RFO power {} dBm out of range -1-14'.format(power_dbm))
            self._write_reg(self._REG_PA_DAC, 0x84)
            self._write_reg(self._REG_OCP, 0x2B)
            max_power = 7
            pmax = 10.8 + 0.6 * max_power
            output_power = int(power_dbm - pmax + 15)
            output_power = max(0, min(15, output_power))
            pa_config = (max_power << 4) | (output_power & 0x0F)
        self._write_reg(self._REG_PA_CONFIG, pa_config)

    def receive_continuous(self):
        self._set_mode(self._MODE_STDBY)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        self._set_mode(self._MODE_RXCONTINUOUS)

    def read_packet(self):
        flags = self._read_reg(self._REG_IRQ_FLAGS)
        if not (flags & self._IRQ_RX_DONE):
            return None
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_DONE)
        nb_bytes = self._read_reg(self._REG_RX_NB_BYTES)
        self._write_reg(self._REG_FIFO_ADDR_PTR, self._read_reg(self._REG_FIFO_RX_CURRENT_ADDR))
        fifo_data = self._transport.write_read(bytes([self._REG_FIFO]), nb_bytes)
        return bytes(fifo_data)

    def stop_receive(self):
        self._set_mode(self._MODE_STDBY)

    def rssi(self):
        rssi_raw = self._read_reg(self._REG_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_rssi(self):
        rssi_raw = self._read_reg(self._REG_PKT_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_snr(self):
        snr_raw = self._read_reg(self._REG_PKT_SNR_VALUE)
        return (snr_raw if snr_raw < 128 else snr_raw - 256) / 4.0


class RFM98Full(RFM98Minimal):
    def __init__(self, transport, frequency_hz, reset_pin=None, dio0_pin=None):
        self._reset_pin = reset_pin
        self._dio0_pin = dio0_pin
        if reset_pin:
            reset_pin.value(0)
            time.sleep_us(100)
            reset_pin.value(1)
            time.sleep_ms(5)
        super().__init__(transport, frequency_hz)

    def reset(self):
        if self._reset_pin is None:
            raise RuntimeError('reset_pin not configured')
        self._reset_pin.value(0)
        time.sleep_us(100)
        self._reset_pin.value(1)
        time.sleep_ms(5)
        self._init()

    def configure(self, sf, bandwidth_khz, coding_rate, crc=True):
        if sf < 6 or sf > self.MAX_SF:
            raise ValueError('sf {} out of range 6-{}'.format(sf, self.MAX_SF))
        if self._LF_BAND and bandwidth_khz > 62.5:
            raise ValueError('bandwidth {} kHz not supported on LF band'.format(bandwidth_khz))
        if bandwidth_khz not in self._BW_MAP:
            raise ValueError('unsupported bandwidth {}'.format(bandwidth_khz))
        if coding_rate not in self._CR_MAP:
            raise ValueError('coding_rate {} must be 5-8'.format(coding_rate))
        self._sf = sf
        self._bw = bandwidth_khz * 1000
        self._cr = coding_rate
        self._crc = crc
        bw_bits = self._BW_MAP[bandwidth_khz]
        cr_bits = self._CR_MAP[coding_rate]
        self._write_reg(self._REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1) | 0x00)
        implicit = 1 if sf == 6 else 0
        self._write_reg(self._REG_MODEM_CONFIG2, (sf << 4) | (0x04 if crc else 0x00) | (implicit << 0))
        if sf == 6:
            self._write_reg(0x31, 0x05)
            self._write_reg(0x37, 0x0C)

    def set_frequency(self, frequency_hz):
        if frequency_hz < self.FREQ_MIN_HZ or frequency_hz > self.FREQ_MAX_HZ:
            raise ValueError('frequency {} Hz out of range [{}, {}]'.format(
                frequency_hz, self.FREQ_MIN_HZ, self.FREQ_MAX_HZ))
        self._frequency_hz = frequency_hz
        self._set_frequency(frequency_hz)

    def set_tx_power(self, power_dbm, use_pa_boost=True):
        if use_pa_boost:
            if power_dbm < 2 or power_dbm > 20:
                raise ValueError('PA_BOOST power {} dBm out of range 2-20'.format(power_dbm))
            if power_dbm > 17:
                self._write_reg(self._REG_PA_DAC, 0x87)
                self._write_reg(self._REG_OCP, 0x3B)
            else:
                self._write_reg(self._REG_PA_DAC, 0x84)
                self._write_reg(self._REG_OCP, 0x2B)
            output_power = power_dbm - 2
            pa_config = 0x80 | (output_power & 0x0F)
        else:
            if power_dbm < -1 or power_dbm > 14:
                raise ValueError('RFO power {} dBm out of range -1-14'.format(power_dbm))
            self._write_reg(self._REG_PA_DAC, 0x84)
            self._write_reg(self._REG_OCP, 0x2B)
            max_power = 7
            pmax = 10.8 + 0.6 * max_power
            output_power = int(power_dbm - pmax + 15)
            output_power = max(0, min(15, output_power))
            pa_config = (max_power << 4) | (output_power & 0x0F)
        self._write_reg(self._REG_PA_CONFIG, pa_config)

    def receive_continuous(self):
        self._set_mode(self._MODE_STDBY)
        self._write_reg(self._REG_IRQ_FLAGS, 0xFF)
        self._set_mode(self._MODE_RXCONTINUOUS)

    def read_packet(self):
        flags = self._read_reg(self._REG_IRQ_FLAGS)
        if not (flags & self._IRQ_RX_DONE):
            return None
        self._write_reg(self._REG_IRQ_FLAGS, self._IRQ_RX_DONE)
        nb_bytes = self._read_reg(self._REG_RX_NB_BYTES)
        self._write_reg(self._REG_FIFO_ADDR_PTR, self._read_reg(self._REG_FIFO_RX_CURRENT_ADDR))
        fifo_data = self._transport.write_read(bytes([self._REG_FIFO]), nb_bytes)
        return bytes(fifo_data)

    def stop_receive(self):
        self._set_mode(self._MODE_STDBY)

    def rssi(self):
        rssi_raw = self._read_reg(self._REG_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_rssi(self):
        rssi_raw = self._read_reg(self._REG_PKT_RSSI_VALUE)
        return -137 + rssi_raw * 0.5

    def last_packet_snr(self):
        snr_raw = self._read_reg(self._REG_PKT_SNR_VALUE)
        return (snr_raw if snr_raw < 128 else snr_raw - 256) / 4.0