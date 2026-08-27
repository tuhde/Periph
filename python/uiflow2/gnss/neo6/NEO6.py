"""
file     NEO6
time     2026-08-27
author
email
license  Apache License 2.0
"""

from periph.connection.uart_auto import UARTConnection
from periph.chips.gnss.neo6 import NEO6Full


class NEO6:
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

    def __init__(self, port: int = 1, baudrate: int = 9600, tx: int = 0, rx: int = 0):
        """
        label:
            en: '%1 init port %2 baudrate %3 tx %4 rx %5'
        params:
            port:
                name: port
                type: int
                default: '1'
                field: number
            baudrate:
                name: baudrate
                type: int
                default: '9600'
                field: number
            tx:
                name: tx
                type: int
                default: '0'
                field: number
            rx:
                name: rx
                type: int
                default: '0'
                field: number
        """
        connection = UARTConnection(port=port, baudrate=baudrate, tx=tx, rx=rx)
        self._driver = NEO6Full(connection)

    def update(self) -> bool:
        """
        label:
            en: '%1 update (got new fix?)'
        """
        return self._driver.update()

    def latitude(self) -> float:
        """
        label:
            en: '%1 latitude (°)'
        """
        return self._driver.latitude()

    def longitude(self) -> float:
        """
        label:
            en: '%1 longitude (°)'
        """
        return self._driver.longitude()

    def altitude(self) -> float:
        """
        label:
            en: '%1 altitude (m)'
        """
        return self._driver.altitude()
