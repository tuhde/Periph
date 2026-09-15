_frame = _periph_mcp2515.recv(int(${_timeout_ms}))
if _frame is None:
    ''
else:
    '%X,%d,%s' % (_frame.id, 1 if _frame.extended else 0, _frame.data.hex())
