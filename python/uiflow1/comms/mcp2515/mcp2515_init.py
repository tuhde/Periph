from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.comms.mcp2515 import MCP2515Full as _MCP2515Full

_periph_mcp2515 = _MCP2515Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}),
                               bitrate_kbps=int(${_bitrate_kbps}))
