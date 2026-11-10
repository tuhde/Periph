"""AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers).

Switch charger (up to 1.5 A), four buck converters and eleven LDOs, a 14-bit
ADC, a fuel gauge with percentage output, a power-key controller, watchdog,
JEITA protection and a 24-source interrupt controller. Communicates over I²C
at address 0x34 (the datasheet's 8-bit bytes 0x68 / 0x69).

Charger, rail and power-key settings are partly eFuse-programmed per product,
so the driver never touches them at construction and every setter uses a
read-modify-write that preserves the bits it does not own.

The driver is platform-agnostic — it only calls the register connection
interface. The same file is used on MicroPython, CircuitPython, and Linux.
"""

import math

# Register addresses.
_REG_STATUS1 = 0x00
_REG_STATUS2 = 0x01
_REG_DATA_BUFFER0 = 0x04
_REG_COMMON_CONFIG = 0x10
_REG_BATFET_CTRL = 0x12
_REG_DIE_TEMP_CTRL = 0x13
_REG_MIN_VSYS = 0x14
_REG_VINDPM = 0x15
_REG_IINLIM = 0x16
_REG_GAUGE_RESET = 0x17
_REG_MODULE_ENABLE = 0x18
_REG_WATCHDOG_CTRL = 0x19
_REG_LOWBAT_WARN = 0x1A
_REG_GPIO1_CTRL = 0x1B
_REG_PWRON_STATUS = 0x20
_REG_PWROFF_STATUS = 0x21
_REG_PWROFF_EN = 0x22
_REG_DCDC_PROT = 0x23
_REG_VOFF = 0x24
_REG_PWROK_CTRL = 0x25
_REG_SLEEP_CTRL = 0x26
_REG_PWRKEY_LEVEL = 0x27
_REG_FAST_PWRON0 = 0x28
_REG_ADC_ENABLE = 0x30
_REG_VBAT_H = 0x34
_REG_TS_H = 0x36
_REG_VBUS_H = 0x38
_REG_VSYS_H = 0x3A
_REG_TDIE_H = 0x3C
_REG_IRQ_EN0 = 0x40
_REG_IRQ_STATUS0 = 0x48
_REG_TS_CTRL = 0x50
_REG_TS_HYS_L2H = 0x52
_REG_TS_HYS_H2L = 0x53
_REG_VLTF_CHG = 0x54
_REG_VHTF_CHG = 0x55
_REG_VLTF_WORK = 0x56
_REG_VHTF_WORK = 0x57
_REG_JEITA_EN = 0x58
_REG_JEITA_CV = 0x59
_REG_JEITA_COOL = 0x5A
_REG_JEITA_WARM = 0x5B
_REG_IPRECHG = 0x61
_REG_ICC = 0x62
_REG_ITERM = 0x63
_REG_CV = 0x64
_REG_THERMAL_REG = 0x65
_REG_CHG_TIMER = 0x67
_REG_BAT_DETECT = 0x68
_REG_CHGLED_CTRL = 0x69
_REG_BTN_BAT_VTERM = 0x6A
_REG_DCDC_EN = 0x80
_REG_DCDC_PWM = 0x81
_REG_LDO_EN0 = 0x90
_REG_LDO_EN1 = 0x91
_REG_BAT_PERCENT = 0xA4

# Rail table: name -> (enable register, enable bit, voltage register, kind).
_RAILS = {
    'dcdc1': (0x80, 0, 0x82, 'dcdc1'),
    'dcdc2': (0x80, 1, 0x83, 'dcdc23'),
    'dcdc3': (0x80, 2, 0x84, 'dcdc23'),
    'dcdc4': (0x80, 3, 0x85, 'dcdc4'),
    'aldo1': (0x90, 0, 0x92, 'ldo100'),
    'aldo2': (0x90, 1, 0x93, 'ldo100'),
    'aldo3': (0x90, 2, 0x94, 'ldo100'),
    'aldo4': (0x90, 3, 0x95, 'ldo100'),
    'bldo1': (0x90, 4, 0x96, 'ldo100'),
    'bldo2': (0x90, 5, 0x97, 'ldo100'),
    'cpusldo': (0x90, 6, 0x98, 'ldo50'),
    'dldo1': (0x90, 7, 0x99, 'dldo1'),
    'dldo2': (0x91, 0, 0x9A, 'ldo50'),
}

# Per-kind voltage encoding: (start V, step V, max code).
_RAIL_KINDS = {
    'dcdc1': (1.5, 0.1, 19),
    'ldo100': (0.5, 0.1, 30),
    'ldo50': (0.5, 0.05, 19),
    'dldo1': (0.5, 0.1, 28),
}

# Maximum ICC (A) per CV code (datasheet Table 5-5).
_ICC_LIMIT = {1: 1.5, 2: 1.5, 3: 1.4, 4: 1.1, 5: 1.0}
_CV_CODES = ((4.0, 1), (4.1, 2), (4.2, 3), (4.35, 4), (4.4, 5))
_IINLIM_CODES = ((0.1, 0), (0.5, 1), (0.9, 2), (1.0, 3), (1.5, 4), (2.0, 5))

# Fast power-on sequence registers: rail -> (register offset from 0x28, shift).
_FAST_RAILS = {
    'dcdc1': (0, 0), 'dcdc2': (0, 2), 'dcdc3': (0, 4), 'dcdc4': (0, 6),
    'aldo1': (1, 2), 'aldo2': (1, 4), 'aldo3': (1, 6),
    'aldo4': (2, 0), 'bldo1': (2, 2), 'bldo2': (2, 4), 'cpusldo': (2, 6),
    'dldo1': (3, 0), 'dldo2': (3, 2),
}


