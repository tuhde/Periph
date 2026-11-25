_periph_drv2605.play_sequence([('wait', int(s[1:])) if s[0] == 'w' else int(s) for s in str(${_steps}).replace(' ', '').split(',') if s])
