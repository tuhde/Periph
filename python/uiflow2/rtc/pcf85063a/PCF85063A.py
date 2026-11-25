"""
file     PCF85063A
time     2026-11-09
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rtc.pcf85063a import PCF85063AFull


class PCF85063A:
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

    def __init__(self, bus: int = 0, address: int = 81):
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
                default: '81'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PCF85063AFull(connection)

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
            en: '%1 weekday'
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

    def set_alarm(self, second: int, minute: int, hour: int, day: int, weekday: int):
        """
        label:
            en: '%1 set alarm (-1 = ignore) second %2 minute %3 hour %4 day %5 weekday %6'
        params:
            second:
                name: second
                type: int
                default: '-1'
                field: number
                max: '59'
                min: '-1'
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
        self._driver.set_alarm(*[None if v < 0 else v for v in (second, minute, hour, day, weekday)])

    def alarm_second(self) -> int:
        """
        label:
            en: '%1 alarm second (-1 = ignored)'
        """
        value = self._driver.get_alarm()[0]
        return -1 if value is None else value

    def alarm_minute(self) -> int:
        """
        label:
            en: '%1 alarm minute (-1 = ignored)'
        """
        value = self._driver.get_alarm()[1]
        return -1 if value is None else value

    def alarm_hour(self) -> int:
        """
        label:
            en: '%1 alarm hour (-1 = ignored)'
        """
        value = self._driver.get_alarm()[2]
        return -1 if value is None else value

    def alarm_day(self) -> int:
        """
        label:
            en: '%1 alarm day (-1 = ignored)'
        """
        value = self._driver.get_alarm()[3]
        return -1 if value is None else value

    def alarm_weekday(self) -> int:
        """
        label:
            en: '%1 alarm weekday (-1 = ignored)'
        """
        value = self._driver.get_alarm()[4]
        return -1 if value is None else value

    def configure_timer(self, value: int, source_clock: int, pulsed: int):
        """
        label:
            en: '%1 start timer value %2 source clock (0=4096Hz 1=64Hz 2=1Hz 3=1/60Hz) %3 pulsed (0/1) %4'
        params:
            value:
                name: value
                type: int
                default: '10'
                field: number
                max: '255'
                min: '1'
            source_clock:
                name: source_clock
                type: int
                default: '2'
                field: number
                max: '3'
                min: '0'
            pulsed:
                name: pulsed
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.configure_timer(value, ('4096hz', '64hz', '1hz', '1_60hz')[source_clock], bool(pulsed))

    def disable_timer(self):
        """
        label:
            en: '%1 stop timer'
        """
        self._driver.disable_timer()

    def read_timer(self) -> int:
        """
        label:
            en: '%1 timer counter'
        """
        return self._driver.read_timer()

    def set_minute_interrupt(self, mode: int):
        """
        label:
            en: '%1 minute interrupt (0=off 1=minute 2=half-minute) %2'
        params:
            mode:
                name: mode
                type: int
                default: '0'
                field: number
                max: '2'
                min: '0'
        """
        self._driver.set_minute_interrupt(('off', 'minute', 'half_minute')[mode])

    def set_clock_output(self, frequency_hz: int):
        """
        label:
            en: '%1 CLKOUT frequency (Hz: 32768, 16384, 8192, 4096, 2048, 1024, 1) %2'
        params:
            frequency_hz:
                name: frequency_hz
                type: int
                default: '32768'
                field: number
                max: '32768'
                min: '1'
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

    def set_offset(self, offset: int, course_mode: int):
        """
        label:
            en: '%1 set offset (LSB) %2 course mode (0/1) %3'
        params:
            offset:
                name: offset
                type: int
                default: '0'
                field: number
                max: '63'
                min: '-64'
            course_mode:
                name: course_mode
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_offset(offset, 'course' if course_mode else 'normal')

    def set_oscillator_load(self, load_12_5pf: int):
        """
        label:
            en: '%1 oscillator load (0=7pF 1=12.5pF) %2'
        params:
            load_12_5pf:
                name: load_12_5pf
                type: int
                default: '0'
                field: number
                max: '1'
                min: '0'
        """
        self._driver.set_oscillator_load(12.5 if load_12_5pf else 7)

    def read_ram(self) -> int:
        """
        label:
            en: '%1 RAM byte'
        """
        return self._driver.read_ram()

    def write_ram(self, value: int):
        """
        label:
            en: '%1 write RAM byte %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
                max: '255'
                min: '0'
        """
        self._driver.write_ram(value)

    def oscillator_stopped(self) -> bool:
        """
        label:
            en: '%1 oscillator stopped?'
        """
        return self._driver.oscillator_stopped()

    def clear_oscillator_stop(self):
        """
        label:
            en: '%1 clear oscillator-stop flag'
        """
        self._driver.clear_oscillator_stop()

    def stop_clock(self):
        """
        label:
            en: '%1 stop clock'
        """
        self._driver.stop_clock()

    def start_clock(self):
        """
        label:
            en: '%1 start clock'
        """
        self._driver.start_clock()

    def software_reset(self):
        """
        label:
            en: '%1 software reset (resets time)'
        """
        self._driver.software_reset()

    def enable_interrupt(self, source: int):
        """
        label:
            en: '%1 enable interrupt source (1=alarm 2=timer 4=minute 8=half-minute 16=correction) %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                max: '31'
                min: '0'
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int):
        """
        label:
            en: '%1 disable interrupt source (1=alarm 2=timer 4=minute 8=half-minute 16=correction) %2'
        params:
            source:
                name: source
                type: int
                default: '1'
                field: number
                max: '31'
                min: '0'
        """
        self._driver.disable_interrupt(source)

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 poll interrupt status (1=alarm 2=timer)'
        """
        return self._driver.poll_interrupt()
