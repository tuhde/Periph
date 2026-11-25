from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.memory.mx25l12845e import MX25L12845EFull as _MX25L12845EFull

_periph_mx25l12845e = _MX25L12845EFull(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}))