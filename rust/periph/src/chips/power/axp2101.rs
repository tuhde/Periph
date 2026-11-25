//! AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers).
//!
//! Switch charger (up to 1.5 A), four buck converters and eleven LDOs, a
//! 14-bit ADC, a fuel gauge with percentage output, a power-key controller,
//! watchdog, JEITA protection and a 24-source interrupt controller.
//! Communicates over I²C at address `0x34`.
//!
//! Charger, rail and power-key settings are partly eFuse-programmed per
//! product, so construction only enables the ADC channels and the fuel gauge
//! and every setter is a read-modify-write that preserves the bits it does
//! not own. The datasheet gives no conversion time: wait about 20 ms after
//! construction before the first ADC read (the driver is `no_std` and has no
//! delay of its own).
//!
//! Interrupts: [`Axp2101Full::poll_interrupt`] reads and clears the 24-bit
//! status; the caller wires it into an ISR on the IRQ pin or a polling loop.
//! Sources are the `SOURCE_*` constants of this module.

use embedded_hal::i2c::I2c;

use crate::connection::register::{read_register, write_register};

const REG_STATUS1: u8 = 0x00;
const REG_STATUS2: u8 = 0x01;
const REG_DATA_BUFFER0: u8 = 0x04;
const REG_COMMON_CONFIG: u8 = 0x10;
const REG_BATFET_CTRL: u8 = 0x12;
const REG_DIE_TEMP_CTRL: u8 = 0x13;
const REG_MIN_VSYS: u8 = 0x14;
const REG_VINDPM: u8 = 0x15;
const REG_IINLIM: u8 = 0x16;
const REG_GAUGE_RESET: u8 = 0x17;
const REG_MODULE_ENABLE: u8 = 0x18;
const REG_WATCHDOG_CTRL: u8 = 0x19;
const REG_LOWBAT_WARN: u8 = 0x1A;
const REG_GPIO1_CTRL: u8 = 0x1B;
const REG_PWRON_STATUS: u8 = 0x20;
const REG_PWROFF_STATUS: u8 = 0x21;
const REG_PWROFF_EN: u8 = 0x22;
const REG_DCDC_PROT: u8 = 0x23;
const REG_VOFF: u8 = 0x24;
const REG_PWROK_CTRL: u8 = 0x25;
const REG_SLEEP_CTRL: u8 = 0x26;
const REG_PWRKEY_LEVEL: u8 = 0x27;
const REG_FAST_PWRON0: u8 = 0x28;
const REG_ADC_ENABLE: u8 = 0x30;
const REG_VBAT_H: u8 = 0x34;
const REG_TS_H: u8 = 0x36;
const REG_VBUS_H: u8 = 0x38;
const REG_VSYS_H: u8 = 0x3A;
const REG_TDIE_H: u8 = 0x3C;
const REG_IRQ_EN0: u8 = 0x40;
const REG_IRQ_STATUS0: u8 = 0x48;
const REG_TS_CTRL: u8 = 0x50;
const REG_TS_HYS_L2H: u8 = 0x52;
const REG_TS_HYS_H2L: u8 = 0x53;
const REG_VLTF_CHG: u8 = 0x54;
const REG_VHTF_CHG: u8 = 0x55;
const REG_VLTF_WORK: u8 = 0x56;
const REG_VHTF_WORK: u8 = 0x57;
const REG_JEITA_EN: u8 = 0x58;
const REG_JEITA_CV: u8 = 0x59;
const REG_JEITA_COOL: u8 = 0x5A;
const REG_JEITA_WARM: u8 = 0x5B;
const REG_IPRECHG: u8 = 0x61;
const REG_ICC: u8 = 0x62;
const REG_ITERM: u8 = 0x63;
const REG_CV: u8 = 0x64;
const REG_THERMAL_REG: u8 = 0x65;
const REG_CHG_TIMER: u8 = 0x67;
const REG_BAT_DETECT: u8 = 0x68;
const REG_CHGLED_CTRL: u8 = 0x69;
const REG_BTN_BAT_VTERM: u8 = 0x6A;
const REG_DCDC_EN: u8 = 0x80;
const REG_DCDC_PWM: u8 = 0x81;
const REG_BAT_PERCENT: u8 = 0xA4;

/// Charge state 0: trickle charge.
pub const CHARGE_TRICKLE: u8 = 0;
/// Charge state 1: pre-charge.
pub const CHARGE_PRE: u8 = 1;
/// Charge state 2: constant current.
pub const CHARGE_CC: u8 = 2;
/// Charge state 3: constant voltage.
pub const CHARGE_CV: u8 = 3;
/// Charge state 4: charge done.
pub const CHARGE_DONE: u8 = 4;
/// Charge state 5: not charging.
pub const CHARGE_NONE: u8 = 5;

/// Battery under-temperature while discharging.
pub const SOURCE_BAT_UNDER_TEMP_WORK: u32 = 0x000001;
/// Battery over-temperature while discharging.
pub const SOURCE_BAT_OVER_TEMP_WORK: u32 = 0x000002;
/// Battery under-temperature while charging.
pub const SOURCE_BAT_UNDER_TEMP_CHG: u32 = 0x000004;
/// Battery over-temperature while charging.
pub const SOURCE_BAT_OVER_TEMP_CHG: u32 = 0x000008;
/// Gauge new state of charge.
pub const SOURCE_GAUGE_NEW_SOC: u32 = 0x000010;
/// Gauge watchdog timeout.
pub const SOURCE_GAUGE_WDT: u32 = 0x000020;
/// State of charge at warning level 1 / shutdown level.
pub const SOURCE_SOC_WARN_L1: u32 = 0x000040;
/// State of charge at warning level 2.
pub const SOURCE_SOC_WARN_L2: u32 = 0x000080;
/// Power key released.
pub const SOURCE_PWRON_POS_EDGE: u32 = 0x000100;
/// Power key pressed.
pub const SOURCE_PWRON_NEG_EDGE: u32 = 0x000200;
/// Power key long press.
pub const SOURCE_PWRON_LONG: u32 = 0x000400;
/// Power key short press.
pub const SOURCE_PWRON_SHORT: u32 = 0x000800;
/// Battery removed.
pub const SOURCE_BAT_REMOVE: u32 = 0x001000;
/// Battery inserted.
pub const SOURCE_BAT_INSERT: u32 = 0x002000;
/// VBUS removed.
pub const SOURCE_VBUS_REMOVE: u32 = 0x004000;
/// VBUS inserted.
pub const SOURCE_VBUS_INSERT: u32 = 0x008000;
/// Battery over-voltage protection.
pub const SOURCE_BAT_OVER_VOLTAGE: u32 = 0x010000;
/// Charger safety timer expired.
pub const SOURCE_CHARGER_TIMER: u32 = 0x020000;
/// Die over-temperature level 1.
pub const SOURCE_DIE_OVER_TEMP_L1: u32 = 0x040000;
/// Charging started.
pub const SOURCE_CHARGE_START: u32 = 0x080000;
/// Charging done.
pub const SOURCE_CHARGE_DONE: u32 = 0x100000;
/// LDO over-current.
pub const SOURCE_LDO_OVER_CURRENT: u32 = 0x400000;
/// Watchdog expired.
pub const SOURCE_WATCHDOG_EXPIRE: u32 = 0x800000;

