"""Complete example for the PCF85063A — exercise every method in the public API.

Constructs the Full driver, sets and reads the calendar clock, configures
the alarm and countdown timer, drives CLKOUT, reads/writes the offset, RAM
byte and oscillator load, and runs the interrupt API.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.pcf85063a import (
    PCF85063AFull, I2C_ADDRESS,
    SOURCE_ALARM, SOURCE_TIMER, SOURCE_MINUTE, SOURCE_HALF_MINUTE, SOURCE_CORRECTION,
)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = PCF85063AFull(connection)                          # Create PCF85063A Full driver, (connection)
                                                         # bus check, forces 24-hour mode with STOP = 0

rtc.set_datetime(2026, 9, 23, 3, 14, 30, 0)              # Write calendar clock, (year 2000–2099, month, day, weekday 0=Sun, hour, minute, second) → None
                                                         # STOP-bit precision start; clears OS
dt = rtc.get_datetime()                                  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
                                                         # decodes the seven BCD clock/calendar registers
stopped = rtc.oscillator_stopped()                       # Query oscillator-stop flag, () → bool
                                                         # True means the time may be invalid until set_datetime
rtc.clear_oscillator_stop()                              # Clear oscillator-stop flag, () → None
                                                         # still True afterwards means the crystal is not running
rtc.stop_clock()                                         # Freeze the clock, () → None
                                                         # STOP = 1, divider chain held in reset
rtc.start_clock()                                        # Release the clock, () → None

rtc.set_alarm(second=0, minute=0, hour=9)                # Configure alarm, (second=None, minute=None, hour=None, day=None, weekday=None) → None
                                                         # fires daily at 09:00:00; None fields are ignored in the match
alarm = rtc.get_alarm()                                  # Read alarm, () → (second, minute, hour, day, weekday)
                                                         # disabled fields decode as None

rtc.configure_timer(10, '1hz', pulsed=False)             # Start countdown timer, (value 1–255, source_clock, pulsed=False) → None
                                                         # counts down 10 s, then sets TF
remaining = rtc.read_timer()                             # Read timer counter, () → int
                                                         # live value, not the loaded one
rtc.disable_timer()                                      # Stop timer, () → None
                                                         # TE = 0, TIE = 0, 1/60 Hz clock for power saving

rtc.set_minute_interrupt('minute')                       # Select minute interrupt, (mode "off"|"minute"|"half_minute") → None
                                                         # sets MI; reported as SOURCE_TIMER

rtc.set_clock_output(1)                                  # Drive CLKOUT, (frequency_hz) → None
                                                         # 1 Hz square wave on the CLKOUT pin
rtc.disable_clock_output()                               # Disable CLKOUT, () → None
                                                         # COF = 111, pin low

rtc.set_offset(-3, 'normal')                             # Write offset calibration, (offset −64–63, mode='normal') → None
                                                         # −3 LSB × 4.34 ppm = −13.02 ppm correction
offset, offset_mode = rtc.get_offset()                   # Read offset calibration, () → (int, str)

rtc.set_oscillator_load(12.5)                            # Select crystal load, (picofarads 7|12.5) → None
                                                         # match the crystal's specified load capacitance
rtc.write_ram(0xA5)                                      # Write RAM byte, (value 0–255) → None
ram = rtc.read_ram()                                     # Read RAM byte, () → int


def on_event(status):
    print('interrupt status=0x{:02X}'.format(status))


rtc.on_interrupt(on_event)                               # Subscribe to interrupts, (callback, int_pin=None) → None
                                                         # falls back to 5 ms polling when no INT pin is wired
rtc.enable_interrupt(SOURCE_ALARM | SOURCE_TIMER)        # Enable sources, (source) → None
                                                         # sets AIE and TIE
status = rtc.poll_interrupt()                            # Poll & clear flags, () → int
                                                         # clears AF/TF, returns the pre-clear mask
rtc.disable_interrupt(SOURCE_ALARM | SOURCE_TIMER | SOURCE_MINUTE | SOURCE_HALF_MINUTE | SOURCE_CORRECTION)  # Disable sources, (source) → None
rtc.off_interrupt()                                      # Unsubscribe, () → None

rtc.software_reset()                                     # Software reset, () → None
                                                         # resets ALL registers including the time

print(dt, 'os_stopped={}'.format(stopped))
print('alarm', alarm, 'timer', remaining, 'ram', ram)
print('offset={} {} status=0x{:02X}'.format(offset, offset_mode, status))
