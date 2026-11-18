import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.led.is31fl3731 import IS31FL3731Minimal, IS31FL3731Full

passed = 0
failed = 0

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)


def check(label, ok, extra=''):
    global passed, failed
    if ok:
        print('PASS ' + label)
        passed += 1
    else:
        print('FAIL ' + label + (': ' + extra if extra else ''))
        failed += 1


def attempt(label, fn):
    try:
        fn()
        check(label, True)
    except Exception as e:
        check(label, False, str(e))


matrix = IS31FL3731Minimal(connection)
check('constants', IS31FL3731Minimal.ROWS == 9 and IS31FL3731Minimal.COLS == 16)
attempt('set_pixel', lambda: (matrix.set_pixel(0, 0, 255), matrix.set_pixel(8, 15, 64)))
attempt('fill', lambda: matrix.fill(32))
attempt('clear', matrix.clear)

full = IS31FL3731Full(connection)
full.set_pixel(3, 4, 99)
check('get_pixel', full.get_pixel(3, 4) == 99 and full.get_pixel(3, 5) == 0)
attempt('set_pixels', lambda: full.set_pixels(bytes(range(144))))
attempt('led_enabled', lambda: (full.set_led_enabled(0, 0, False), full.set_led_enabled(0, 0, True)))
attempt('blink', lambda: (full.set_blink(1, 1, True), full.set_blink_all(True), full.enable_blink(True, 2),
                          full.enable_blink(False), full.set_blink_all(False)))
attempt('frames', lambda: (full.select_frame(1), full.fill(10), full.copy_frame(1, 2), full.show_frame(2),
                           full.select_frame(0), full.show_frame(0)))
attempt('clear_frames', lambda: (full.clear_frame(1), full.clear_all_frames()))
attempt('picture_mode', full.set_picture_mode)
attempt('auto_play', lambda: (full.start_auto_play(frames=2, loops=1, delay_ms=110), full.stop_auto_play()))
attempt('audio', lambda: (full.set_audio_gain(6, True, False), full.set_audio_rate(10),
                          full.set_audio_sync(False), full.set_audio_mode(False)))
attempt('breath', lambda: (full.set_breath(True, 2, 2, 1), full.set_breath(False)))
attempt('intensity_control', lambda: (full.set_intensity_control(True), full.set_intensity_control(False)))
attempt('frame_state', lambda: (full.frame_state(), full.current_frame(), full.movie_finished()))
attempt('function_register', lambda: (full.write_function_register(0x03, 0x05), full.read_function_register(0x03)))
attempt('poll_interrupt', full.poll_interrupt)
attempt('shutdown', lambda: (full.shutdown(), full.shutdown(False)))
full.clear()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
