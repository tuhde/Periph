from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.led.is31fl3731 import IS31FL3731Minimal, IS31FL3731Full

passed = 0
failed = 0


def check(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    return False


c = I2CConnectionMock()
d = IS31FL3731Full(c)
w = c.writes
check('init_sequence', w[:9] == [
    bytes([0xFD, 0x0B]), bytes([0x0A, 0x00]), bytes([0x00, 0x00, 0x00]), bytes([0x05, 0x00]),
    bytes([0xFD, 0x00]), bytes([0x00] + [0xFF] * 18), bytes([0x12] + [0] * 18),
    bytes([0x24] + [0] * 144), bytes([0xFD, 0x0B])] and w[9] == bytes([0x0A, 0x01]) and len(w) == 10)

# page cache: drawing on frame 1 writes the page once, then only data
c.writes.clear()
d.set_pixel(2, 5, 200)
check('set_pixel_page_and_pwm', c.writes == [bytes([0xFD, 0x00]), bytes([0x24 + 37, 200])])
c.writes.clear()
d.set_pixel(8, 15, 1)
check('set_pixel_cached_page', c.writes == [bytes([0x24 + 143, 1])])
check('get_pixel', d.get_pixel(2, 5) == 200 and d.get_pixel(0, 0) == 0)
check('pixel_range', raises(lambda: d.set_pixel(9, 0, 0)) and raises(lambda: d.set_pixel(0, 16, 0))
      and raises(lambda: d.set_pixel(0, 0, 256)) and raises(lambda: d.set_pixel(-1, 0, 0)))

c.writes.clear()
d.fill(0x40)
check('fill', c.writes == [bytes([0x24] + [0x40] * 144)])
d.clear()
check('clear', c.writes[-1] == bytes([0x24] + [0] * 144) and d.get_pixel(2, 5) == 0)

d.set_pixels(bytes(range(144)))
check('set_pixels', c.writes[-1] == bytes([0x24]) + bytes(range(144)) and d.get_pixel(1, 1) == 17)
check('set_pixels_len', raises(lambda: d.set_pixels(b'\x00' * 143)))

# LED control and blink bits
c.writes.clear()
d.set_led_enabled(1, 9, False)
check('led_off', c.writes[-1] == bytes([0x00 + 3, 0xFD]))
d.set_led_enabled(1, 9, True)
check('led_on', c.writes[-1] == bytes([0x03, 0xFF]))
d.set_blink(1, 9, True)
check('blink_bit', c.writes[-1] == bytes([0x12 + 3, 0x02]))
d.set_blink_all(True)
check('blink_all', c.writes[-1] == bytes([0x12] + [0xFF] * 18))
d.set_blink_all(False)
check('blink_all_off', c.writes[-1] == bytes([0x12] + [0] * 18))

# frames
c.writes.clear()
d.select_frame(3)
check('select_frame', c.writes == [bytes([0xFD, 0x03])])
d.set_pixel(0, 0, 9)
check('draw_on_frame3', c.writes[-1] == bytes([0x24, 9]))
d.copy_frame(3, 5)
check('copy_frame', c.writes[-2] == bytes([0xFD, 0x05]) and c.writes[-1] == bytes([0x24, 9] + [0] * 143))
d.clear_frame(5)
check('clear_frame', c.writes[-3] == bytes([0x00] + [0xFF] * 18) and c.writes[-1] == bytes([0x24] + [0] * 144))
c.writes.clear()
d.clear_all_frames()
check('clear_all_frames', sum(1 for x in c.writes if len(x) == 145) == 8)
check('frame_range', raises(lambda: d.select_frame(8)) and raises(lambda: d.copy_frame(0, 8)))
d.select_frame(0)

c.writes.clear()
d.show_frame(4)
check('show_frame', c.writes == [bytes([0xFD, 0x0B]), bytes([0x00, 0x00, 0x04])])
d.set_picture_mode()
check('picture_mode', c.writes[-1] == bytes([0x00, 0x00]))

# auto play
d.start_auto_play(frames=3, loops=2, delay_ms=121, start_frame=1)
check('auto_play', c.writes[-2] == bytes([0x02, 0x23, 11]) and c.writes[-1] == bytes([0x00, 0x09]))
d.start_auto_play()
check('auto_play_defaults', c.writes[-2] == bytes([0x02, 0x00, 11]) and c.writes[-1] == bytes([0x00, 0x08]))
d.start_auto_play(delay_ms=704)
check('auto_play_64', c.writes[-2] == bytes([0x02, 0x00, 0x00]))
check('auto_play_range', raises(lambda: d.start_auto_play(delay_ms=2000)) and raises(lambda: d.start_auto_play(loops=8)))
c.set_register(0x07, 0x15)
d.stop_auto_play()
check('stop_auto_play', c.writes[-1] == bytes([0x00, 0x00, 0x05]))

# audio
d.set_audio_mode(True)
check('audio_mode', c.writes[-1] == bytes([0x00, 0x10]))
d.set_audio_mode(False)
check('audio_mode_off', c.writes[-1] == bytes([0x00, 0x00]))
d.set_audio_sync(True)
check('audio_sync', c.writes[-1] == bytes([0x06, 0x01]))
d.set_audio_gain(9, agc=True, fast=True)
check('audio_gain', c.writes[-1] == bytes([0x0B, 0x1B]))
check('audio_gain_range', raises(lambda: d.set_audio_gain(4)) and raises(lambda: d.set_audio_gain(24)))
d.set_audio_rate(100)
check('audio_rate', c.writes[-1] == bytes([0x0C, 100]))

# blink / breath / intensity
d.enable_blink(True, 3)
check('enable_blink', c.writes[-1] == bytes([0x05, 0x0B]))
d.set_intensity_control(True)
check('intensity_control', c.writes[-1] == bytes([0x05, 0x2B]))
d.enable_blink(False)
check('disable_blink', c.writes[-1] == bytes([0x05, 0x21]))
check('blink_period_range', raises(lambda: d.enable_blink(True, 8)))
d.set_breath(True, fade_in=2, fade_out=5, extinguish=3)
check('breath', c.writes[-1] == bytes([0x08, 0x52, 0x13]))
d.set_breath(False)
check('breath_off', c.writes[-1] == bytes([0x08, 0x00, 0x00]))

# shutdown
d.shutdown()
check('shutdown', c.writes[-1] == bytes([0x0A, 0x00]))
d.shutdown(False)
check('shutdown_off', c.writes[-1] == bytes([0x0A, 0x01]))

# state
c.set_register(0x07, 0x13)
check('frame_state', d.frame_state() == 0x13)
check('current_frame', d.current_frame() == 3)
check('movie_finished', d.movie_finished())
c.set_register(0x07, 0x02)
check('movie_not_finished', not d.movie_finished())

# raw access restores the page
d.select_frame(2)
c.writes.clear()
c.set_register(0x03, 0x2A)
check('read_function_register', d.read_function_register(0x03) == 0x2A)
check('read_restores_page', c.writes[-1] == bytes([0xFD, 0x02]))
c.writes.clear()
d.write_function_register(0x03, 5)
check('write_function_register', c.writes == [bytes([0xFD, 0x0B]), bytes([0x03, 5]), bytes([0xFD, 0x02])])
check('function_register_range', raises(lambda: d.read_function_register(0x0D)))

c.writes.clear()
c.set_register(0x07, 0x14)
check('poll_interrupt', d.poll_interrupt() == 0x14 and c.writes[-1] == bytes([0xFD, 0x02]))

check('gamma_tables', len(IS31FL3731Full.GAMMA_32) == 32 and len(IS31FL3731Full.GAMMA_64) == 64
      and IS31FL3731Full.GAMMA_32[31] == 255 and IS31FL3731Full.GAMMA_64[63] == 255)

m = IS31FL3731Minimal(I2CConnectionMock())
m.set_pixel(0, 0, 1)
check('minimal', m.ROWS == 9 and m.COLS == 16)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
