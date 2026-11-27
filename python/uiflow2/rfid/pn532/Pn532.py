"""
file     Pn532
time     2026-11-27
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.pn532 import PN532Full


class Pn532:
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

    def __init__(self, bus: int = 0, address: int = 36):
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
                default: '36'
                field: number
        """
        connection = I2CConnection(address, bus=bus)
        self._driver = PN532Full(connection)
        self._driver.init()

    def read_uid(self) -> str:
        """
        label:
            en: '%1 card UID (hex, empty if none)'
        """
        return (self._driver.read_uid() or b'').hex()

    def firmware_version(self) -> str:
        """
        label:
            en: '%1 firmware version'
        """
        fw = self._driver.firmware_version()
        return '%d.%d' % (fw['version'], fw['revision'])

    def field_present(self) -> bool:
        """
        label:
            en: '%1 external RF field present?'
        """
        return self._driver.general_status()['field']

    def self_test(self) -> bool:
        """
        label:
            en: '%1 self test passed?'
        """
        return self._driver.self_test()

    def list_passive_targets(self, max_targets: int = 1) -> str:
        """
        label:
            en: '%1 list cards (hex UIDs, comma separated) max targets (1-2) %2'
        params:
            max_targets:
                name: max_targets
                type: int
                default: '1'
                field: number
        """
        return ','.join(t.uid.hex() for t in self._driver.list_passive_targets(max_targets=max_targets))

    def release(self, target: int = 0):
        """
        label:
            en: '%1 release target (0 = all) %2'
        params:
            target:
                name: target
                type: int
                default: '0'
                field: number
        """
        self._driver.release(target)

    def select(self, target: int = 1):
        """
        label:
            en: '%1 select target %2'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
        """
        self._driver.select(target)

    def deselect(self, target: int = 1):
        """
        label:
            en: '%1 deselect target %2'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
        """
        self._driver.deselect(target)

    def data_exchange(self, target: int = 1, data: str = '3000') -> str:
        """
        label:
            en: '%1 exchange with target %2 data (hex) %3 -> hex'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
            data:
                name: data
                type: str
                default: '3000'
        """
        return self._driver.data_exchange(target, bytes.fromhex(data)).hex()

    def mifare_authenticate(self, target: int = 1, block: int = 4, key: str = 'FFFFFFFFFFFF', uid: str = ''):
        """
        label:
            en: '%1 MIFARE authenticate target %2 block %3 key (hex) %4 UID (hex) %5'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
            block:
                name: block
                type: int
                default: '4'
                field: number
            key:
                name: key
                type: str
                default: 'FFFFFFFFFFFF'
            uid:
                name: uid
                type: str
                default: ''
        """
        self._driver.mifare_authenticate(target, block, bytes.fromhex(key), bytes.fromhex(uid))

    def mifare_read_block(self, target: int = 1, block: int = 4) -> str:
        """
        label:
            en: '%1 MIFARE read target %2 block %3 (hex)'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
            block:
                name: block
                type: int
                default: '4'
                field: number
        """
        return self._driver.mifare_read_block(target, block).hex()

    def mifare_write_block(self, target: int = 1, block: int = 4, data: str = ''):
        """
        label:
            en: '%1 MIFARE write target %2 block %3 data (32 hex digits) %4'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
            block:
                name: block
                type: int
                default: '4'
                field: number
            data:
                name: data
                type: str
                default: ''
        """
        self._driver.mifare_write_block(target, block, bytes.fromhex(data))

    def ntag_read_page(self, target: int = 1, page: int = 4) -> str:
        """
        label:
            en: '%1 NTAG read target %2 page %3 (4 pages, hex)'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
            page:
                name: page
                type: int
                default: '4'
                field: number
        """
        return self._driver.ntag_read_page(target, page).hex()

    def ntag_write_page(self, target: int = 1, page: int = 4, data: str = ''):
        """
        label:
            en: '%1 NTAG write target %2 page %3 data (8 hex digits) %4'
        params:
            target:
                name: target
                type: int
                default: '1'
                field: number
            page:
                name: page
                type: int
                default: '4'
                field: number
            data:
                name: data
                type: str
                default: ''
        """
        self._driver.ntag_write_page(target, page, bytes.fromhex(data))

    def set_rf_field(self, on: int = 1):
        """
        label:
            en: '%1 RF field on (1) / off (0) %2'
        params:
            on:
                name: on
                type: int
                default: '1'
                field: number
        """
        self._driver.set_rf_field(bool(on))

    def set_max_retries(self, passive_activation: int = 2):
        """
        label:
            en: '%1 card activation retries (255 = forever) %2'
        params:
            passive_activation:
                name: passive_activation
                type: int
                default: '2'
                field: number
        """
        self._driver.set_max_retries(passive_activation)

    def read_register(self, address: int = 25346) -> int:
        """
        label:
            en: '%1 register %2'
        params:
            address:
                name: address
                type: int
                default: '25346'
                field: number
        """
        return self._driver.read_register(address)

    def write_register(self, address: int = 25346, value: int = 0):
        """
        label:
            en: '%1 set register %2 value %3'
        params:
            address:
                name: address
                type: int
                default: '25346'
                field: number
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.write_register(address, value)

    def power_down(self):
        """
        label:
            en: '%1 power down'
        """
        self._driver.power_down()
