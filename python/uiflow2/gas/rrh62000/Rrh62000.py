"""
file     Rrh62000
time     2026-11-28
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh62000 import RRH62000Full


class Rrh62000:
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

    def __init__(self, bus: int = 0, address: int = 105):
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
                default: '105'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = RRH62000Full(connection)
        self._driver.init()
        self._m = {}

    def read(self):
        """
        label:
            en: '%1 read new sample'
        """
        self._m = self._driver.read_all()

    def pm1(self) -> float:
        """
        label:
            en: '%1 PM1 (ug/m3)'
        """
        return self._m.get('pm1_ug_m3', 0)

    def pm2_5(self) -> float:
        """
        label:
            en: '%1 PM2.5 (ug/m3)'
        """
        return self._m.get('pm2_5_ug_m3', 0)

    def pm10(self) -> float:
        """
        label:
            en: '%1 PM10 (ug/m3)'
        """
        return self._m.get('pm10_ug_m3', 0)

    def pm1_smoke(self) -> float:
        """
        label:
            en: '%1 PM1 smoke ref. (ug/m3)'
        """
        return self._m.get('pm1_smoke_ug_m3', 0)

    def pm2_5_smoke(self) -> float:
        """
        label:
            en: '%1 PM2.5 smoke ref. (ug/m3)'
        """
        return self._m.get('pm2_5_smoke_ug_m3', 0)

    def pm10_smoke(self) -> float:
        """
        label:
            en: '%1 PM10 smoke ref. (ug/m3)'
        """
        return self._m.get('pm10_smoke_ug_m3', 0)

    def nc_0_3(self) -> float:
        """
        label:
            en: '%1 particles >0.3 um (1/cm3)'
        """
        return self._m.get('nc_0_3', 0)

    def nc_0_5(self) -> float:
        """
        label:
            en: '%1 particles >0.5 um (1/cm3)'
        """
        return self._m.get('nc_0_5', 0)

    def nc_1(self) -> float:
        """
        label:
            en: '%1 particles >1 um (1/cm3)'
        """
        return self._m.get('nc_1', 0)

    def nc_2_5(self) -> float:
        """
        label:
            en: '%1 particles >2.5 um (1/cm3)'
        """
        return self._m.get('nc_2_5', 0)

    def nc_4(self) -> float:
        """
        label:
            en: '%1 particles >4 um (1/cm3)'
        """
        return self._m.get('nc_4', 0)

    def temperature(self) -> float:
        """
        label:
            en: '%1 temperature (C)'
        """
        return self._m.get('temperature_c', 0)

    def humidity(self) -> float:
        """
        label:
            en: '%1 humidity (%RH)'
        """
        return self._m.get('humidity_rh', 0)

    def tvoc(self) -> int:
        """
        label:
            en: '%1 TVOC (ug/m3)'
        """
        return self._m.get('tvoc', 0)

    def eco2(self) -> int:
        """
        label:
            en: '%1 eCO2 (ppm)'
        """
        return self._m.get('eco2_ppm', 0)

    def iaq(self) -> float:
        """
        label:
            en: '%1 IAQ index (1-5)'
        """
        return self._m.get('iaq', 0)

    def status(self) -> int:
        """
        label:
            en: '%1 status word'
        """
        return self._m.get('status', 0)

    def high_concentration(self) -> bool:
        """
        label:
            en: '%1 dust concentration too high'
        """
        return self._m.get('high_concentration', False)

    def dust_accumulation(self) -> bool:
        """
        label:
            en: '%1 dust accumulated'
        """
        return self._m.get('dust_accumulation', False)

    def fan_speed_error(self) -> bool:
        """
        label:
            en: '%1 fan speed out of range'
        """
        return self._m.get('fan_speed', False)

    def fan_error(self) -> bool:
        """
        label:
            en: '%1 fan malfunction'
        """
        return self._m.get('fan', False)

    def data_ready(self) -> bool:
        """
        label:
            en: '%1 new data ready'
        """
        return self._driver.data_ready()

    def sleep(self):
        """
        label:
            en: '%1 sleep'
        """
        self._driver.sleep()

    def wake(self):
        """
        label:
            en: '%1 wake up'
        """
        self._driver.wake()

    def start_cleaning(self):
        """
        label:
            en: '%1 start fan cleaning'
        """
        self._driver.start_cleaning()

    def cleaning_done(self) -> bool:
        """
        label:
            en: '%1 cleaning done'
        """
        return self._driver.cleaning_done()

    def soft_reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.soft_reset()

    def set_moving_average(self, count: int = 10):
        """
        label:
            en: '%1 moving average (1-60) %2'
        params:
            count:
                name: count
                type: int
                default: '10'
                field: number
        """
        self._driver.set_moving_average(int(count))

    def get_moving_average(self) -> int:
        """
        label:
            en: '%1 moving average'
        """
        return self._driver.get_moving_average()

    def set_fan_speed(self, percent: int = 86):
        """
        label:
            en: '%1 fan speed (60-100 %) %2'
        params:
            percent:
                name: percent
                type: int
                default: '86'
                field: number
        """
        self._driver.set_fan_speed(int(percent))

    def get_fan_speed(self) -> int:
        """
        label:
            en: '%1 fan speed (%)'
        """
        return self._driver.get_fan_speed()

    def set_cleaning_interval(self, seconds: int = 604800):
        """
        label:
            en: '%1 cleaning interval (s, multiple of 30) %2'
        params:
            seconds:
                name: seconds
                type: int
                default: '604800'
                field: number
        """
        self._driver.set_cleaning_interval(int(seconds))

    def get_cleaning_interval(self) -> int:
        """
        label:
            en: '%1 cleaning interval (s)'
        """
        return self._driver.get_cleaning_interval()

    def set_cleaning_time(self, seconds: int = 10):
        """
        label:
            en: '%1 cleaning time (0-60 s) %2'
        params:
            seconds:
                name: seconds
                type: int
                default: '10'
                field: number
        """
        self._driver.set_cleaning_time(int(seconds))

    def get_cleaning_time(self) -> int:
        """
        label:
            en: '%1 cleaning time (s)'
        """
        return self._driver.get_cleaning_time()

    def mox_resistance(self) -> int:
        """
        label:
            en: '%1 MOX resistance (ohm)'
        """
        return self._driver.read_mox_resistance()

    def unique_id(self) -> str:
        """
        label:
            en: '%1 unique ID (hex)'
        """
        return ''.join('%02X' % b for b in self._driver.unique_id())

    def algorithm_version(self) -> str:
        """
        label:
            en: '%1 algorithm version'
        """
        return '%d.%d.%d' % self._driver.algorithm_version()

    def firmware_version(self) -> str:
        """
        label:
            en: '%1 firmware version'
        """
        return '%d.%d' % self._driver.firmware_version()
