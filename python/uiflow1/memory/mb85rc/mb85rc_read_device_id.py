'%03X%X%02X' % _periph_mb85rc.read_device_id(_periph_i2c_conn(0x7C, bus=_periph_mb85rc_bus, reg_bytes=1), _periph_mb85rc_addr)
