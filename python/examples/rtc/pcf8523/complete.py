"""Complete example for the PCF8523 — exercise every method in the public API.

Constructs the Full driver, sets and reads the calendar clock, configures
the alarm and both timers, drives CLKOUT, reads/writes the offset and
battery-backup controls, and runs the interrupt API.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.pcf8523 import (
    PCF8523Full, I2C_ADDRESS,
    SOURCE_ALARM, SOURCE_TIMER_A, SOURCE_TIMER_B, SOURCE_BATTERY_LOW,
)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = PCF8523Full(connection)                            # Create PCF8523 Full driver, (connection)
                                                         # enables battery switch-over standard mode (PM=000)

rtc.set_datetime(2026, 9, 23, 3, 14, 30, 0)              # Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → None
                                                         # STOP-bit precision start; forces 24-hour mode and clears OS
dt = rtc.get_datetime()                                  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
                                                         # decodes the seven BCD clock/calendar registers
stopped = rtc.oscillator_stopped()                       # Query oscillator-stop flag, () → bool
                                                         # True means the time may be invalid until set_datetime

rtc.set_alarm(minute=0, hour=9)                          # Configure alarm, (minute=None, hour=None, day=None, weekday=None) → None
                                                         # fires daily at 09:00; None fields are ignored in the match
alarm = rtc.get_alarm()                                  # Read alarm, () → (minute, hour, day, weekday)
                                                         # disabled fields decode as None

rtc.configure_timer_a('countdown', 10, '1hz')            # Start Timer A, (mode, value 0–255, source_clock, pulsed=False) → None
                                                         # counts down 10 s, then sets CTAF
remaining_a = rtc.read_timer_a()                         # Read Timer A counter, () → int
                                                         # live value, not the loaded one
rtc.disable_timer_a()                                    # Stop Timer A, () → None

rtc.configure_timer_b(30, '1hz', 62.5, True)             # Start Timer B, (value 0–255, source_clock, pulse_width_ms=46.875 ms, pulsed=False) → None
                                                         # 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
remaining_b = rtc.read_timer_b()                         # Read Timer B counter, () → int
rtc.disable_timer_b()                                    # Stop Timer B, () → None

rtc.set_clock_output(1)                                  # Drive CLKOUT, (frequency_hz) → None
                                                         # 1 Hz square wave on the shared INT1/CLKOUT pin
rtc.disable_clock_output()                               # Disable CLKOUT, () → None
                                                         # frees INT1 for interrupts

rtc.set_offset(-3, 'every_two_hours')                    # Write offset calibration, (offset −64–63, mode='every_two_hours') → None
                                                         # −3 LSB × 4.34 ppm = −13.02 ppm correction
offset, offset_mode = rtc.get_offset()                   # Read offset calibration, () → (int, str)

rtc.configure_battery_backup('standard', True)           # Select battery switch-over, (mode, low_detection=True) → None
                                                         # switches to VBAT when VDD < VBAT and VDD < 2.5 V
switched = rtc.is_battery_switched_over()                # Query switch-over flag, () → bool
rtc.clear_battery_switchover()                           # Clear switch-over flag, () → None
low = rtc.is_battery_low()                               # Query battery-low flag, () → bool
                                                         # read-only; clears itself once the cell is replaced


def on_event(status):
    print('interrupt status=0x{:02X}'.format(status))


rtc.on_interrupt(on_event)                               # Subscribe to interrupts, (callback, int_pin=None) → None
                                                         # falls back to 5 ms polling when no INT pin is wired
rtc.enable_interrupt(SOURCE_ALARM | SOURCE_TIMER_B | SOURCE_BATTERY_LOW)  # Enable sources, (source) → None
                                                         # sets AIE, CTBIE and BLIE
status = rtc.poll_interrupt()                            # Poll & clear flags, () → int
                                                         # clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
rtc.disable_interrupt(SOURCE_ALARM | SOURCE_TIMER_A | SOURCE_TIMER_B | SOURCE_BATTERY_LOW)  # Disable sources, (source) → None
rtc.off_interrupt()                                      # Unsubscribe, () → None

rtc.software_reset()                                     # Software reset, () → None
                                                         # control registers back to POR (PM=111); time is kept

print(dt, 'os_stopped={}'.format(stopped))
print('alarm', alarm, 'timer_a', remaining_a, 'timer_b', remaining_b)
print('offset={} {} switched={} low={} status=0x{:02X}'.format(
    offset, offset_mode, switched, low, status))
