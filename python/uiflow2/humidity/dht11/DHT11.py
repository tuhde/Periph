"""
file     DHT11
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.dhtxx_auto import DHTxxConnection
from periph.chips.humidity.dht11 import DHT11Full


class DHT11:
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

    def __init__(self, pin: int = 0):
        """
        label:
            en: '%1 init pin %2'
        params:
            pin:
                name: pin
                type: int
                default: '0'
                field: number
        """
        connection = DHTxxConnection(pin)
        self._driver = DHT11Full(connection)

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 temperature (°C)'
        """
        return self._driver.read_temperature()

    def read_humidity(self) -> float:
        """
        label:
            en: '%1 humidity (%)'
        """
        return self._driver.read_humidity()
