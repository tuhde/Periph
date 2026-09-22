"""
file     DS3231
time     2026-09-22
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rtc.ds3231 import DS3231Full


class DS3231:
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
        self._driver = DS3231Full(connection)

    def set_datetime(self, year: int, month: int, day: int, weekday: int,
                      hour: int, minute: int, second: int):
        """
        label:
            en: '%1 set date/time year %2 month %3 day %4 weekday %5 hour %6 minute %7 second %8'
        params:
            year:
                name: year
                type: int
                default: '2026'
                field: number
            month:
                name: month
                type: int
                default: '1'
                field: number
            day:
                name: day
                type: int
                default: '1'
                field: number
            weekday:
                name: weekday
                type: int
                default: '1'
                field: number
            hour:
                name: hour
                type: int
                default: '0'
                field: number
            minute:
                name: minute
                type: int
                default: '0'
                field: number
            second:
                name: second
                type: int
                default: '0'
                field: number
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
            en: '%1 weekday (1=Mon..7=Sun)'
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

    def read_temperature(self) -> float:
        """
        label:
            en: '%1 read temperature (°C)'
        """
        return self._driver.read_temperature()

    def force_temperature_conversion(self):
        """
        label:
            en: '%1 force temperature conversion'
        """
        self._driver.force_temperature_conversion()

    def set_alarm1(self, second: int, minute: int, hour: int, day_or_date: int,
                    is_day_of_week: int, match_mode: int):
        """
        label:
            en: '%1 set alarm 1 second %2 minute %3 hour %4 day/date %5 is day of week %6 match mode %7'
        params:
            second:
                name: second
                type: int
                default: '0'
                field: number
            minute:
                name: minute
                type: int
                default: '0'
                field: number
            hour:
                name: hour
                type: int
                default: '0'
                field: number
            day_or_date:
                name: day_or_date
                type: int
                default: '1'
                field: number
            is_day_of_week:
                name: is_day_of_week
                type: int
                default: '0'
                field: number
            match_mode:
                name: match_mode
                type: int
                default: '0'
                field: number
                max: '5'
                min: '0'
        """
        self._driver.set_alarm1(second, minute, hour, day_or_date,
                                 bool(is_day_of_week), match_mode)

    def set_alarm2(self, minute: int, hour: int, day_or_date: int,
                    is_day_of_week: int, match_mode: int):
        """
        label:
            en: '%1 set alarm 2 minute %2 hour %3 day/date %4 is day of week %5 match mode %6'
        params:
            minute:
                name: minute
                type: int
                default: '0'
                field: number
            hour:
                name: hour
                type: int
                default: '0'
                field: number
            day_or_date:
                name: day_or_date
                type: int
                default: '1'
                field: number
            is_day_of_week:
                name: is_day_of_week
                type: int
                default: '0'
                field: number
            match_mode:
                name: match_mode
                type: int
                default: '0'
                field: number
                max: '4'
                min: '0'
        """
        self._driver.set_alarm2(minute, hour, day_or_date, bool(is_day_of_week), match_mode)

    def enable_square_wave(self, rate_hz: int = 8192, battery_backed: int = 0):
        """
        label:
            en: '%1 enable square wave rate %2 Hz battery backed %3'
        params:
            rate_hz:
                name: rate_hz
                type: int
                default: '8192'
                field: number
            battery_backed:
                name: battery_backed
                type: int
                default: '0'
                field: number
        """
        self._driver.enable_square_wave(rate_hz, bool(battery_backed))

    def disable_square_wave(self):
        """
        label:
            en: '%1 disable square wave'
        """
        self._driver.disable_square_wave()

    def enable_32khz_output(self):
        """
        label:
            en: '%1 enable 32kHz output'
        """
        self._driver.enable_32khz_output()

    def disable_32khz_output(self):
        """
        label:
            en: '%1 disable 32kHz output'
        """
        self._driver.disable_32khz_output()

    def oscillator_stopped(self) -> bool:
        """
        label:
            en: '%1 oscillator stopped?'
        """
        return self._driver.oscillator_stopped()

    def clear_oscillator_stopped(self):
        """
        label:
            en: '%1 clear oscillator stop flag'
        """
        self._driver.clear_oscillator_stopped()

    def get_aging_offset(self) -> int:
        """
        label:
            en: '%1 aging offset'
        """
        return self._driver.get_aging_offset()

    def set_aging_offset(self, offset: int):
        """
        label:
            en: '%1 set aging offset %2'
        params:
            offset:
                name: offset
                type: int
                default: '0'
                field: number
                max: '127'
                min: '-128'
        """
        self._driver.set_aging_offset(offset)

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 enable interrupt source %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt source %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
        """
        self._driver.disable_interrupt(source)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt status'
        """
        return self._driver.poll_interrupt()
