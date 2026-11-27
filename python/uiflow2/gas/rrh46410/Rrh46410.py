"""
file     Rrh46410
time     2026-11-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.gas.rrh46410 import RRH46410Full


class Rrh46410:
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

    def __init__(self, bus: int = 0, address: int = 56):
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
                default: '56'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = RRH46410Full(connection)
        self._driver.init()

    def wait_for_sample(self) -> bool:
        """
        label:
            en: '%1 new sample (wait up to 5 s)'
        """
        return self._driver.wait_for_sample(5)

    def iaq(self) -> float:
        """
        label:
            en: '%1 IAQ index (1-5)'
        """
        return (self._driver.read_results() or {}).get('iaq', 0)

    def tvoc_mg_m3(self) -> float:
        """
        label:
            en: '%1 TVOC (mg/m3)'
        """
        return (self._driver.read_results() or {}).get('tvoc_mg_m3', 0)

    def etoh_ppm(self) -> float:
        """
        label:
            en: '%1 ethanol (ppm)'
        """
        return (self._driver.read_results() or {}).get('etoh_ppm', 0)

    def eco2_ppm(self) -> int:
        """
        label:
            en: '%1 eCO2 (ppm)'
        """
        return (self._driver.read_results() or {}).get('eco2_ppm', 0)

    def relative_iaq(self) -> int:
        """
        label:
            en: '%1 relative IAQ'
        """
        return (self._driver.read_results() or {}).get('relative_iaq', 0)

    def warmed_up(self) -> bool:
        """
        label:
            en: '%1 warmed up'
        """
        return (self._driver.read_results() or {}).get('warmed_up', False)

    def air_quality_label(self) -> str:
        """
        label:
            en: '%1 air quality rating'
        """
        return self._driver.air_quality_label((self._driver.read_results() or {}).get('iaq', 0))

    def product_id(self) -> int:
        """
        label:
            en: '%1 product ID'
        """
        return self._driver.product_id()[0]

    def get_mode(self) -> int:
        """
        label:
            en: '%1 operation mode'
        """
        return self._driver.get_mode()

    def set_mode(self, mode: int = 1):
        """
        label:
            en: '%1 operation mode (0 suspend, 1 IAQ, 2 ULP, 5 PBAQ) %2'
        params:
            mode:
                name: mode
                type: int
                default: '1'
                field: number
        """
        self._driver.set_mode(mode)

    def set_humidity(self, percent: float = 50.0):
        """
        label:
            en: '%1 ambient humidity (%RH) %2'
        params:
            percent:
                name: percent
                type: float
                default: '50.0'
                field: number
        """
        self._driver.set_humidity(percent)

    def sample_period(self) -> float:
        """
        label:
            en: '%1 sample period (s)'
        """
        return self._driver.sample_period()

    def warmup_samples(self) -> int:
        """
        label:
            en: '%1 warm-up samples'
        """
        return self._driver.warmup_samples()

    def config_gpio(self, directions: int = 3):
        """
        label:
            en: '%1 GPIO outputs mask %2'
        params:
            directions:
                name: directions
                type: int
                default: '3'
                field: number
        """
        self._driver.config_gpio(directions)

    def get_gpio(self) -> int:
        """
        label:
            en: '%1 GPIO levels'
        """
        return self._driver.get_gpio()

    def set_gpio(self, mask: int = 1):
        """
        label:
            en: '%1 set GPIO mask %2'
        params:
            mask:
                name: mask
                type: int
                default: '1'
                field: number
        """
        self._driver.set_gpio(mask)

    def clear_gpio(self, mask: int = 1):
        """
        label:
            en: '%1 clear GPIO mask %2'
        params:
            mask:
                name: mask
                type: int
                default: '1'
                field: number
        """
        self._driver.clear_gpio(mask)

    def status(self) -> int:
        """
        label:
            en: '%1 status byte'
        """
        return self._driver.status()['raw']

    def suspend(self):
        """
        label:
            en: '%1 suspend'
        """
        self._driver.suspend()

    def reset(self):
        """
        label:
            en: '%1 reset'
        """
        self._driver.reset()