def _delay_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _round(x):
    return int(math.floor(x + 0.5))


def _step_code(value, start, step, max_code, what):
    # Nearest step code, raising when the value is outside the range.
    code = _round((value - start) / step)
    if code < 0 or code > max_code or abs(value - (start + code * step)) > step / 2 + 1e-9:
        raise ValueError('{} out of range: {}'.format(what, value))
    return code


def _lookup(value, table, what):
    for key, code in table:
        if abs(key - value) < 1e-6:
            return code
    raise ValueError('{} unsupported value: {}'.format(what, value))


def _rail_code(kind, volts, name):
    if kind in _RAIL_KINDS:
        start, step, max_code = _RAIL_KINDS[kind]
        return _step_code(volts, start, step, max_code, name + ' voltage')
    top = 87 if kind == 'dcdc23' else 102
    if volts <= 1.21:
        return _step_code(volts, 0.5, 0.01, 70, name + ' voltage')
    return 71 + _step_code(volts, 1.22, 0.02, top - 71, name + ' voltage')


def _rail_volts(kind, code):
    if kind in _RAIL_KINDS:
        start, step, _ = _RAIL_KINDS[kind]
        return start + code * step
    if code <= 70:
        return 0.5 + code * 0.01
    return 1.22 + (code - 71) * 0.02