/// Error type for [`Axp2101Full`] configuration methods: wraps the underlying
/// bus error plus out-of-range argument validation.
#[derive(Debug)]
pub enum Axp2101Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// An argument was outside the range the chip can represent.
    InvalidArgument,
}

impl<E> From<E> for Axp2101Error<E> {
    fn from(e: E) -> Self {
        Axp2101Error::Bus(e)
    }
}

/// Every measurement returned by [`Axp2101Full::read_all`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Axp2101Readings {
    /// Battery voltage in V.
    pub battery_voltage: f32,
    /// VBUS voltage in V.
    pub vbus_voltage: f32,
    /// System voltage in V.
    pub system_voltage: f32,
    /// TS pin voltage in V.
    pub ts_voltage: f32,
    /// Die temperature in °C.
    pub die_temperature: f32,
    /// Fuel-gauge state of charge, 0-100 %.
    pub battery_percent: u8,
    /// One of the `CHARGE_*` constants.
    pub charge_state: u8,
    /// True when a good input source is connected.
    pub vbus_present: bool,
}

/// Decoded STATUS1 / STATUS2 returned by [`Axp2101Full::status`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Axp2101Status {
    /// Input source good.
    pub vbus_good: bool,
    /// BATFET open.
    pub batfet_open: bool,
    /// Battery present.
    pub battery_present: bool,
    /// Battery in active mode.
    pub battery_active: bool,
    /// Charger in thermal regulation.
    pub thermal_regulation: bool,
    /// In current limit.
    pub current_limit: bool,
    /// `"standby"`, `"charge"`, `"discharge"` or `"reserved"`.
    pub battery_direction: &'static str,
    /// System powered on.
    pub system_on: bool,
    /// Input voltage regulation active.
    pub vindpm: bool,
    /// One of the `CHARGE_*` constants.
    pub charge_state: u8,
}

// (enable register, enable bit, voltage register, kind, voltage mask)
fn rail_info(name: &str) -> Option<(u8, u8, u8, u8, u8)> {
    // kind: 0 dcdc1, 1 dcdc2/3, 2 dcdc4, 3 ldo 100 mV, 4 ldo 50 mV, 5 dldo1
    Some(match name {
        "dcdc1" => (0x80, 0, 0x82, 0, 0x1F),
        "dcdc2" => (0x80, 1, 0x83, 1, 0x7F),
        "dcdc3" => (0x80, 2, 0x84, 1, 0x7F),
        "dcdc4" => (0x80, 3, 0x85, 2, 0x7F),
        "aldo1" => (0x90, 0, 0x92, 3, 0x1F),
        "aldo2" => (0x90, 1, 0x93, 3, 0x1F),
        "aldo3" => (0x90, 2, 0x94, 3, 0x1F),
        "aldo4" => (0x90, 3, 0x95, 3, 0x1F),
        "bldo1" => (0x90, 4, 0x96, 3, 0x1F),
        "bldo2" => (0x90, 5, 0x97, 3, 0x1F),
        "cpusldo" => (0x90, 6, 0x98, 4, 0x1F),
        "dldo1" => (0x90, 7, 0x99, 5, 0x1F),
        "dldo2" => (0x91, 0, 0x9A, 4, 0x1F),
        _ => return None,
    })
}

// (register offset from 0x28, shift)
fn fast_rail(name: &str) -> Option<(u8, u8)> {
    Some(match name {
        "dcdc1" => (0, 0), "dcdc2" => (0, 2), "dcdc3" => (0, 4), "dcdc4" => (0, 6),
        "aldo1" => (1, 2), "aldo2" => (1, 4), "aldo3" => (1, 6),
        "aldo4" => (2, 0), "bldo1" => (2, 2), "bldo2" => (2, 4), "cpusldo" => (2, 6),
        "dldo1" => (3, 0), "dldo2" => (3, 2),
        _ => return None,
    })
}

fn round(x: f64) -> i32 {
    libm::floor(x + 0.5) as i32
}

// Nearest step code, None outside the range.
fn step_code(value: f32, start: f64, step: f64, max_code: i32) -> Option<u8> {
    let v = value as f64;
    let code = round((v - start) / step);
    if code < 0 || code > max_code || libm::fabs(v - (start + code as f64 * step)) > step / 2.0 + 1e-4 {
        return None;
    }
    Some(code as u8)
}

// Index of `value` in `keys` (the register code of the enumerated settings).
fn index_of(value: f32, keys: &[f32]) -> Option<u8> {
    for (i, k) in keys.iter().enumerate() {
        if libm::fabsf(*k - value) < 1e-4 {
            return Some(i as u8);
        }
    }
    None
}

fn rail_code(kind: u8, volts: f32) -> Option<u8> {
    match kind {
        0 => step_code(volts, 1.5, 0.1, 19),
        3 => step_code(volts, 0.5, 0.1, 30),
        4 => step_code(volts, 0.5, 0.05, 19),
        5 => step_code(volts, 0.5, 0.1, 28),
        _ => {
            let top = if kind == 1 { 87 } else { 102 };
            if volts <= 1.21 {
                step_code(volts, 0.5, 0.01, 70)
            } else {
                step_code(volts, 1.22, 0.02, top - 71).map(|c| 71 + c)
            }
        }
    }
}

fn rail_volts(kind: u8, code: u8) -> f32 {
    let c = code as f64;
    (match kind {
        0 => 1.5 + c * 0.1,
        3 | 5 => 0.5 + c * 0.1,
        4 => 0.5 + c * 0.05,
        _ => if code <= 70 { 0.5 + c * 0.01 } else { 1.22 + (c - 71.0) * 0.02 },
    }) as f32
}

fn bit(on: bool, b: u8) -> u8 {
    if on { b } else { 0 }
}

/// AXP2101 minimal driver — battery and power-source monitoring.
///
/// Construction enables the ADC channels (VBAT, TS, VBUS, VSYS, TDIE) and the
/// fuel gauge; charger, rails, power key, interrupts and watchdog keep their
/// power-on (eFuse) configuration.
pub struct Axp2101Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Axp2101Minimal<I2C> {
    /// Create a new `Axp2101Minimal` and run the initialisation sequence:
    /// bus check (read of STATUS1), ADC channels on, fuel gauge on.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit I²C address (`0x34`).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut chip = Self { i2c, addr };
        chip.rd(REG_STATUS1)?;
        chip.rmw(REG_ADC_ENABLE, 0x1F, 0x1F)?;
        chip.rmw(REG_MODULE_ENABLE, 0x08, 0x08)?;
        Ok(chip)
    }

    // Read one register, retrying once on a bus error.
    fn rd(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        if read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut buf).is_err() {
            read_register(&mut self.i2c, self.addr, reg as u32, 1, &mut buf)?;
        }
        Ok(buf[0])
    }

    // Write one register, retrying once on a bus error.
    fn wr(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        if write_register(&mut self.i2c, self.addr, reg as u32, 1, &[value]).is_err() {
            write_register(&mut self.i2c, self.addr, reg as u32, 1, &[value])?;
        }
        Ok(())
    }

    // Replace only the bits in `mask`.
    fn rmw(&mut self, reg: u8, mask: u8, value: u8) -> Result<(), I2C::Error> {
        let cur = self.rd(reg)?;
        self.wr(reg, (cur & !mask) | (value & mask))
    }

    // Self-clearing command: write the current value with the bit set.
    fn command(&mut self, reg: u8, b: u8) -> Result<(), I2C::Error> {
        let cur = self.rd(reg)?;
        self.wr(reg, cur | b)
    }

    // 14-bit ADC pair, high byte first.
    fn adc14(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        let hi = self.rd(reg)?;
        let lo = self.rd(reg + 1)?;
        Ok((((hi & 0x3F) as u16) << 8) | lo as u16)
    }

    /// Read the battery voltage in V (1 mV / LSB).
    pub fn battery_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.adc14(REG_VBAT_H)? as f32 * 0.001)
    }

    /// Read the fuel-gauge state of charge, 0-100 %.
    pub fn battery_percent(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.rd(REG_BAT_PERCENT)? & 0x7F)
    }

    /// Read the charger state (STATUS2 bits 2:0), one of the `CHARGE_*` constants.
    pub fn charge_state(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.rd(REG_STATUS2)? & 0x07)
    }

    /// True when a good input source is connected (STATUS1.VBUS_GOOD).
    pub fn vbus_present(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.rd(REG_STATUS1)? & 0x20 != 0)
    }
}

