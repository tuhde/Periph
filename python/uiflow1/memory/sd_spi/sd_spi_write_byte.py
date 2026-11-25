_periph_sd_spi_buf = bytearray(_periph_sd_spi.read_block(${_block}))
_periph_sd_spi_buf[${_offset}] = ${_value}
_periph_sd_spi.write_block(${_block}, _periph_sd_spi_buf)