class AXP2101Minimal:
    """AXP2101 PMIC — minimal interface.

    Battery and power-source monitoring. At construction the ADC channels
    (VBAT, TS, VBUS, VSYS, TDIE) and the fuel gauge are enabled; charger,
    input limits, rails, power key, interrupts and watchdog keep their
    power-on (eFuse) configuration.

    Attributes:
        CHARGE_TRICKLE: Charge state 0, trickle charge.
        CHARGE_PRE: Charge state 1, pre-charge.
        CHARGE_CC: Charge state 2, constant current.
        CHARGE_CV: Charge state 3, constant voltage.
        CHARGE_DONE: Charge state 4, charge done.
        CHARGE_NONE: Charge state 5, not charging.
    """

    CHARGE_TRICKLE = 0
    CHARGE_PRE = 1
    CHARGE_CC = 2
    CHARGE_CV = 3
    CHARGE_DONE = 4
    CHARGE_NONE = 5

    def __init__(self, connection):
        """Initialise the AXP2101.

        Args:
            connection: Register connection bound to I²C address 0x34.

        Raises:
            OSError: If the bus check (read of STATUS1) fails.
        """
        self._connection = connection
        try:
            self._read(_REG_STATUS1, 1)
        except OSError as e:
            raise OSError('AXP2101 not responding at I2C 0x34: {}'.format(e))
        adc = self._read_reg(_REG_ADC_ENABLE)
        self._write_reg(_REG_ADC_ENABLE, adc | 0x1F)
        mod = self._read_reg(_REG_MODULE_ENABLE)
        self._write_reg(_REG_MODULE_ENABLE, mod | 0x08)
        _delay_ms(20)

    def _read(self, reg, n):
        try:
            return self._connection.read_reg(reg, n)
        except OSError:
            return self._connection.read_reg(reg, n)

    def _write(self, reg, value):
        try:
            self._connection.write_reg(reg, value)
        except OSError:
            self._connection.write_reg(reg, value)

    def _read_reg(self, reg):
        return self._read(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._write(reg, value & 0xFF)

    def _rmw(self, reg, mask, value):
        # Replace only the bits in mask; everything else is preserved.
        cur = self._read_reg(reg)
        self._write_reg(reg, (cur & ~mask) | (value & mask))

    def _command(self, reg, bit):
        # Self-clearing command: write the current value with the bit set.
        cur = self._read_reg(reg)
        self._write_reg(reg, cur | bit)

    def _adc14(self, reg):
        # High byte first, then the low byte (datasheet 6.10).
        hi = self._read_reg(reg)
        lo = self._read_reg(reg + 1)
        return ((hi & 0x3F) << 8) | lo

    def battery_voltage(self):
        """Read the battery voltage.

        Returns:
            float: Battery voltage in volts (1 mV / LSB).
        """
        return self._adc14(_REG_VBAT_H) * 0.001

    def battery_percent(self):
        """Read the fuel-gauge state of charge.

        Returns:
            int: State of charge, 0-100 %.
        """
        return self._read_reg(_REG_BAT_PERCENT) & 0x7F

    def charge_state(self):
        """Read the charger state machine.

        Returns:
            int: One of CHARGE_TRICKLE, CHARGE_PRE, CHARGE_CC, CHARGE_CV,
            CHARGE_DONE, CHARGE_NONE (STATUS2 bits 2:0).
        """
        return self._read_reg(_REG_STATUS2) & 0x07

    def vbus_present(self):
        """Check whether a good input source is connected.

        Returns:
            bool: True when STATUS1.VBUS_GOOD is set.
        """
        return bool(self._read_reg(_REG_STATUS1) & 0x20)


class AXP2101Full(AXP2101Minimal):
    """AXP2101 PMIC — full interface.

    Adds the remaining ADC channels, charger, battery-temperature, power
    rail, power-key, sleep, watchdog, fuel-gauge and interrupt control.

    Dangerous operations: disabling or re-voltaging the rail that powers the
    host (commonly DCDC1 or DCDC3), power_off(), power-off thresholds, charge
    voltage / current beyond the cell's limits and set_batfet_off_enable()
    can power the board down or damage the battery. The application must
    know its cell and load.
    """

    # Interrupt source mask: IRQ_STATUS0 in bits 7:0, STATUS1 in 15:8, STATUS2 in 23:16.
    SOURCE_BAT_UNDER_TEMP_WORK = 0x000001
    SOURCE_BAT_OVER_TEMP_WORK = 0x000002
    SOURCE_BAT_UNDER_TEMP_CHG = 0x000004
    SOURCE_BAT_OVER_TEMP_CHG = 0x000008
    SOURCE_GAUGE_NEW_SOC = 0x000010
    SOURCE_GAUGE_WDT = 0x000020
    SOURCE_SOC_WARN_L1 = 0x000040
    SOURCE_SOC_WARN_L2 = 0x000080
    SOURCE_PWRON_POS_EDGE = 0x000100
    SOURCE_PWRON_NEG_EDGE = 0x000200
    SOURCE_PWRON_LONG = 0x000400
    SOURCE_PWRON_SHORT = 0x000800
    SOURCE_BAT_REMOVE = 0x001000
    SOURCE_BAT_INSERT = 0x002000
    SOURCE_VBUS_REMOVE = 0x004000
    SOURCE_VBUS_INSERT = 0x008000
    SOURCE_BAT_OVER_VOLTAGE = 0x010000
    SOURCE_CHARGER_TIMER = 0x020000
    SOURCE_DIE_OVER_TEMP_L1 = 0x040000
    SOURCE_CHARGE_START = 0x080000
    SOURCE_CHARGE_DONE = 0x100000
    SOURCE_LDO_OVER_CURRENT = 0x400000
    SOURCE_WATCHDOG_EXPIRE = 0x800000

    def __init__(self, connection):
        """Initialise the AXP2101 (same sequence as AXP2101Minimal).

        Args:
            connection: Register connection bound to I²C address 0x34.
        """
        AXP2101Minimal.__init__(self, connection)
        self._int_callback = None
        self._int_handler = None
        self._poll_stop = None

    # --- Measurements -----------------------------------------------------

    def vbus_voltage(self):
        """Read the VBUS voltage.

        Returns:
            float: VBUS voltage in volts.
        """
        return self._adc14(_REG_VBUS_H) * 0.001

    def system_voltage(self):
        """Read the system (VSYS) voltage.

        Returns:
            float: VSYS voltage in volts.
        """
        return self._adc14(_REG_VSYS_H) * 0.001

    def ts_voltage(self):
        """Read the TS pin voltage.

        Returns:
            float: TS voltage in volts (0.5 mV / LSB).
        """
        return self._adc14(_REG_TS_H) * 0.0005

    def die_temperature(self):
        """Read the die temperature.

        The conversion is the vendor XPowersLib formula, not from the
        datasheet; verify on hardware.

        Returns:
            float: Die temperature in °C.
        """
        return 22 + (7274 - self._adc14(_REG_TDIE_H)) / 20.0

    def read_all(self):
        """Read every measurement in one call.

        Returns:
            dict: battery_voltage, vbus_voltage, system_voltage, ts_voltage,
            die_temperature, battery_percent, charge_state, vbus_present.
        """
        return {
            'battery_voltage': self.battery_voltage(),
            'vbus_voltage': self.vbus_voltage(),
            'system_voltage': self.system_voltage(),
            'ts_voltage': self.ts_voltage(),
            'die_temperature': self.die_temperature(),
            'battery_percent': self.battery_percent(),
            'charge_state': self.charge_state(),
            'vbus_present': self.vbus_present(),
        }

    def status(self):
        """Decode STATUS1 and STATUS2.

        Returns:
            dict: vbus_good, batfet_open, battery_present, battery_active,
            thermal_regulation, current_limit, battery_direction
            ('standby' / 'charge' / 'discharge' / 'reserved'), system_on,
            vindpm, charge_state.
        """
        s1 = self._read_reg(_REG_STATUS1)
        s2 = self._read_reg(_REG_STATUS2)
        direction = ('standby', 'charge', 'discharge', 'reserved')[(s2 >> 5) & 3]
        return {
            'vbus_good': bool(s1 & 0x20),
            'batfet_open': bool(s1 & 0x10),
            'battery_present': bool(s1 & 0x08),
            'battery_active': bool(s1 & 0x04),
            'thermal_regulation': bool(s1 & 0x02),
            'current_limit': bool(s1 & 0x01),
            'battery_direction': direction,
            'system_on': bool(s2 & 0x10),
            'vindpm': bool(s2 & 0x08),
            'charge_state': s2 & 0x07,
        }

    # --- ADC ----------------------------------------------------------------

    def set_adc_channels(self, vbat=True, ts=True, vbus=True, vsys=True, tdie=True, gpadc=False):
        """Select the enabled ADC channels (ADC_ENABLE).

        Args:
            vbat: Battery voltage channel.
            ts: TS pin channel.
            vbus: VBUS voltage channel.
            vsys: System voltage channel.
            tdie: Die temperature channel.
            gpadc: General-purpose ADC channel.
        """
        value = (int(bool(vbat)) | (int(bool(ts)) << 1) | (int(bool(vbus)) << 2)
                 | (int(bool(vsys)) << 3) | (int(bool(tdie)) << 4) | (int(bool(gpadc)) << 5))
        self._rmw(_REG_ADC_ENABLE, 0x3F, value)

    def set_adc_low_frequency(self, enabled):
        """Select slow (64 s) ADC sampling when powered off on battery only.

        Args:
            enabled: True for low-frequency sampling.
        """
        self._rmw(_REG_TS_H, 0x80, 0x80 if enabled else 0)

    # --- Charger ------------------------------------------------------------

    def set_charging(self, enabled):
        """Enable or disable main-cell charging (MODULE_ENABLE bit 1).

        Args:
            enabled: True to charge.
        """
        self._rmw(_REG_MODULE_ENABLE, 0x02, 0x02 if enabled else 0)

    def set_charge_voltage(self, volts):
        """Set the charge target voltage.

        Args:
            volts: 4.0, 4.1, 4.2, 4.35 or 4.4 V (4.35 / 4.4 V only for
                high-voltage cells).

        Raises:
            ValueError: For any other value.
        """
        self._rmw(_REG_CV, 0x07, _lookup(volts, _CV_CODES, 'charge voltage'))

    def set_charge_current(self, amps):
        """Set the constant-current charge current.

        Args:
            amps: 0-0.2 A in 25 mA steps, 0.2-1.5 A in 100 mA steps; capped
                by the limit of the currently programmed charge voltage.

        Raises:
            ValueError: Out of range or above the charge-voltage limit.
        """
        if amps < 0 or amps > 1.5 + 1e-9:
            raise ValueError('charge current out of range: {}'.format(amps))
        cv = self._read_reg(_REG_CV) & 0x07
        limit = _ICC_LIMIT.get(cv)
        if limit is not None and amps > limit + 1e-9:
            raise ValueError('charge current {} A exceeds {} A limit for this charge voltage'.format(amps, limit))
        if amps <= 0.25:
            code = min(8, _round(amps / 0.025))
        else:
            code = 8 + _round((amps - 0.2) / 0.1)
        self._rmw(_REG_ICC, 0x1F, code)

    def set_precharge_current(self, amps):
        """Set the pre-charge current.

        Args:
            amps: 0-0.2 A in 25 mA steps.
        """
        self._rmw(_REG_IPRECHG, 0x0F, _step_code(amps, 0.0, 0.025, 8, 'precharge current'))

    def set_termination(self, amps, enabled=True):
        """Set the charge termination current.

        Args:
            amps: 0-0.2 A in 25 mA steps.
            enabled: Termination enable.
        """
        code = _step_code(amps, 0.0, 0.025, 8, 'termination current')
        self._rmw(_REG_ITERM, 0x1F, code | (0x10 if enabled else 0))

    def set_input_current_limit(self, amps):
        """Set the input current limit.

        Args:
            amps: 0.1, 0.5, 0.9, 1.0, 1.5 or 2.0 A.
        """
        self._rmw(_REG_IINLIM, 0x07, _lookup(amps, _IINLIM_CODES, 'input current limit'))

    def set_input_voltage_limit(self, volts):
        """Set the input voltage limit (VINDPM).

        Args:
            volts: 3.88-5.08 V in 80 mV steps.
        """
        self._rmw(_REG_VINDPM, 0x0F, _step_code(volts, 3.88, 0.08, 15, 'input voltage limit'))

    def set_min_system_voltage(self, volts):
        """Set the minimum system voltage.

        Args:
            volts: 3.2-3.9 V in 100 mV steps.
        """
        self._rmw(_REG_MIN_VSYS, 0x07, _step_code(volts, 3.2, 0.1, 7, 'minimum system voltage'))

    def set_charge_timers(self, precharge_min=60, done_hours=12, precharge_enabled=True,
                          done_enabled=True, slow_in_dpm=True):
        """Configure the charger safety timers (CHG_TIMER).

        Args:
            precharge_min: Pre-charge timer, 40 / 50 / 60 / 70 min.
            done_hours: Charge-done timer, 5 / 8 / 12 / 20 h.
            precharge_enabled: Pre-charge timer enable.
            done_enabled: Charge-done timer enable.
            slow_in_dpm: Run timers at half speed during input DPM / thermal regulation.
        """
        pre = _lookup(precharge_min, ((40, 0), (50, 1), (60, 2), (70, 3)), 'precharge timer')
        done = _lookup(done_hours, ((5, 0), (8, 1), (12, 2), (20, 3)), 'done timer')
        value = (pre | (0x04 if precharge_enabled else 0) | (done << 4)
                 | (0x40 if done_enabled else 0) | (0x80 if slow_in_dpm else 0))
        self._rmw(_REG_CHG_TIMER, 0xF7, value)

    def set_thermal_regulation(self, celsius):
        """Set the charger thermal-regulation threshold.

        Args:
            celsius: 60, 80, 100 or 120 °C.
        """
        self._rmw(_REG_THERMAL_REG, 0x03,
                  _lookup(celsius, ((60, 0), (80, 1), (100, 2), (120, 3)), 'thermal regulation'))

    def set_die_protection(self, level1_celsius=125, detect=True):
        """Configure the die over-temperature level 1 (DIE_TEMP_CTRL).

        Args:
            level1_celsius: 115, 125 or 135 °C.
            detect: Die temperature detection enable.
        """
        code = _lookup(level1_celsius, ((115, 0), (125, 1), (135, 2)), 'die protection')
        self._rmw(_REG_DIE_TEMP_CTRL, 0x07, (code << 1) | (1 if detect else 0))

    def set_battery_detection(self, enabled):
        """Enable or disable battery detection (BAT_DETECT).

        Args:
            enabled: True to enable.
        """
        self._rmw(_REG_BAT_DETECT, 0x01, 1 if enabled else 0)

    def set_backup_charging(self, enabled, volts=2.9):
        """Configure charging of the backup (coin) cell.

        Args:
            enabled: True to charge the backup cell.
            volts: Termination voltage, 2.6-3.3 V in 100 mV steps.
        """
        code = _step_code(volts, 2.6, 0.1, 7, 'backup charge voltage')
        self._rmw(_REG_BTN_BAT_VTERM, 0x07, code)
        self._rmw(_REG_MODULE_ENABLE, 0x04, 0x04 if enabled else 0)

    def set_chgled(self, mode='type_a', output='hiz'):
        """Configure the CHGLED pin.

        Args:
            mode: 'off', 'type_a', 'type_b' or 'manual'.
            output: For 'manual': 'hiz', 'blink_1hz', 'blink_4hz' or 'low'.
        """
        modes = {'off': 0x00, 'type_a': 0x01, 'type_b': 0x03, 'manual': 0x05}
        outs = {'hiz': 0, 'blink_1hz': 1, 'blink_4hz': 2, 'low': 3}
        if mode not in modes:
            raise ValueError('unknown CHGLED mode: {}'.format(mode))
        if output not in outs:
            raise ValueError('unknown CHGLED output: {}'.format(output))
        value = modes[mode]
        if mode == 'manual':
            value |= outs[output] << 4
        elif mode == 'off':
            value = 0
        self._rmw(_REG_CHGLED_CTRL, 0x37, value)

    # --- Battery temperature ------------------------------------------------

    def configure_ts(self, battery_sensor=True, current_ua=50, source='when_adc'):
        """Configure the TS pin (TS_CTRL).

        Args:
            battery_sensor: True for the battery thermistor, False for an
                external input that does not affect the charger.
            current_ua: Injected current, 20 / 40 / 50 / 60 µA.
            source: Current-source mode: 'off', 'when_adc', 'when_ts' or 'always'.
        """
        sources = {'off': 0, 'when_adc': 1, 'when_ts': 2, 'always': 3}
        if source not in sources:
            raise ValueError('unknown TS source: {}'.format(source))
        cur = _lookup(current_ua, ((20, 0), (40, 1), (50, 2), (60, 3)), 'TS current')
        value = cur | (sources[source] << 2) | (0 if battery_sensor else 0x10)
        self._rmw(_REG_TS_CTRL, 0x1F, value)

    def set_ts_limits(self, charge_cold, charge_hot, work_cold, work_hot):
        """Set the battery-temperature limits as TS pin voltages.

        Args:
            charge_cold: Charge low-temperature limit in V (32 mV steps).
            charge_hot: Charge high-temperature limit in V (2 mV steps).
            work_cold: Discharge low-temperature limit in V (32 mV steps).
            work_hot: Discharge high-temperature limit in V (2 mV steps).
        """
        codes = (
            (_REG_VLTF_CHG, _step_code(charge_cold, 0.0, 0.032, 255, 'charge cold limit')),
            (_REG_VHTF_CHG, _step_code(charge_hot, 0.0, 0.002, 255, 'charge hot limit')),
            (_REG_VLTF_WORK, _step_code(work_cold, 0.0, 0.032, 255, 'work cold limit')),
            (_REG_VHTF_WORK, _step_code(work_hot, 0.0, 0.002, 255, 'work hot limit')),
        )
        for reg, code in codes:
            self._write_reg(reg, code)

    def set_ts_hysteresis(self, low_to_normal=0.032, high_to_normal=0.004):
        """Set the TS threshold hysteresis.

        Args:
            low_to_normal: Low to normal hysteresis in V (16 mV steps).
            high_to_normal: High to normal hysteresis in V (4 mV steps).
        """
        l2h = _step_code(low_to_normal, 0.0, 0.016, 255, 'TS low hysteresis')
        h2l = _step_code(high_to_normal, 0.0, 0.004, 255, 'TS high hysteresis')
        self._write_reg(_REG_TS_HYS_L2H, l2h)
        self._write_reg(_REG_TS_HYS_H2L, h2l)

    def configure_jeita(self, enabled, cool_voltage=None, warm_voltage=None, cool_half_current=True,
                        warm_half_current=False, cool_voltage_drop=0, warm_voltage_drop=1):
        """Configure the JEITA battery-temperature charging profile.

        Args:
            enabled: JEITA enable.
            cool_voltage: Cool threshold T2 as TS voltage in V (16 mV steps), or None to keep.
            warm_voltage: Warm threshold T3 as TS voltage in V (8 mV steps), or None to keep.
            cool_half_current: Halve the charge current in the cool range.
            warm_half_current: Halve the charge current in the warm range.
            cool_voltage_drop: Charge-voltage gears below the setting when cool, 0-2.
            warm_voltage_drop: Charge-voltage gears below the setting when warm, 0-2.
        """
        if cool_voltage_drop not in (0, 1, 2) or warm_voltage_drop not in (0, 1, 2):
            raise ValueError('JEITA voltage drop must be 0-2')
        value = (cool_voltage_drop | (warm_voltage_drop << 2)
                 | (0x10 if cool_half_current else 0) | (0x40 if warm_half_current else 0))
        self._rmw(_REG_JEITA_CV, 0x5F, value)
        if cool_voltage is not None:
            self._write_reg(_REG_JEITA_COOL, _step_code(cool_voltage, 0.0, 0.016, 255, 'JEITA cool'))
        if warm_voltage is not None:
            self._write_reg(_REG_JEITA_WARM, _step_code(warm_voltage, 0.0, 0.008, 255, 'JEITA warm'))
        self._rmw(_REG_JEITA_EN, 0x01, 1 if enabled else 0)

    # --- Power rails --------------------------------------------------------

    def _rail(self, name):
        if name not in _RAILS:
            raise ValueError('unknown rail: {}'.format(name))
        return _RAILS[name]

    def get_rail(self, name):
        """Read the state of a power rail.

        Args:
            name: 'dcdc1'..'dcdc4', 'aldo1'..'aldo4', 'bldo1', 'bldo2',
                'cpusldo', 'dldo1' or 'dldo2'.

        Returns:
            tuple: (enabled, volts).
        """
        en_reg, en_bit, v_reg, kind = self._rail(name)
        enabled = bool(self._read_reg(en_reg) & (1 << en_bit))
        mask = 0x7F if kind in ('dcdc23', 'dcdc4') else 0x1F
        return (enabled, _rail_volts(kind, self._read_reg(v_reg) & mask))

    def set_rail_voltage(self, name, volts):
        """Set a rail voltage, rounded to the nearest step.

        Args:
            name: Rail name as for get_rail().
            volts: Target voltage in V; outside the rail's range raises.

        Raises:
            ValueError: Unknown rail or voltage out of range.
        """
        _, _, v_reg, kind = self._rail(name)
        code = _rail_code(kind, volts, name)
        mask = 0x7F if kind in ('dcdc23', 'dcdc4') else 0x1F
        self._rmw(v_reg, mask, code)

    def set_rail_enabled(self, name, enabled):
        """Enable or disable a rail.

        Disabling the rail that powers the host cuts the host's power.

        Args:
            name: Rail name as for get_rail().
            enabled: True to enable.
        """
        en_reg, en_bit, _, _ = self._rail(name)
        self._rmw(en_reg, 1 << en_bit, (1 << en_bit) if enabled else 0)

    def configure_dcdc(self, name, always_pwm=False, dvm=None):
        """Configure one DCDC converter.

        Args:
            name: 'dcdc1'..'dcdc4'.
            always_pwm: Force PWM instead of automatic PWM/PFM.
            dvm: Dynamic voltage scaling for DCDC2 / DCDC3, None to keep.
        """
        if name not in ('dcdc1', 'dcdc2', 'dcdc3', 'dcdc4'):
            raise ValueError('not a DCDC: {}'.format(name))
        bit = 1 << (int(name[4]) + 1)
        self._rmw(_REG_DCDC_PWM, bit, bit if always_pwm else 0)
        if dvm is not None:
            if name not in ('dcdc2', 'dcdc3'):
                raise ValueError('DVM is only available on dcdc2 / dcdc3')
            self._rmw(_RAILS[name][2], 0x80, 0x80 if dvm else 0)

    def configure_dcdc_global(self, force_ccm=False, slow_ramp=False, frequency_spread=False,
                              spread_100khz=False, uvp_debounce_us=60):
        """Configure settings shared by all DCDC converters.

        Args:
            force_ccm: Force continuous conduction mode.
            slow_ramp: 31.25 µs per DVM step instead of 15.625 µs.
            frequency_spread: Enable switching-frequency spread.
            spread_100khz: Spread range 100 kHz instead of 50 kHz.
            uvp_debounce_us: Under-voltage debounce, 60 / 120 / 180 / 240 µs.
        """
        deb = _lookup(uvp_debounce_us, ((60, 0), (120, 1), (180, 2), (240, 3)), 'UVP debounce')
        self._rmw(_REG_DCDC_EN, 0x60, (0x40 if force_ccm else 0) | (0x20 if slow_ramp else 0))
        self._rmw(_REG_DCDC_PWM, 0xC3,
                  (0x80 if frequency_spread else 0) | (0x40 if spread_100khz else 0) | deb)

    # --- System control -----------------------------------------------------

    def set_off_discharge(self, enabled):
        """Enable output discharge when a rail is off (COMMON_CONFIG bit 5).

        Args:
            enabled: True to enable.
        """
        self._rmw(_REG_COMMON_CONFIG, 0x20, 0x20 if enabled else 0)

    def power_off(self):
        """Soft power-off: all outputs except RTCLDO turn off."""
        self._command(_REG_COMMON_CONFIG, 0x01)

    def restart(self):
        """Restart the system (power-off / power-on)."""
        self._command(_REG_COMMON_CONFIG, 0x02)

    def sleep(self):
        """Enter sleep. The host disables / re-voltages rails first."""
        self._command(_REG_SLEEP_CTRL, 0x01)

    def wakeup(self):
        """Wake up from sleep."""
        self._command(_REG_SLEEP_CTRL, 0x02)

    def configure_sleep(self, wake_on_irq=False, pwrok_low=True, restore_voltages=False):
        """Configure sleep / wake-up behaviour (SLEEP_CTRL).

        Args:
            wake_on_irq: IRQ pin held low for more than 16 ms wakes the PMU.
            pwrok_low: PWROK low during wake-up.
            restore_voltages: Wake with the voltages from before sleep.
        """
        value = (0x10 if wake_on_irq else 0) | (0x08 if pwrok_low else 0) | (0x04 if restore_voltages else 0)
        self._rmw(_REG_SLEEP_CTRL, 0x1C, value)

    def power_on_source(self):
        """Read the power-on source flags.

        Returns:
            int: PWRON_STATUS (bit 0 key, 1 IRQ pin, 2 VBUS, 3 battery > 3.3 V, 4 battery inserted, 5 EN mode).
        """
        return self._read_reg(_REG_PWRON_STATUS)

    def power_off_source(self):
        """Read the power-off source flags.

        Returns:
            int: PWROFF_STATUS (bit 0 key, 1 software, 2 EN low, 3 VSYS UV, 4 VBUS OV, 5 DCDC UV, 6 DCDC OV, 7 die over-temperature).
        """
        return self._read_reg(_REG_PWROFF_STATUS)

    def configure_power_key(self, on_ms=512, off_s=6, irq_s=1.5, long_press_off=True,
                            restart_instead=False, force_off_16s=False):
        """Configure the power-key timing and actions.

        Args:
            on_ms: Hold time to power on, 128 / 512 / 1000 / 2000 ms.
            off_s: Hold time to power off, 4 / 6 / 8 / 10 s.
            irq_s: Hold time for the long-press IRQ, 1 / 1.5 / 2 / 2.5 s.
            long_press_off: Power off when the key is held for off_s.
            restart_instead: Restart instead of powering off.
            force_off_16s: Holding the key for 16 s shuts the PMIC down.
        """
        on = _lookup(on_ms, ((128, 0), (512, 1), (1000, 2), (2000, 3)), 'power key on time')
        off = _lookup(off_s, ((4, 0), (6, 1), (8, 2), (10, 3)), 'power key off time')
        irq = _lookup(irq_s, ((1, 0), (1.5, 1), (2, 2), (2.5, 3)), 'power key irq time')
        self._rmw(_REG_PWRKEY_LEVEL, 0x3F, on | (off << 2) | (irq << 4))
        self._rmw(_REG_PWROFF_EN, 0x03, (0x02 if long_press_off else 0) | (0x01 if restart_instead else 0))
        self._rmw(_REG_COMMON_CONFIG, 0x04, 0x04 if force_off_16s else 0)

    def configure_power_off(self, die_over_temp=True, off_voltage=2.6, dcdc_ovp=True, dcdc_uvp=0x0F):
        """Configure the power-off sources and thresholds.

        Args:
            die_over_temp: Power off on die over-temperature level 2 (145 °C).
            off_voltage: System power-off threshold, 2.6-3.3 V in 100 mV steps.
            dcdc_ovp: DCDC over-voltage turns the PMIC off.
            dcdc_uvp: Mask of DCDC1-4 whose under-voltage turns the PMIC off (bits 0-3).
        """
        code = _step_code(off_voltage, 2.6, 0.1, 7, 'power-off voltage')
        self._rmw(_REG_PWROFF_EN, 0x04, 0x04 if die_over_temp else 0)
        self._rmw(_REG_VOFF, 0x07, code)
        self._rmw(_REG_DCDC_PROT, 0x2F, (0x20 if dcdc_ovp else 0) | (dcdc_uvp & 0x0F))

    def configure_pwrok(self, check=True, delay_ms=8, off_delay=True, reverse_off_sequence=False,
                        restart_on_low=False):
        """Configure the PWROK output.

        Args:
            check: Check PWROK after all outputs are valid.
            delay_ms: PWROK delay, 8 / 16 / 32 / 64 ms.
            off_delay: 4 ms power-off delay after PWROK disable.
            reverse_off_sequence: Power off in reverse start-up order.
            restart_on_low: PWROK pulled low restarts the system.
        """
        delay = _lookup(delay_ms, ((8, 0), (16, 1), (32, 2), (64, 3)), 'PWROK delay')
        value = (delay | (0x04 if reverse_off_sequence else 0) | (0x08 if off_delay else 0)
                 | (0x10 if check else 0))
        self._rmw(_REG_PWROK_CTRL, 0x1F, value)
        self._rmw(_REG_COMMON_CONFIG, 0x08, 0x08 if restart_on_low else 0)

    def set_batfet_off_enable(self, enabled):
        """Turn the BATFET on when powered off on battery only (BATFET_CTRL bit 3).

        Args:
            enabled: True to enable.
        """
        self._rmw(_REG_BATFET_CTRL, 0x08, 0x08 if enabled else 0)

    def configure_fast_poweron(self, poweron, wakeup, sequence=None):
        """Configure fast power-on / wake-up and the start sequence.

        Args:
            poweron: Fast power-on enable.
            wakeup: Fast wake-up enable.
            sequence: Optional dict rail -> start-sequence code 0-2 or None
                (disabled), e.g. {'dcdc1': 0, 'aldo1': 1}.
        """
        if sequence:
            for name in sequence:
                if name not in _FAST_RAILS:
                    raise ValueError('unknown rail: {}'.format(name))
                code = sequence[name]
                if code is None:
                    code = 3
                if code not in (0, 1, 2, 3):
                    raise ValueError('start-sequence code must be 0-2 or None')
                off, shift = _FAST_RAILS[name]
                self._rmw(_REG_FAST_PWRON0 + off, 3 << shift, code << shift)
        self._rmw(_REG_FAST_PWRON0 + 3, 0xC0, (0x80 if poweron else 0) | (0x40 if wakeup else 0))

    def configure_watchdog(self, enabled, timeout_s=64, action='irq'):
        """Configure the watchdog.

        Args:
            enabled: Watchdog enable.
            timeout_s: 1, 2, 4, 8, 16, 32, 64 or 128 s.
            action: 'irq', 'reset', 'reset_pwrok' or 'power_cycle'.
        """
        actions = {'irq': 0, 'reset': 1, 'reset_pwrok': 2, 'power_cycle': 3}
        if action not in actions:
            raise ValueError('unknown watchdog action: {}'.format(action))
        code = _lookup(timeout_s, ((1, 0), (2, 1), (4, 2), (8, 3), (16, 4), (32, 5), (64, 6), (128, 7)),
                       'watchdog timeout')
        self._rmw(_REG_WATCHDOG_CTRL, 0x37, code | (actions[action] << 4))
        self._rmw(_REG_MODULE_ENABLE, 0x01, 1 if enabled else 0)

    def feed_watchdog(self):
        """Clear (feed) the watchdog."""
        self._command(_REG_WATCHDOG_CTRL, 0x08)

    # --- Fuel gauge ---------------------------------------------------------

    def configure_battery_warning(self, level2=10, level1=1):
        """Set the low-battery warning levels (LOWBAT_WARN).

        Args:
            level2: Warning level 2, 5-20 %.
            level1: Warning level 1, 0-15 %.
        """
        if level2 < 5 or level2 > 20 or level1 < 0 or level1 > 15:
            raise ValueError('battery warning levels out of range')
        self._write_reg(_REG_LOWBAT_WARN, ((level2 - 5) << 4) | level1)

    def set_gauge_enabled(self, enabled):
        """Enable or disable the fuel gauge (MODULE_ENABLE bit 3).

        Args:
            enabled: True to enable.
        """
        self._rmw(_REG_MODULE_ENABLE, 0x08, 0x08 if enabled else 0)

    def reset_gauge(self, full=False):
        """Reset the fuel gauge.

        Args:
            full: True for the deeper reset that also resets gauge data beyond the registers.
        """
        self._command(_REG_GAUGE_RESET, 0x04 if full else 0x08)

    # --- GPIO1 and data buffer ---------------------------------------------

    def set_gpio1(self, low):
        """Drive the open-drain GPIO1 output.

        Args:
            low: True to drive low, False for Hi-Z.
        """
        self._rmw(_REG_GPIO1_CTRL, 0x0C, 0x04 if low else 0)

    def read_data_buffer(self, index):
        """Read one of the four general-purpose bytes.

        Args:
            index: 0-3.

        Returns:
            int: Stored byte.
        """
        if index < 0 or index > 3:
            raise ValueError('data buffer index must be 0-3')
        return self._read_reg(_REG_DATA_BUFFER0 + index)

    def write_data_buffer(self, index, value):
        """Write one of the four general-purpose bytes.

        Survives soft restarts, not a power-on reset.

        Args:
            index: 0-3.
            value: Byte to store.
        """
        if index < 0 or index > 3:
            raise ValueError('data buffer index must be 0-3')
        self._write_reg(_REG_DATA_BUFFER0 + index, value)

    # --- Interrupts ---------------------------------------------------------

    def enable_interrupt(self, source):
        """Enable interrupt sources (IRQ_EN0..2 read-modify-write).

        Args:
            source: Bitwise OR of SOURCE_* constants.
        """
        for k in range(3):
            bits = (source >> (8 * k)) & 0xFF
            if bits:
                self._rmw(_REG_IRQ_EN0 + k, bits, bits)

    def disable_interrupt(self, source):
        """Disable interrupt sources.

        Args:
            source: Bitwise OR of SOURCE_* constants.
        """
        for k in range(3):
            bits = (source >> (8 * k)) & 0xFF
            if bits:
                self._rmw(_REG_IRQ_EN0 + k, bits, 0)

    def poll_interrupt(self):
        """Read and clear the interrupt status.

        Writes back exactly the bits it saw set, so an event arriving
        between the read and the write is not lost.

        Returns:
            int: 24-bit status, IRQ_STATUS0 in bits 7:0, STATUS1 in 15:8, STATUS2 in 23:16.
        """
        status = 0
        for k in range(3):
            value = self._read_reg(_REG_IRQ_STATUS0 + k)
            if value:
                self._write_reg(_REG_IRQ_STATUS0 + k, value)
            status |= value << (8 * k)
        return status

    def on_interrupt(self, callback):
        """Subscribe to IRQ pin assertions.

        Uses the connection's INT pin (falling edge) when wired, otherwise a
        5 ms polling thread.

        Args:
            callback: Function called with the 24-bit status int.
        """
        self.off_interrupt()
        self._int_callback = callback
        int_pin = getattr(self._connection, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._int_handler = lambda pin=None: self._dispatch_int()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'FALLING', 0))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe from IRQ pin assertions."""
        self._int_callback = None
        int_pin = getattr(self._connection, 'int_pin', None)
        if self._int_handler is not None and int_pin is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(self._int_handler)
        self._int_handler = None
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None

    def _dispatch_int(self):
        cb = self._int_callback
        if cb is None:
            return
        status = self.poll_interrupt()
        if status:
            cb(status)

    def _start_polling_fallback(self):
        try:
            import _thread
            import time
        except ImportError:
            return
        stop = [False]
        self._poll_stop = stop

        def _loop():
            while not stop[0]:
                try:
                    self._dispatch_int()
                except Exception:
                    pass
                if hasattr(time, 'sleep_ms'):
                    time.sleep_ms(5)
                else:
                    time.sleep(0.005)
        _thread.start_new_thread(_loop, ())
