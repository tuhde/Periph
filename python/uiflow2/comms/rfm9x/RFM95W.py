"""
file     RFM95W
time     2026-09-11
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.comms.rfm9x import RFM95Full


class RFM95W:
    """
    note:
        en: ''
    details:
        color: '#C084FC'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self, bus: int = 1, cs_pin: int = 5, frequency_hz: int = 915000000):
        """
        label:
            en: '%1 init bus %2 cs_pin %3 frequency (Hz) %4'
        params:
            bus:
                name: bus
                type: int
                default: '1'
                field: number
            cs_pin:
                name: cs_pin
                type: int
                default: '5'
                field: number
            frequency_hz:
                name: frequency_hz
                type: int
                default: '915000000'
                field: number
                min: '862000000'
                max: '1020000000'
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = RFM95Full(connection, frequency_hz)

    def send(self, data: str):
        """
        label:
            en: '%1 send packet data %2'
        params:
            data:
                name: data
                type: str
        """
        self._driver.send(data.encode())

    def receive(self, timeout_ms: int = 2000) -> str:
        """
        label:
            en: '%1 receive packet (hex, empty if none) timeout (ms) %2'
        params:
            timeout_ms:
                name: timeout_ms
                type: int
                default: '2000'
                field: number
        """
        return (self._driver.receive(timeout_ms=timeout_ms) or b'').hex()

    def configure(self, sf: int = 7, bandwidth_khz: float = 125.0, coding_rate: int = 5, crc: int = 1):
        """
        label:
            en: '%1 configure LoRa sf %2 bandwidth (kHz) %3 coding_rate %4 crc on (1) / off (0) %5'
        params:
            sf:
                name: sf
                type: int
                default: '7'
                field: number
                min: '6'
                max: '12'
            bandwidth_khz:
                name: bandwidth_khz
                type: float
                default: '125.0'
                field: number
            coding_rate:
                name: coding_rate
                type: int
                default: '5'
                field: number
                min: '5'
                max: '8'
            crc:
                name: crc
                type: int
                default: '1'
                field: number
        """
        self._driver.configure(sf, bandwidth_khz, coding_rate, crc=bool(crc))

    def set_tx_power(self, power_dbm: int = 17, use_pa_boost: int = 1):
        """
        label:
            en: '%1 set TX power (dBm) power_dbm %2 use PA_BOOST (1) / RFO (0) %3'
        params:
            power_dbm:
                name: power_dbm
                type: int
                default: '17'
                field: number
                min: '-1'
                max: '20'
            use_pa_boost:
                name: use_pa_boost
                type: int
                default: '1'
                field: number
        """
        self._driver.set_tx_power(power_dbm, use_pa_boost=bool(use_pa_boost))

    def last_packet_rssi(self) -> float:
        """
        label:
            en: '%1 last packet RSSI (dBm)'
        """
        return self._driver.last_packet_rssi()

    def last_packet_snr(self) -> float:
        """
        label:
            en: '%1 last packet SNR (dB)'
        """
        return self._driver.last_packet_snr()

    def sleep(self):
        """
        label:
            en: '%1 sleep'
        """
        self._driver.sleep()