/// AXP2101 full driver — extends [`Axp2101Minimal`] with the remaining ADC
/// channels, charger, battery-temperature, power rail, power-key, sleep,
/// watchdog, fuel-gauge and interrupt control.
///
/// Minimal's methods are re-exposed as one-line delegates (the Rust analog of
/// "Full never duplicates Minimal"). Dangerous operations: disabling or
/// re-voltaging the rail that powers the host (commonly DCDC1 or DCDC3),
/// [`power_off`](Self::power_off), the power-off thresholds, charge voltage /
/// current beyond the cell's limits and
/// [`set_batfet_off_enable`](Self::set_batfet_off_enable) can power the board
/// down or damage the battery.
pub struct Axp2101Full<I2C> {
    inner: Axp2101Minimal<I2C>,
}

type FullResult<T, I2C> = Result<T, Axp2101Error<<I2C as embedded_hal::i2c::ErrorType>::Error>>;

impl<I2C: I2c> Axp2101Full<I2C> {
    /// Create a new `Axp2101Full`; the initialisation sequence is the same as
    /// [`Axp2101Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Axp2101Minimal::new(i2c, addr)? })
    }

    /// Read the battery voltage in V.
    pub fn battery_voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.battery_voltage()
    }

    /// Read the fuel-gauge state of charge, 0-100 %.
    pub fn battery_percent(&mut self) -> Result<u8, I2C::Error> {
        self.inner.battery_percent()
    }

    /// Read the charger state, one of the `CHARGE_*` constants.
    pub fn charge_state(&mut self) -> Result<u8, I2C::Error> {
        self.inner.charge_state()
    }

    /// True when a good input source is connected.
    pub fn vbus_present(&mut self) -> Result<bool, I2C::Error> {
        self.inner.vbus_present()
    }

    /// Read the VBUS voltage in V.
    pub fn vbus_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.adc14(REG_VBUS_H)? as f32 * 0.001)
    }

    /// Read the system (VSYS) voltage in V.
    pub fn system_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.adc14(REG_VSYS_H)? as f32 * 0.001)
    }

    /// Read the TS pin voltage in V (0.5 mV / LSB).
    pub fn ts_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.adc14(REG_TS_H)? as f32 * 0.0005)
    }

    /// Read the die temperature in °C using the vendor XPowersLib formula
    /// (not from the datasheet; verify on hardware).
    pub fn die_temperature(&mut self) -> Result<f32, I2C::Error> {
        let raw = self.inner.adc14(REG_TDIE_H)? as f32;
        Ok(22.0 + (7274.0 - raw) / 20.0)
    }

    /// Read every measurement.
    pub fn read_all(&mut self) -> Result<Axp2101Readings, I2C::Error> {
        Ok(Axp2101Readings {
            battery_voltage: self.battery_voltage()?,
            vbus_voltage: self.vbus_voltage()?,
            system_voltage: self.system_voltage()?,
            ts_voltage: self.ts_voltage()?,
            die_temperature: self.die_temperature()?,
            battery_percent: self.battery_percent()?,
            charge_state: self.charge_state()?,
            vbus_present: self.vbus_present()?,
        })
    }

    /// Decode STATUS1 and STATUS2.
    pub fn status(&mut self) -> Result<Axp2101Status, I2C::Error> {
        let s1 = self.inner.rd(REG_STATUS1)?;
        let s2 = self.inner.rd(REG_STATUS2)?;
        Ok(Axp2101Status {
            vbus_good: s1 & 0x20 != 0,
            batfet_open: s1 & 0x10 != 0,
            battery_present: s1 & 0x08 != 0,
            battery_active: s1 & 0x04 != 0,
            thermal_regulation: s1 & 0x02 != 0,
            current_limit: s1 & 0x01 != 0,
            battery_direction: ["standby", "charge", "discharge", "reserved"][((s2 >> 5) & 3) as usize],
            system_on: s2 & 0x10 != 0,
            vindpm: s2 & 0x08 != 0,
            charge_state: s2 & 0x07,
        })
    }

    /// Select the enabled ADC channels (`ADC_ENABLE`).
    pub fn set_adc_channels(&mut self, vbat: bool, ts: bool, vbus: bool, vsys: bool, tdie: bool, gpadc: bool) -> Result<(), I2C::Error> {
        let v = bit(vbat, 1) | bit(ts, 2) | bit(vbus, 4) | bit(vsys, 8) | bit(tdie, 16) | bit(gpadc, 32);
        self.inner.rmw(REG_ADC_ENABLE, 0x3F, v)
    }

    /// Slow (64 s) ADC sampling when powered off on battery only (`TS_H` bit 7).
    pub fn set_adc_low_frequency(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_TS_H, 0x80, bit(enabled, 0x80))
    }

    /// Enable or disable main-cell charging (`MODULE_ENABLE` bit 1).
    pub fn set_charging(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_MODULE_ENABLE, 0x02, bit(enabled, 0x02))
    }

    /// Set the charge target voltage: 4.0, 4.1, 4.2, 4.35 or 4.4 V (the last
    /// two only for high-voltage cells).
    pub fn set_charge_voltage(&mut self, volts: f32) -> FullResult<(), I2C> {
        let i = index_of(volts, &[4.0, 4.1, 4.2, 4.35, 4.4]).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_CV, 0x07, i + 1)?)
    }

    /// Set the constant-current charge current: 0-0.2 A in 25 mA steps,
    /// 0.2-1.5 A in 100 mA steps, capped by the limit of the programmed
    /// charge voltage (1.5 A @ 4.0/4.1 V, 1.4 A @ 4.2 V, 1.1 A @ 4.35 V, 1.0 A @ 4.4 V).
    pub fn set_charge_current(&mut self, amps: f32) -> FullResult<(), I2C> {
        if amps < 0.0 || amps > 1.5 + 1e-4 {
            return Err(Axp2101Error::InvalidArgument);
        }
        let cv = self.inner.rd(REG_CV)? & 0x07;
        let limit = match cv { 1 | 2 => 1.5, 3 => 1.4, 4 => 1.1, 5 => 1.0, _ => 1.5 };
        if amps > limit + 1e-4 {
            return Err(Axp2101Error::InvalidArgument);
        }
        let a = amps as f64;
        let code = if a <= 0.25 { core::cmp::min(8, round(a / 0.025)) } else { 8 + round((a - 0.2) / 0.1) };
        Ok(self.inner.rmw(REG_ICC, 0x1F, code as u8)?)
    }

    /// Set the pre-charge current: 0-0.2 A in 25 mA steps.
    pub fn set_precharge_current(&mut self, amps: f32) -> FullResult<(), I2C> {
        let code = step_code(amps, 0.0, 0.025, 8).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_IPRECHG, 0x0F, code)?)
    }

    /// Set the charge termination current (0-0.2 A in 25 mA steps) and enable bit.
    pub fn set_termination(&mut self, amps: f32, enabled: bool) -> FullResult<(), I2C> {
        let code = step_code(amps, 0.0, 0.025, 8).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_ITERM, 0x1F, code | bit(enabled, 0x10))?)
    }

    /// Set the input current limit: 0.1, 0.5, 0.9, 1.0, 1.5 or 2.0 A.
    pub fn set_input_current_limit(&mut self, amps: f32) -> FullResult<(), I2C> {
        let i = index_of(amps, &[0.1, 0.5, 0.9, 1.0, 1.5, 2.0]).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_IINLIM, 0x07, i)?)
    }

    /// Set the input voltage limit (VINDPM): 3.88-5.08 V in 80 mV steps.
    pub fn set_input_voltage_limit(&mut self, volts: f32) -> FullResult<(), I2C> {
        let code = step_code(volts, 3.88, 0.08, 15).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_VINDPM, 0x0F, code)?)
    }

    /// Set the minimum system voltage: 3.2-3.9 V in 100 mV steps.
    pub fn set_min_system_voltage(&mut self, volts: f32) -> FullResult<(), I2C> {
        let code = step_code(volts, 3.2, 0.1, 7).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_MIN_VSYS, 0x07, code)?)
    }

    /// Configure the charger safety timers (`CHG_TIMER`): pre-charge 40 / 50 /
    /// 60 / 70 min, charge-done 5 / 8 / 12 / 20 h, half-speed timers during
    /// input DPM / thermal regulation. Defaults: 60, 12, true, true, true.
    pub fn set_charge_timers(&mut self, precharge_min: u8, done_hours: u8, precharge_enabled: bool,
                             done_enabled: bool, slow_in_dpm: bool) -> FullResult<(), I2C> {
        let pre = index_of(precharge_min as f32, &[40.0, 50.0, 60.0, 70.0]).ok_or(Axp2101Error::InvalidArgument)?;
        let done = index_of(done_hours as f32, &[5.0, 8.0, 12.0, 20.0]).ok_or(Axp2101Error::InvalidArgument)?;
        let v = pre | bit(precharge_enabled, 0x04) | (done << 4) | bit(done_enabled, 0x40) | bit(slow_in_dpm, 0x80);
        Ok(self.inner.rmw(REG_CHG_TIMER, 0xF7, v)?)
    }

    /// Set the charger thermal-regulation threshold: 60, 80, 100 or 120 °C.
    pub fn set_thermal_regulation(&mut self, celsius: u8) -> FullResult<(), I2C> {
        let i = index_of(celsius as f32, &[60.0, 80.0, 100.0, 120.0]).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_THERMAL_REG, 0x03, i)?)
    }

    /// Configure die over-temperature level 1 (115, 125 or 135 °C; default 125)
    /// and die-temperature detection.
    pub fn set_die_protection(&mut self, level1_celsius: u8, detect: bool) -> FullResult<(), I2C> {
        let i = index_of(level1_celsius as f32, &[115.0, 125.0, 135.0]).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_DIE_TEMP_CTRL, 0x07, (i << 1) | bit(detect, 1))?)
    }

    /// Enable or disable battery detection (`BAT_DETECT`).
    pub fn set_battery_detection(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_BAT_DETECT, 0x01, bit(enabled, 1))
    }

    /// Configure charging of the backup (coin) cell: termination voltage
    /// 2.6-3.3 V in 100 mV steps (default 2.9).
    pub fn set_backup_charging(&mut self, enabled: bool, volts: f32) -> FullResult<(), I2C> {
        let code = step_code(volts, 2.6, 0.1, 7).ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.rmw(REG_BTN_BAT_VTERM, 0x07, code)?;
        Ok(self.inner.rmw(REG_MODULE_ENABLE, 0x04, bit(enabled, 0x04))?)
    }

    /// Configure the CHGLED pin. `mode` is `"off"`, `"type_a"`, `"type_b"` or
    /// `"manual"`; for `"manual"`, `output` is `"hiz"`, `"blink_1hz"`,
    /// `"blink_4hz"` or `"low"`.
    pub fn set_chgled(&mut self, mode: &str, output: &str) -> FullResult<(), I2C> {
        let mut v = match mode {
            "off" => 0x00, "type_a" => 0x01, "type_b" => 0x03, "manual" => 0x05,
            _ => return Err(Axp2101Error::InvalidArgument),
        };
        let o = match output {
            "hiz" => 0, "blink_1hz" => 1, "blink_4hz" => 2, "low" => 3,
            _ => return Err(Axp2101Error::InvalidArgument),
        };
        if mode == "manual" {
            v |= o << 4;
        }
        Ok(self.inner.rmw(REG_CHGLED_CTRL, 0x37, v)?)
    }

    /// Configure the TS pin (`TS_CTRL`): battery thermistor (`true`) or
    /// external input (`false`), injected current 20 / 40 / 50 / 60 µA, and
    /// source mode `"off"`, `"when_adc"`, `"when_ts"` or `"always"`.
    pub fn configure_ts(&mut self, battery_sensor: bool, current_ua: u8, source: &str) -> FullResult<(), I2C> {
        let s = match source {
            "off" => 0, "when_adc" => 1, "when_ts" => 2, "always" => 3,
            _ => return Err(Axp2101Error::InvalidArgument),
        };
        let cur = index_of(current_ua as f32, &[20.0, 40.0, 50.0, 60.0]).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(REG_TS_CTRL, 0x1F, cur | (s << 2) | bit(!battery_sensor, 0x10))?)
    }

    /// Set the battery-temperature limits as TS pin voltages in V: charge
    /// cold (32 mV steps), charge hot (2 mV), work cold (32 mV), work hot (2 mV).
    pub fn set_ts_limits(&mut self, charge_cold: f32, charge_hot: f32, work_cold: f32, work_hot: f32) -> FullResult<(), I2C> {
        let a = step_code(charge_cold, 0.0, 0.032, 255).ok_or(Axp2101Error::InvalidArgument)?;
        let b = step_code(charge_hot, 0.0, 0.002, 255).ok_or(Axp2101Error::InvalidArgument)?;
        let c = step_code(work_cold, 0.0, 0.032, 255).ok_or(Axp2101Error::InvalidArgument)?;
        let d = step_code(work_hot, 0.0, 0.002, 255).ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.wr(REG_VLTF_CHG, a)?;
        self.inner.wr(REG_VHTF_CHG, b)?;
        self.inner.wr(REG_VLTF_WORK, c)?;
        Ok(self.inner.wr(REG_VHTF_WORK, d)?)
    }

    /// Set the TS threshold hysteresis in V: low to normal (16 mV steps,
    /// default 0.032) and high to normal (4 mV steps, default 0.004).
    pub fn set_ts_hysteresis(&mut self, low_to_normal: f32, high_to_normal: f32) -> FullResult<(), I2C> {
        let l2h = step_code(low_to_normal, 0.0, 0.016, 255).ok_or(Axp2101Error::InvalidArgument)?;
        let h2l = step_code(high_to_normal, 0.0, 0.004, 255).ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.wr(REG_TS_HYS_L2H, l2h)?;
        Ok(self.inner.wr(REG_TS_HYS_H2L, h2l)?)
    }

    /// Configure the JEITA charging profile. `cool_voltage` (T2, TS voltage
    /// in V, 16 mV steps) and `warm_voltage` (T3, 8 mV steps) are left
    /// unchanged when `None`; the drops are charge-voltage gears 0-2.
    /// Defaults: cool half current true, warm half current false, drops 0 and 1.
    #[allow(clippy::too_many_arguments)]
    pub fn configure_jeita(&mut self, enabled: bool, cool_voltage: Option<f32>, warm_voltage: Option<f32>,
                           cool_half_current: bool, warm_half_current: bool, cool_voltage_drop: u8,
                           warm_voltage_drop: u8) -> FullResult<(), I2C> {
        if cool_voltage_drop > 2 || warm_voltage_drop > 2 {
            return Err(Axp2101Error::InvalidArgument);
        }
        let cool = match cool_voltage {
            Some(v) => Some(step_code(v, 0.0, 0.016, 255).ok_or(Axp2101Error::InvalidArgument)?),
            None => None,
        };
        let warm = match warm_voltage {
            Some(v) => Some(step_code(v, 0.0, 0.008, 255).ok_or(Axp2101Error::InvalidArgument)?),
            None => None,
        };
        let v = cool_voltage_drop | (warm_voltage_drop << 2) | bit(cool_half_current, 0x10) | bit(warm_half_current, 0x40);
        self.inner.rmw(REG_JEITA_CV, 0x5F, v)?;
        if let Some(c) = cool {
            self.inner.wr(REG_JEITA_COOL, c)?;
        }
        if let Some(w) = warm {
            self.inner.wr(REG_JEITA_WARM, w)?;
        }
        Ok(self.inner.rmw(REG_JEITA_EN, 0x01, bit(enabled, 1))?)
    }

    /// Read the state of a rail: `(enabled, volts)`. `name` is `"dcdc1"`..`"dcdc4"`,
    /// `"aldo1"`..`"aldo4"`, `"bldo1"`, `"bldo2"`, `"cpusldo"`, `"dldo1"` or `"dldo2"`.
    pub fn get_rail(&mut self, name: &str) -> FullResult<(bool, f32), I2C> {
        let (en_reg, en_bit, v_reg, kind, mask) = rail_info(name).ok_or(Axp2101Error::InvalidArgument)?;
        let enabled = self.inner.rd(en_reg)? & (1 << en_bit) != 0;
        let code = self.inner.rd(v_reg)? & mask;
        Ok((enabled, rail_volts(kind, code)))
    }

    /// Set a rail voltage, rounded to the nearest step inside the rail's range;
    /// a value outside it is rejected.
    pub fn set_rail_voltage(&mut self, name: &str, volts: f32) -> FullResult<(), I2C> {
        let (_, _, v_reg, kind, mask) = rail_info(name).ok_or(Axp2101Error::InvalidArgument)?;
        let code = rail_code(kind, volts).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(v_reg, mask, code)?)
    }

    /// Enable or disable a rail. Disabling the rail that powers the host cuts
    /// the host's power.
    pub fn set_rail_enabled(&mut self, name: &str, enabled: bool) -> FullResult<(), I2C> {
        let (en_reg, en_bit, _, _, _) = rail_info(name).ok_or(Axp2101Error::InvalidArgument)?;
        Ok(self.inner.rmw(en_reg, 1 << en_bit, bit(enabled, 1 << en_bit))?)
    }

    /// Configure one DCDC converter (`"dcdc1"`..`"dcdc4"`): forced PWM instead
    /// of automatic PWM/PFM, and dynamic voltage scaling for dcdc2 / dcdc3
    /// (`None` leaves it unchanged).
    pub fn configure_dcdc(&mut self, name: &str, always_pwm: bool, dvm: Option<bool>) -> FullResult<(), I2C> {
        let (en_reg, en_bit, v_reg, _, _) = rail_info(name).ok_or(Axp2101Error::InvalidArgument)?;
        if en_reg != 0x80 {
            return Err(Axp2101Error::InvalidArgument);
        }
        if dvm.is_some() && name != "dcdc2" && name != "dcdc3" {
            return Err(Axp2101Error::InvalidArgument);
        }
        let b = 1u8 << (en_bit + 2);
        self.inner.rmw(REG_DCDC_PWM, b, bit(always_pwm, b))?;
        if let Some(d) = dvm {
            self.inner.rmw(v_reg, 0x80, bit(d, 0x80))?;
        }
        Ok(())
    }

    /// Configure settings shared by all DCDC converters: force CCM, slow DVM
    /// ramp (31.25 µs / step), frequency spread and its 100 kHz range, and the
    /// under-voltage debounce (60 / 120 / 180 / 240 µs).
    pub fn configure_dcdc_global(&mut self, force_ccm: bool, slow_ramp: bool, frequency_spread: bool,
                                 spread_100khz: bool, uvp_debounce_us: u16) -> FullResult<(), I2C> {
        let deb = index_of(uvp_debounce_us as f32, &[60.0, 120.0, 180.0, 240.0]).ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.rmw(REG_DCDC_EN, 0x60, bit(force_ccm, 0x40) | bit(slow_ramp, 0x20))?;
        Ok(self.inner.rmw(REG_DCDC_PWM, 0xC3, bit(frequency_spread, 0x80) | bit(spread_100khz, 0x40) | deb)?)
    }

    /// Enable output discharge when a rail is off (`COMMON_CONFIG` bit 5).
    pub fn set_off_discharge(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_COMMON_CONFIG, 0x20, bit(enabled, 0x20))
    }

    /// Soft power-off: every output except RTCLDO turns off.
    pub fn power_off(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(REG_COMMON_CONFIG, 0x01)
    }

    /// Restart the system (power-off / power-on).
    pub fn restart(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(REG_COMMON_CONFIG, 0x02)
    }

    /// Enter sleep; the host disables / re-voltages rails first.
    pub fn sleep(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(REG_SLEEP_CTRL, 0x01)
    }

    /// Wake up from sleep.
    pub fn wakeup(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(REG_SLEEP_CTRL, 0x02)
    }

    /// Configure sleep / wake-up (`SLEEP_CTRL`): wake on the IRQ pin, PWROK
    /// low during wake-up, restore the voltages from before sleep.
    /// Defaults: false, true, false.
    pub fn configure_sleep(&mut self, wake_on_irq: bool, pwrok_low: bool, restore_voltages: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_SLEEP_CTRL, 0x1C, bit(wake_on_irq, 0x10) | bit(pwrok_low, 0x08) | bit(restore_voltages, 0x04))
    }

    /// Read the `PWRON_STATUS` flags.
    pub fn power_on_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.rd(REG_PWRON_STATUS)
    }

    /// Read the `PWROFF_STATUS` flags.
    pub fn power_off_source(&mut self) -> Result<u8, I2C::Error> {
        self.inner.rd(REG_PWROFF_STATUS)
    }

    /// Configure the power key: hold time to power on (128 / 512 / 1000 /
    /// 2000 ms), to power off (4 / 6 / 8 / 10 s) and for the long-press IRQ
    /// (1 / 1.5 / 2 / 2.5 s), plus the long-press actions.
    /// Defaults: 512, 6, 1.5, true, false, false.
    pub fn configure_power_key(&mut self, on_ms: u16, off_s: f32, irq_s: f32, long_press_off: bool,
                               restart_instead: bool, force_off_16s: bool) -> FullResult<(), I2C> {
        let on = index_of(on_ms as f32, &[128.0, 512.0, 1000.0, 2000.0]).ok_or(Axp2101Error::InvalidArgument)?;
        let off = index_of(off_s, &[4.0, 6.0, 8.0, 10.0]).ok_or(Axp2101Error::InvalidArgument)?;
        let irq = index_of(irq_s, &[1.0, 1.5, 2.0, 2.5]).ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.rmw(REG_PWRKEY_LEVEL, 0x3F, on | (off << 2) | (irq << 4))?;
        self.inner.rmw(REG_PWROFF_EN, 0x03, bit(long_press_off, 0x02) | bit(restart_instead, 0x01))?;
        Ok(self.inner.rmw(REG_COMMON_CONFIG, 0x04, bit(force_off_16s, 0x04))?)
    }

    /// Configure the power-off sources: die over-temperature level 2, system
    /// power-off voltage (2.6-3.3 V in 100 mV steps), DCDC over-voltage and
    /// the mask of DCDC1-4 whose under-voltage turns the PMIC off (bits 0-3).
    /// Defaults: true, 2.6, true, 0x0F.
    pub fn configure_power_off(&mut self, die_over_temp: bool, off_voltage: f32, dcdc_ovp: bool, dcdc_uvp: u8) -> FullResult<(), I2C> {
        let code = step_code(off_voltage, 2.6, 0.1, 7).ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.rmw(REG_PWROFF_EN, 0x04, bit(die_over_temp, 0x04))?;
        self.inner.rmw(REG_VOFF, 0x07, code)?;
        Ok(self.inner.rmw(REG_DCDC_PROT, 0x2F, bit(dcdc_ovp, 0x20) | (dcdc_uvp & 0x0F))?)
    }

    /// Configure the PWROK output: check after all outputs are valid, delay
    /// 8 / 16 / 32 / 64 ms, 4 ms power-off delay, reverse power-off sequence,
    /// restart when PWROK is pulled low. Defaults: true, 8, true, false, false.
    pub fn configure_pwrok(&mut self, check: bool, delay_ms: u8, off_delay: bool, reverse_off_sequence: bool,
                           restart_on_low: bool) -> FullResult<(), I2C> {
        let delay = index_of(delay_ms as f32, &[8.0, 16.0, 32.0, 64.0]).ok_or(Axp2101Error::InvalidArgument)?;
        let v = delay | bit(reverse_off_sequence, 0x04) | bit(off_delay, 0x08) | bit(check, 0x10);
        self.inner.rmw(REG_PWROK_CTRL, 0x1F, v)?;
        Ok(self.inner.rmw(REG_COMMON_CONFIG, 0x08, bit(restart_on_low, 0x08))?)
    }

    /// Turn the BATFET on when powered off on battery only (`BATFET_CTRL` bit 3).
    pub fn set_batfet_off_enable(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_BATFET_CTRL, 0x08, bit(enabled, 0x08))
    }

    /// Configure fast power-on / wake-up and, optionally, the per-rail start
    /// sequence: each entry is `(rail, code)` with code 0-2 or `None` to
    /// disable the rail. An empty slice leaves the sequence untouched.
    pub fn configure_fast_poweron(&mut self, poweron: bool, wakeup: bool, sequence: &[(&str, Option<u8>)]) -> FullResult<(), I2C> {
        for (name, code) in sequence {
            fast_rail(name).ok_or(Axp2101Error::InvalidArgument)?;
            if matches!(code, Some(c) if *c > 2) {
                return Err(Axp2101Error::InvalidArgument);
            }
        }
        for (name, code) in sequence {
            let (off, shift) = fast_rail(name).ok_or(Axp2101Error::InvalidArgument)?;
            let c = code.unwrap_or(3);
            self.inner.rmw(REG_FAST_PWRON0 + off, 3 << shift, c << shift)?;
        }
        Ok(self.inner.rmw(REG_FAST_PWRON0 + 3, 0xC0, bit(poweron, 0x80) | bit(wakeup, 0x40))?)
    }

    /// Configure the watchdog: timeout 1, 2, 4, 8, 16, 32, 64 or 128 s and
    /// action `"irq"`, `"reset"`, `"reset_pwrok"` or `"power_cycle"`.
    pub fn configure_watchdog(&mut self, enabled: bool, timeout_s: u8, action: &str) -> FullResult<(), I2C> {
        let a = match action {
            "irq" => 0, "reset" => 1, "reset_pwrok" => 2, "power_cycle" => 3,
            _ => return Err(Axp2101Error::InvalidArgument),
        };
        let code = index_of(timeout_s as f32, &[1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0])
            .ok_or(Axp2101Error::InvalidArgument)?;
        self.inner.rmw(REG_WATCHDOG_CTRL, 0x37, code | (a << 4))?;
        Ok(self.inner.rmw(REG_MODULE_ENABLE, 0x01, bit(enabled, 1))?)
    }

    /// Clear (feed) the watchdog (`WATCHDOG_CTRL` bit 3).
    pub fn feed_watchdog(&mut self) -> Result<(), I2C::Error> {
        self.inner.command(REG_WATCHDOG_CTRL, 0x08)
    }

    /// Set the low-battery warning levels (`LOWBAT_WARN`): level 2 5-20 %
    /// (default 10), level 1 0-15 % (default 1).
    pub fn configure_battery_warning(&mut self, level2: u8, level1: u8) -> FullResult<(), I2C> {
        if !(5..=20).contains(&level2) || level1 > 15 {
            return Err(Axp2101Error::InvalidArgument);
        }
        Ok(self.inner.wr(REG_LOWBAT_WARN, ((level2 - 5) << 4) | level1)?)
    }

    /// Enable or disable the fuel gauge (`MODULE_ENABLE` bit 3).
    pub fn set_gauge_enabled(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_MODULE_ENABLE, 0x08, bit(enabled, 0x08))
    }

    /// Reset the fuel gauge; `full` selects the deeper reset that also clears
    /// gauge data beyond the registers.
    pub fn reset_gauge(&mut self, full: bool) -> Result<(), I2C::Error> {
        self.inner.command(REG_GAUGE_RESET, if full { 0x04 } else { 0x08 })
    }

    /// Drive the open-drain GPIO1 output: `true` drives low, `false` is Hi-Z.
    pub fn set_gpio1(&mut self, low: bool) -> Result<(), I2C::Error> {
        self.inner.rmw(REG_GPIO1_CTRL, 0x0C, bit(low, 0x04))
    }

    /// Read one of the four general-purpose bytes (`index` 0-3).
    pub fn read_data_buffer(&mut self, index: u8) -> FullResult<u8, I2C> {
        if index > 3 {
            return Err(Axp2101Error::InvalidArgument);
        }
        Ok(self.inner.rd(REG_DATA_BUFFER0 + index)?)
    }

    /// Write one of the four general-purpose bytes (`index` 0-3); it survives
    /// soft restarts, not a power-on reset.
    pub fn write_data_buffer(&mut self, index: u8, value: u8) -> FullResult<(), I2C> {
        if index > 3 {
            return Err(Axp2101Error::InvalidArgument);
        }
        Ok(self.inner.wr(REG_DATA_BUFFER0 + index, value)?)
    }

    /// Set the bits of `source` (an OR of `SOURCE_*` constants) in `IRQ_EN0..2`.
    pub fn enable_interrupt(&mut self, source: u32) -> Result<(), I2C::Error> {
        for k in 0..3u8 {
            let bits = (source >> (8 * k)) as u8;
            if bits != 0 {
                self.inner.rmw(REG_IRQ_EN0 + k, bits, bits)?;
            }
        }
        Ok(())
    }

    /// Clear the bits of `source` in `IRQ_EN0..2`.
    pub fn disable_interrupt(&mut self, source: u32) -> Result<(), I2C::Error> {
        for k in 0..3u8 {
            let bits = (source >> (8 * k)) as u8;
            if bits != 0 {
                self.inner.rmw(REG_IRQ_EN0 + k, bits, 0)?;
            }
        }
        Ok(())
    }

    /// Read `IRQ_STATUS0..2` and write back exactly the bits seen set (so an
    /// event arriving between the read and the write is not lost). Returns the
    /// 24-bit status: `IRQ_STATUS0` in bits 7:0, `IRQ_STATUS1` in 15:8,
    /// `IRQ_STATUS2` in 23:16.
    ///
    /// The caller is responsible for wiring this into an ISR on the IRQ pin or
    /// a polling loop.
    pub fn poll_interrupt(&mut self) -> Result<u32, I2C::Error> {
        let mut status = 0u32;
        for k in 0..3u8 {
            let v = self.inner.rd(REG_IRQ_STATUS0 + k)?;
            if v != 0 {
                self.inner.wr(REG_IRQ_STATUS0 + k, v)?;
            }
            status |= (v as u32) << (8 * k);
        }
        Ok(status)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x34;

    fn rd(reg: u8, val: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![val])
    }

    fn wr(reg: u8, val: u8) -> T {
        T::write(ADDR, vec![reg, val])
    }

    // One read-modify-write: read `cur`, write `new`.
    fn rmw(reg: u8, cur: u8, new: u8) -> Vec<T> {
        vec![rd(reg, cur), wr(reg, new)]
    }

    fn init() -> Vec<T> {
        let mut v = vec![rd(REG_STATUS1, 0)];
        v.extend(rmw(REG_ADC_ENABLE, 0x03, 0x1F));
        v.extend(rmw(REG_MODULE_ENABLE, 0x0A, 0x0A));
        v
    }

    fn new_full(extra: Vec<T>) -> Axp2101Full<I2cMock> {
        let mut tx = init();
        tx.extend(extra);
        Axp2101Full::new(I2cMock::new(&tx), ADDR).expect("new")
    }

    fn done(mut f: Axp2101Full<I2cMock>) {
        f.inner.i2c.done();
    }

    #[test]
    fn minimal_init_and_reads() {
        let mut tx = init();
        tx.extend(vec![rd(0x34, 0x0F), rd(0x35, 0xA0), rd(REG_BAT_PERCENT, 87), rd(REG_STATUS2, 0x22), rd(REG_STATUS1, 0x20)]);
        let mut m = Axp2101Minimal::new(I2cMock::new(&tx), ADDR).unwrap();
        assert!((m.battery_voltage().unwrap() - 4.0).abs() < 1e-3);
        assert_eq!(m.battery_percent().unwrap(), 87);
        assert_eq!(m.charge_state().unwrap(), CHARGE_CC);
        assert!(m.vbus_present().unwrap());
        m.i2c.done();
    }

    #[test]
    fn adc_masks_debug_bits() {
        let mut m = new_full(vec![rd(0x34, 0xCF), rd(0x35, 0xA0)]);
        assert!((m.battery_voltage().unwrap() - 4.0).abs() < 1e-3);
        done(m);
    }

    #[test]
    fn full_measurements() {
        let mut m = new_full(vec![
            rd(0x38, 0x13), rd(0x39, 0x88), rd(0x3A, 0x10), rd(0x3B, 0x68),
            rd(0x36, 0x03), rd(0x37, 0xE8), rd(0x3C, 0x1C), rd(0x3D, 0x6A),
        ]);
        assert!((m.vbus_voltage().unwrap() - 5.0).abs() < 1e-3);
        assert!((m.system_voltage().unwrap() - 4.2).abs() < 1e-3);
        assert!((m.ts_voltage().unwrap() - 0.5).abs() < 1e-3);
        assert!((m.die_temperature().unwrap() - 22.0).abs() < 1e-3);
        done(m);
    }

    #[test]
    fn status_flags() {
        let mut m = new_full(vec![rd(REG_STATUS1, 0x3F), rd(REG_STATUS2, 0x4B)]);
        let s = m.status().unwrap();
        assert!(s.vbus_good && s.batfet_open && s.battery_present && s.battery_active
            && s.thermal_regulation && s.current_limit);
        assert_eq!(s.battery_direction, "discharge");
        assert!(!s.system_on && s.vindpm);
        assert_eq!(s.charge_state, 3);
        done(m);
    }

    #[test]
    fn charger_setters() {
        let mut tx = Vec::new();
        tx.extend(rmw(REG_CV, 0x00, 3));                       // set_charge_voltage(4.2)
        tx.push(rd(REG_CV, 3));                                // set_charge_current(0.5): reads CV
        tx.extend(rmw(REG_ICC, 0x00, 11));
        tx.extend(rmw(REG_IPRECHG, 0xF0, 0xF5));               // precharge 0.125 A
        tx.extend(rmw(REG_ITERM, 0xE0, 0xF2));                 // termination 0.05 A enabled
        tx.extend(rmw(REG_IINLIM, 0x00, 5));                   // 2.0 A
        tx.extend(rmw(REG_VINDPM, 0x00, 6));                   // 4.36 V
        tx.extend(rmw(REG_MIN_VSYS, 0x00, 5));                 // 3.7 V
        tx.extend(rmw(REG_CHG_TIMER, 0x08, 0xEE));             // default timers, bit 3 preserved
        let mut m = new_full(tx);
        m.set_charge_voltage(4.2).unwrap();
        m.set_charge_current(0.5).unwrap();
        m.set_precharge_current(0.125).unwrap();
        m.set_termination(0.05, true).unwrap();
        m.set_input_current_limit(2.0).unwrap();
        m.set_input_voltage_limit(4.36).unwrap();
        m.set_min_system_voltage(3.7).unwrap();
        m.set_charge_timers(60, 12, true, true, true).unwrap();
        done(m);
    }

    #[test]
    fn invalid_arguments_are_rejected() {
        // Only set_charge_current(1.5) at 4.2 V touches the bus (reads CV first).
        let mut m = new_full(vec![rd(REG_CV, 3)]);
        assert!(matches!(m.set_charge_voltage(4.25), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_charge_current(-0.1), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_charge_current(1.5), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_precharge_current(0.3), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_input_current_limit(1.2), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_rail_voltage("dcdc3", 1.84), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_rail_voltage("dldo1", 3.5), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_rail_voltage("foo", 1.0), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.set_rail_voltage("aldo1", 0.3), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.configure_dcdc("dcdc1", false, Some(true)), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.configure_power_key(100, 6.0, 1.5, true, false, false), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.configure_watchdog(true, 3, "irq"), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.configure_battery_warning(25, 1), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.configure_jeita(true, None, None, true, false, 3, 0), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.read_data_buffer(4), Err(Axp2101Error::InvalidArgument)));
        assert!(matches!(m.configure_fast_poweron(true, true, &[("x", Some(1))]), Err(Axp2101Error::InvalidArgument)));
        // The one accepted CV read above is consumed by the 1.5 A check.
        done(m);
    }

    #[test]
    fn charge_current_limit_by_voltage() {
        // 1.5 A is allowed at 4.0 V (CV = 1) but rejected at 4.2 V (CV = 3).
        let mut tx = vec![rd(REG_CV, 1)];
        tx.extend(rmw(REG_ICC, 0x00, 21));
        let mut m = new_full(tx);
        m.set_charge_current(1.5).unwrap();
        done(m);
    }

    #[test]
    fn rails() {
        let mut tx = vec![rd(0x80, 0x0F), rd(0x83, 0x80 | 18)];   // get_rail("dcdc2")
        tx.extend(rmw(0x83, 0x80, 0x80 | 85));                    // 1.5 V = 71 + 14
        tx.extend(rmw(0x82, 0x00, 18));                           // dcdc1 3.3 V
        tx.extend(rmw(0x85, 0x00, 102));                          // dcdc4 1.84 V
        tx.extend(rmw(0x93, 0x00, 28));                           // aldo2 3.3 V
        tx.extend(rmw(0x98, 0x00, 10));                           // cpusldo 1.0 V
        tx.extend(rmw(0x90, 0x20, 0xA0));                         // enable dldo1, bldo2 kept
        tx.extend(rmw(0x81, 0x00, 0x10));                         // dcdc3 always PWM
        tx.extend(rmw(0x84, 0x00, 0x80));                         // dcdc3 DVM
        let mut m = new_full(tx);
        let (en, v) = m.get_rail("dcdc2").unwrap();
        assert!(en && (v - 0.68).abs() < 1e-3);
        m.set_rail_voltage("dcdc2", 1.5).unwrap();
        m.set_rail_voltage("dcdc1", 3.3).unwrap();
        m.set_rail_voltage("dcdc4", 1.84).unwrap();
        m.set_rail_voltage("aldo2", 3.3).unwrap();
        m.set_rail_voltage("cpusldo", 1.0).unwrap();
        m.set_rail_enabled("dldo1", true).unwrap();
        m.configure_dcdc("dcdc3", true, Some(true)).unwrap();
        done(m);
    }

    #[test]
    fn self_clearing_commands() {
        let mut tx = Vec::new();
        tx.extend(rmw(REG_COMMON_CONFIG, 0x30, 0x31));            // power_off
        tx.extend(rmw(REG_COMMON_CONFIG, 0x30, 0x32));            // restart
        tx.extend(rmw(REG_SLEEP_CTRL, 0x08, 0x09));               // sleep
        tx.extend(rmw(REG_SLEEP_CTRL, 0x08, 0x0A));               // wakeup
        tx.extend(rmw(REG_WATCHDOG_CTRL, 0x06, 0x0E));            // feed
        tx.extend(rmw(REG_GAUGE_RESET, 0x00, 0x08));              // reset_gauge
        tx.extend(rmw(REG_GAUGE_RESET, 0x00, 0x04));              // reset_gauge(full)
        let mut m = new_full(tx);
        m.power_off().unwrap();
        m.restart().unwrap();
        m.sleep().unwrap();
        m.wakeup().unwrap();
        m.feed_watchdog().unwrap();
        m.reset_gauge(false).unwrap();
        m.reset_gauge(true).unwrap();
        done(m);
    }

    #[test]
    fn system_configuration() {
        let mut tx = Vec::new();
        tx.extend(rmw(REG_PWRKEY_LEVEL, 0xC0, 0xFC));             // power key
        tx.extend(rmw(REG_PWROFF_EN, 0x04, 0x07));
        tx.extend(rmw(REG_COMMON_CONFIG, 0x30, 0x34));
        tx.extend(rmw(REG_WATCHDOG_CTRL, 0x00, 0x37));            // watchdog 128 s power_cycle
        tx.extend(rmw(REG_MODULE_ENABLE, 0x0A, 0x0B));
        tx.extend(rmw(REG_FAST_PWRON0, 0xFF, 0xFD));              // dcdc1 -> 1
        tx.extend(rmw(REG_FAST_PWRON0 + 3, 0x0F, 0x8F));
        let mut m = new_full(tx);
        m.configure_power_key(128, 10.0, 2.5, true, true, true).unwrap();
        m.configure_watchdog(true, 128, "power_cycle").unwrap();
        m.configure_fast_poweron(true, false, &[("dcdc1", Some(1))]).unwrap();
        done(m);
    }

    #[test]
    fn interrupts() {
        let mut tx = Vec::new();
        tx.extend(rmw(0x41, 0xFC, 0x7C));                          // disable VBUS insert
        tx.extend(rmw(0x42, 0x5F, 0x4F));                          // disable charge done (bit 4 of byte 2)
        tx.extend(rmw(0x41, 0x7C, 0xFC));                          // enable VBUS insert again
        tx.extend(vec![rd(0x48, 0x10), wr(0x48, 0x10), rd(0x49, 0x88), wr(0x49, 0x88), rd(0x4A, 0x01), wr(0x4A, 0x01)]);
        tx.extend(vec![rd(0x48, 0), rd(0x49, 0), rd(0x4A, 0)]);
        let mut m = new_full(tx);
        m.disable_interrupt(SOURCE_VBUS_INSERT | SOURCE_CHARGE_DONE).unwrap();
        m.enable_interrupt(SOURCE_VBUS_INSERT).unwrap();
        assert_eq!(m.poll_interrupt().unwrap(), 0x018810);
        assert_eq!(m.poll_interrupt().unwrap(), 0);
        done(m);
    }

    #[test]
    fn retries_once_on_nack() {
        use embedded_hal::i2c::ErrorKind;
        let mut tx = init();
        tx.push(T::write_read(ADDR, vec![REG_STATUS1], vec![0]).with_error(ErrorKind::Other));
        tx.push(rd(REG_STATUS1, 0x20));
        let mut m = Axp2101Minimal::new(I2cMock::new(&tx), ADDR).unwrap();
        assert!(m.vbus_present().unwrap());
        m.i2c.done();
    }
}
