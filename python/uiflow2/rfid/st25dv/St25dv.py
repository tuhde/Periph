"""
file     St25dv
time     2026-11-28
author
email
license  Apache License 2.0
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.rfid.st25dv import ST25DVFull


class St25dv:
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

    def __init__(self, bus: int = 0):
        """
        label:
            en: '%1 init bus %2'
        params:
            bus:
                name: bus
                type: int
                default: '0'
                field: number
        """
        user = I2CConnection(0x53, bus=bus, reg_bytes=2)
        system = I2CConnection(0x57, bus=bus, reg_bytes=2)
        self._driver = ST25DVFull(user, system)

    def read(self, address: int = 0, length: int = 16) -> str:
        """
        label:
            en: '%1 read address %2 length %3 -> hex'
        params:
            address:
                name: address
                type: int
                default: '0'
                field: number
            length:
                name: length
                type: int
                default: '16'
                field: number
        """
        return self._driver.read(address, length).hex()

    def write(self, address: int = 0, data: str = '48656C6C6F'):
        """
        label:
            en: '%1 write address %2 data (hex) %3'
        params:
            address:
                name: address
                type: int
                default: '0'
                field: number
            data:
                name: data
                type: str
                default: '48656C6C6F'
        """
        self._driver.write(address, bytes.fromhex(data))

    def memory_size(self) -> int:
        """
        label:
            en: '%1 memory size (bytes)'
        """
        return self._driver.memory_size()

    def ic_reference(self) -> int:
        """
        label:
            en: '%1 IC reference'
        """
        return self._driver.ic_reference()

    def uid(self) -> str:
        """
        label:
            en: '%1 UID (hex)'
        """
        return self._driver.uid().hex()

    def ic_revision(self) -> int:
        """
        label:
            en: '%1 IC revision'
        """
        return self._driver.ic_revision()

    def read_system(self, address: int = 0, length: int = 1) -> str:
        """
        label:
            en: '%1 read system area address %2 length %3 -> hex'
        params:
            address:
                name: address
                type: int
                default: '0'
                field: number
            length:
                name: length
                type: int
                default: '1'
                field: number
        """
        return self._driver.read_system(address, length).hex()

    def write_system(self, address: int = 0, data: str = '00'):
        """
        label:
            en: '%1 write system area address %2 data (hex) %3'
        params:
            address:
                name: address
                type: int
                default: '0'
                field: number
            data:
                name: data
                type: str
                default: '00'
        """
        self._driver.write_system(address, bytes.fromhex(data))

    def present_password(self, password: str = '0000000000000000') -> bool:
        """
        label:
            en: '%1 present I2C password (hex) %2'
        params:
            password:
                name: password
                type: str
                default: '0000000000000000'
        """
        return self._driver.present_password(bytes.fromhex(password))

    def write_password(self, password: str = '0000000000000000'):
        """
        label:
            en: '%1 change I2C password (hex) %2'
        params:
            password:
                name: password
                type: str
                default: '0000000000000000'
        """
        self._driver.write_password(bytes.fromhex(password))

    def session_open(self) -> bool:
        """
        label:
            en: '%1 security session open'
        """
        return self._driver.session_open()

    def set_area_end(self, area: int = 1, end_block: int = 63):
        """
        label:
            en: '%1 set end of area (1-3) %2 to block (x32 bytes) %3'
        params:
            area:
                name: area
                type: int
                default: '1'
                field: number
            end_block:
                name: end_block
                type: int
                default: '63'
                field: number
        """
        self._driver.set_area_end(area, end_block)

    def set_area_protection(self, area: int = 1, rf: int = 0, i2c: int = 0):
        """
        label:
            en: '%1 protect area (1-4) %2 RF (0-15) %3 I2C (0-3) %4'
        params:
            area:
                name: area
                type: int
                default: '1'
                field: number
            rf:
                name: rf
                type: int
                default: '0'
                field: number
            i2c:
                name: i2c
                type: int
                default: '0'
                field: number
        """
        self._driver.set_area_protection(area, rf, i2c)

    def get_area_rf_protection(self, area: int = 1) -> int:
        """
        label:
            en: '%1 RF protection of area (1-4) %2'
        params:
            area:
                name: area
                type: int
                default: '1'
                field: number
        """
        return self._driver.get_area_protection(area)[0]

    def get_area_i2c_protection(self, area: int = 1) -> int:
        """
        label:
            en: '%1 I2C protection of area (1-4) %2'
        params:
            area:
                name: area
                type: int
                default: '1'
                field: number
        """
        return self._driver.get_area_protection(area)[1]

    def set_gpo(self, sources: int = 8, enable: int = 1):
        """
        label:
            en: '%1 set GPO sources %2 enable (1/0) %3'
        params:
            sources:
                name: sources
                type: int
                default: '8'
                field: number
            enable:
                name: enable
                type: int
                default: '1'
                field: number
        """
        self._driver.set_gpo(sources, bool(enable))

    def get_gpo(self) -> int:
        """
        label:
            en: '%1 GPO register'
        """
        return self._driver.get_gpo()

    def set_gpo_runtime(self, enable: int = 1):
        """
        label:
            en: '%1 GPO output enable (1/0) %2'
        params:
            enable:
                name: enable
                type: int
                default: '1'
                field: number
        """
        self._driver.set_gpo_runtime(bool(enable))

    def set_interrupt_pulse(self, value: int = 3):
        """
        label:
            en: '%1 GPO pulse (0-7) %2'
        params:
            value:
                name: value
                type: int
                default: '3'
                field: number
        """
        self._driver.set_interrupt_pulse(value)

    def field_present(self) -> bool:
        """
        label:
            en: '%1 RF field present'
        """
        return self._driver.field_present()

    def set_energy_harvesting(self, enabled: int = 0, forced: int = 0):
        """
        label:
            en: '%1 energy harvesting (1/0) %2 forced after boot (1/0) %3'
        params:
            enabled:
                name: enabled
                type: int
                default: '0'
                field: number
            forced:
                name: forced
                type: int
                default: '0'
                field: number
        """
        self._driver.set_energy_harvesting(bool(enabled), bool(forced))

    def set_rf_state(self, disable: int = 0, sleep: int = 0):
        """
        label:
            en: '%1 RF disable (1/0) %2 sleep (1/0) %3'
        params:
            disable:
                name: disable
                type: int
                default: '0'
                field: number
            sleep:
                name: sleep
                type: int
                default: '0'
                field: number
        """
        self._driver.set_rf_state(bool(disable), bool(sleep))

    def enable_mailbox(self):
        """
        label:
            en: '%1 enable mailbox'
        """
        self._driver.enable_mailbox()

    def disable_mailbox(self):
        """
        label:
            en: '%1 disable mailbox'
        """
        self._driver.disable_mailbox()

    def mailbox_has_rf_message(self) -> bool:
        """
        label:
            en: '%1 mailbox has RF message'
        """
        return self._driver.mailbox_status()['rf_put']

    def mailbox_has_host_message(self) -> bool:
        """
        label:
            en: '%1 mailbox has host message'
        """
        return self._driver.mailbox_status()['host_put']

    def mailbox_write(self, data: str = '70696E67'):
        """
        label:
            en: '%1 mailbox write (hex) %2'
        params:
            data:
                name: data
                type: str
                default: '70696E67'
        """
        self._driver.mailbox_write(bytes.fromhex(data))

    def mailbox_read(self) -> str:
        """
        label:
            en: '%1 mailbox read -> hex'
        """
        return self._driver.mailbox_read().hex()

    def set_mailbox_watchdog(self, value: int = 0):
        """
        label:
            en: '%1 mailbox watchdog (0-7) %2'
        params:
            value:
                name: value
                type: int
                default: '0'
                field: number
        """
        self._driver.set_mailbox_watchdog(value)

    def lock_ccfile(self, lock: int = 1):
        """
        label:
            en: '%1 lock CC file (1/0) %2'
        params:
            lock:
                name: lock
                type: int
                default: '1'
                field: number
        """
        self._driver.lock_ccfile(bool(lock))

    def write_ndef(self, message: str = 'D101055402656E4869'):
        """
        label:
            en: '%1 write NDEF message (hex) %2'
        params:
            message:
                name: message
                type: str
                default: 'D101055402656E4869'
        """
        self._driver.write_ndef(bytes.fromhex(message))

    def read_ndef(self) -> str:
        """
        label:
            en: '%1 read NDEF message -> hex'
        """
        return self._driver.read_ndef().hex()

    def wait_write_complete(self):
        """
        label:
            en: '%1 wait for write cycle'
        """
        self._driver.wait_write_complete()

    def poll_interrupt(self) -> int:
        """
        label:
            en: '%1 interrupt sources'
        """
        return self._driver.poll_interrupt()

    def enable_interrupt(self, source: int = 16):
        """
        label:
            en: '%1 enable interrupt source %2'
        params:
            source:
                name: source
                type: int
                default: '16'
                field: number
        """
        self._driver.enable_interrupt(source)

    def disable_interrupt(self, source: int = 16):
        """
        label:
            en: '%1 disable interrupt source %2'
        params:
            source:
                name: source
                type: int
                default: '16'
                field: number
        """
        self._driver.disable_interrupt(source)
