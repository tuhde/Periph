"""
file     MFRC522
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.spi_auto import SPIConnection
from periph.chips.rfid.mfrc522 import MFRC522Full


class MFRC522:
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

    def __init__(self, bus: int = 1, cs_pin: int = 5):
        """
        label:
            en: '%1 init bus %2 cs_pin %3'
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
        """
        connection = SPIConnection(bus=bus, cs_pin=cs_pin)
        self._driver = MFRC522Full(connection)

    def is_card_present(self) -> bool:
        """
        label:
            en: '%1 card present?'
        """
        return self._driver.is_card_present()

    def read_uid(self) -> str:
        """
        label:
            en: '%1 card UID (hex, empty if none)'
        """
        return (self._driver.read_uid() or b'').hex()
