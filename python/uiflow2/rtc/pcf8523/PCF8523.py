"""
file     PCF8523
time     2026-09-23
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rtc.pcf8523 import PCF8523Full


class PCF8523:
    """
    note:
        en: ''
    details:
        color: '#C084FC'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self, bus: int = 0, address: int = 104):
        """
        label:
            en: '%1 init bus %2 address %3'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
            address:
                name: address
                type: int
                default: '104'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCF8523Full(connection)

    def set_datetime(self, year: int, month: int, day: int, weekday: int, hour: int, minute: int, second: int):
        """
        label:
            en: '%1 set date/time year %2 month %3 day %4 weekday (0=Sun) %5 hour %6 minute %7 second %8'
        params:
            year:
                name: year
                type: int
                default: '2026'
                field: number
                max: '2099'
                min: '2000'
            month:
                name: month
                type: int
                default: '1'
                field: number
                max: '12'
                min: '1'
            day:
                name: day
                type: int
                default: '1'
                field: number
                max: '31'
                min: '1'
            weekday:
                name: weekday
                type: int
                default: '0'
                field: number
                max: '6'
                min: '0'
            hour:
                name: hour
                type: int
                default: '0'
                field: number
                max: '23'
                min: '0'
            minute:
                name: minute
                type: int
                default: '0'
                field: number
                max: '59'
                min: '0'
            second:
                name: second
                type: int
                default: '0'
                field: number
                max: '59'
                min: '0'
        """
        self._driver.set_datetime(year, month, day, weekday, hour, minute, second)

    def year(self) -> int:
        """
        label:
            en: '%1 year'
        """
        return self._driver.get_datetime()[0]

    def month(self) -> int:
        """
        label:
            en: '%1 month'
        """
        return self._driver.get_datetime()[1]

    def day(self) -> int:
        """
        label:
            en: '%1 day'
        """
        return self._driver.get_datetime()[2]

    def weekday(self) -> int:
        """
        label:
            en: '%1 weekday (0=Sun..6=Sat)'
        """
        return self._driver.get_datetime()[3]

    def hour(self) -> int:
        """
        label:
            en: '%1 hour'
        """
        return self._driver.get_datetime()[4]

    def minute(self) -> int:
        """
        label:
            en: '%1 minute'
        """
        return self._driver.get_datetime()[5]

    def second(self) -> int:
        """
        label:
            en: '%1 second'
        """
        return self._driver.get_datetime()[6]

    def set_alarm(self, minute: int, hour: int, day: int, weekday: int):
        """
        label:
            en: '%1 set alarm (-1 = ignore) minute %2 hour %3 day %4 weekday %5'
        params:
            minute:
                name: minute
                type: int
                default: '-1'
                field: number
                max: '59'
                min: '-1'
            hour:
                name: hour
                type: int
                default: '-1'
                field: number
                max: '23'
                min: '-1'
            day:
                name: day
                type: int
                default: '-1'
                field: number
                max: '31'
                min: '-1'
            weekday:
                name: weekday
                type: int
                default: '-1'
                field: number
                max: '6'
                min: '-1'
        """
        self._driver.set_alarm(*[None if v < 0 else v for v in (minute, hour, day, weekday)])

    def alarm_minute(self) -> int:
        """
        label:
            en: '%1 alarm minute (-1 = ignored)'
        """
        value = self._driver.get_alarm()[0]
        return -1 if value is None else value

    def alarm_hour(self) -> int:
        """
        label:
            en: '%1 alarm hour (-1 = ignored)'
        """
        value = self._driver.get_alarm()[1]
        return -1 if value is None else value

    def alarm_day(self) -> int:
        """
        label:
            en: '%1 alarm day (-1 = ignored)'
        """
        value = self._driver.get_alarm()[2]
        return -1 if value is None else value

    def alarm_weekday(self) -> int:
        """
        label:
            en: '%1 alarm weekday (-1 = ignored)'
        """
        value = self._driver.get_alarm()[3]
        return -1 if value is None else value

    def configure_timer_a(self, watchdog: int, value: int, source_clock: int):
        """
        label:
            en: '%1 start timer A watchdog (0/1) %2 value %3 source clock (0=4096Hz 1=64Hz 2=1Hz 3=1/60Hz 4=1/3600Hz) %4'
        params:
            watchdog:
                name: watchdog
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
            value:
                name: value
                type: int
                default: '10'
                field: number
                max: '255'
                min: '0'
            source_clock:
                name: source_clock
                type: int
                default: '2'
                field: number
                max: '4'
                min: '0'
        """
        self._driver.configure_timer_a('watchdog' if watchdog else 'countdown', value,
                                        ('4096hz', '64hz', '1hz', '1_60hz', '1_3600hz')[source_clock])

    def disable_timer_a(self):
        """
        label:
            en: '%1 stop timer A'
        """
        self._driver.disable_timer_a()

    def read_timer_a(self) -> int:
        """
        label:
            en: '%1 timer A counter'
        """
        return self._driver.read_timer_a()

    def configure_timer_b(self, value: int, source_clock: int):
        """
        label:
            en: '%1 start timer B value %2 source clock (0=4096Hz 1=64Hz 2=1Hz 3=1/60Hz 4=1/3600Hz) %3'
        params:
            value:
                name: value
                type: int
                default: '10'
                field: number
                max: '255'
                min: '0'
            source_clock:
                name: source_clock
                type: int
                default: '2'
                field: number
                max: '4'
                min: '0'
        """
        self._driver.configure_timer_b(value, ('4096hz', '64hz', '1hz', '1_60hz', '1_3600hz')[source_clock])

    def disable_timer_b(self):
        """
        label:
            en: '%1 stop timer B'
        """
        self._driver.disable_timer_b()

    def read_timer_b(self) -> int:
        """
        label:
            en: '%1 timer B counter'
        """
        return self._driver.read_timer_b()

    def set_clock_output(self, frequency_hz: int):
        """
        label:
            en: '%1 CLKOUT frequency (32768/16384/8192/4096/1024/32/1 Hz) %2'
        params:
            frequency_hz:
                name: frequency_hz
                type: int
                default: '32768'
                field: number
        """
        self._driver.set_clock_output(frequency_hz)

    def disable_clock_output(self):
        """
        label:
            en: '%1 disable CLKOUT'
        """
        self._driver.disable_clock_output()

    def offset(self) -> int:
        """
        label:
            en: '%1 offset (LSB)'
        """
        return self._driver.get_offset()[0]

    def set_offset(self, offset: int, every_minute: int):
        """
        label:
            en: '%1 set offset (LSB) %2 every minute (0/1) %3'
        params:
            offset:
                name: offset
                type: int
                default: '0'
                field: number
                max: '63'
                min: '-64'
            every_minute:
                name: every_minute
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_offset(offset, 'every_minute' if every_minute else 'every_two_hours')

    def configure_battery_backup(self, mode: int, low_detection: int):
        """
        label:
            en: '%1 battery backup mode (0=standard 1=direct 2=disabled) %2 low detection (0/1) %3'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
            low_detection:
                name: low_detection
                type: int
                default: '1'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.configure_battery_backup(('standard', 'direct', 'disabled')[mode], bool(low_detection))

    def is_battery_switched_over(self) -> bool:
        """
        label:
            en: '%1 battery switch-over occurred?'
        """
        return self._driver.is_battery_switched_over()

    def clear_battery_switchover(self):
        """
        label:
            en: '%1 clear battery switch-over flag'
        """
        self._driver.clear_battery_switchover()

    def is_battery_low(self) -> bool:
        """
        label:
            en: '%1 battery low?'
        """
        return self._driver.is_battery_low()

    def oscillator_stopped(self) -> bool:
        """
        label:
            en: '%1 oscillator stopped?'
        """
        return self._driver.oscillator_stopped()

    def software_reset(self):
        """
        label:
            en: '%1 software reset'
        """
        self._driver.software_reset()

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 enable interrupt source (1=second 2=timer A 4=timer B 8=alarm 16=battery switch 32=battery low) %2'
        params:
            source:
                name: source
                type: int
                default: '8'
                field: number
                max: '63'
                min: '1'
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt source (1=second 2=timer A 4=timer B 8=alarm 16=battery switch 32=battery low) %2'
        params:
            source:
                name: source
                type: int
                default: '8'
                field: number
                max: '63'
                min: '1'
        """
        self._driver.disable_interrupt(source)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt status'
        """
        return self._driver.poll_interrupt()
