//! MAX17048 / MAX17049 — lithium fuel gauge with ModelGauge (Maxim Integrated / Analog Devices).
//!
//! Communicates over I²C at address `0x36` (up to 400 kHz). All registers are 16-bit big-endian
//! words. The state of charge is estimated from the cell voltage alone (no sense resistor). One
//! driver serves the 1-cell MAX17048 and the 2-cell MAX17049 (`cells` = 1 or 2). The constructors
//! make no bus access. The Full driver's interrupt support is [`Max17048Full::poll_interrupt`]
//! only: the caller wires it into an ISR (falling edge on ALRT) or a polling loop.
//!
//! ## Constants
//!
//! The interrupt source constants (`SOURCE_*`) live in this module
//! (`periph::chips::power::max17048::…`) and are not re-exported at the category level.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_VCELL: u8 = 0x02;
const REG_SOC: u8 = 0x04;
const REG_MODE: u8 = 0x06;
const REG_VERSION: u8 = 0x08;
const REG_HIBRT: u8 = 0x0A;
const REG_CONFIG: u8 = 0x0C;
const REG_VALRT: u8 = 0x14;
const REG_CRATE: u8 = 0x16;
const REG_VRESET: u8 = 0x18;
const REG_STATUS: u8 = 0x1A;
const REG_CMD: u8 = 0xFE;

/// Interrupt source: `STATUS.RI`, the chip has reset.
pub const SOURCE_RESET: u16 = 0x0100;
/// Interrupt source: `STATUS.VH`, `VCELL` above `VALRT.MAX`.
pub const SOURCE_OVERVOLTAGE: u16 = 0x0200;
/// Interrupt source: `STATUS.VL`, `VCELL` below `VALRT.MIN`.
pub const SOURCE_UNDERVOLTAGE: u16 = 0x0400;
/// Interrupt source: `STATUS.VR`, voltage-reset alert (needs `enable_voltage_reset_alert`).
pub const SOURCE_VRESET: u16 = 0x0800;
/// Interrupt source: `STATUS.HD`, SOC crossed the empty-alert threshold.
pub const SOURCE_LOW_SOC: u16 = 0x1000;
/// Interrupt source: `STATUS.SC`, SOC changed by at least 1 % (needs the SOC change alert).
pub const SOURCE_SOC_CHANGE: u16 = 0x2000;

const SOURCES: u16 = 0x3F00;

/// Errors returned by the MAX17048 Full driver.
#[derive(Debug, PartialEq)]
pub enum Max17048Error<E> {
    /// I²C bus error.
    I2c(E),
    /// An argument was invalid (e.g. `min_v > max_v`).
    InvalidArgument,
}

impl<E> From<E> for Max17048Error<E> {
    fn from(e: E) -> Self {
        Max17048Error::I2c(e)
    }
}

fn clamp_byte(v: f32) -> u8 {
    if v <= 0.0 {
        0
    } else if v >= 255.0 {
        255
    } else {
        (v + 0.5) as u8
    }
}

fn read_word<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    register::read_register(i2c, addr, reg as u32, 1, &mut buf)?;
    Ok(((buf[0] as u16) << 8) | buf[1] as u16)
}

fn write_word<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    register::write_register(i2c, addr, reg as u32, 1, &[(value >> 8) as u8, value as u8])
}

fn modify<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, clear: u16, set: u16) -> Result<(), I2C::Error> {
    let v = read_word(i2c, addr, reg)?;
    write_word(i2c, addr, reg, (v & !clear) | set)
}

/// MAX17048 minimal driver — cell voltage and state of charge.
///
/// Relies on the chip's power-on state; nothing is written at construction.
pub struct Max17048Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    cells: u8,
}

impl<I2C: I2c> Max17048Minimal<I2C> {
    /// Create a new `Max17048Minimal`. Makes no bus access.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (`0x36`).
    /// * `cells` — 1 for the MAX17048, 2 for the MAX17049 (scales the voltage).
    pub fn new(i2c: I2C, addr: u8, cells: u8) -> Result<Self, I2C::Error> {
        Ok(Self { i2c, addr, cells: if cells == 2 { 2 } else { 1 } })
    }

    /// Read the cell voltage in volts (`(VCELL >> 4) × 1.25 mV × cells`).
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        let raw = read_word(&mut self.i2c, self.addr, REG_VCELL)?;
        Ok((raw >> 4) as f32 * 1.25e-3 * self.cells as f32)
    }

    /// Read the state of charge in %, clamped to 0.0–100.0 (`SOC / 256`).
    pub fn percent(&mut self) -> Result<f32, I2C::Error> {
        let raw = read_word(&mut self.i2c, self.addr, REG_SOC)?;
        let p = raw as f32 / 256.0;
        Ok(if p > 100.0 { 100.0 } else { p })
    }
}

/// MAX17048 full driver — extends [`Max17048Minimal`] with alerts, RCOMP, hibernate, sleep and reset.
pub struct Max17048Full<I2C> {
    inner: Max17048Minimal<I2C>,
    sleep_enabled: bool,
}

impl<I2C: I2c> Max17048Full<I2C> {
    /// Create a new `Max17048Full`. Makes no bus access. Same arguments as [`Max17048Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, cells: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Max17048Minimal::new(i2c, addr, cells)?, sleep_enabled: false })
    }

    /// Read the cell voltage in volts.
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.voltage()
    }

    /// Read the state of charge in %.
    pub fn percent(&mut self) -> Result<f32, I2C::Error> {
        self.inner.percent()
    }

    fn rd(&mut self, reg: u8) -> Result<u16, I2C::Error> {
        read_word(&mut self.inner.i2c, self.inner.addr, reg)
    }

    fn wr(&mut self, reg: u8, value: u16) -> Result<(), I2C::Error> {
        write_word(&mut self.inner.i2c, self.inner.addr, reg, value)
    }

    fn rmw(&mut self, reg: u8, clear: u16, set: u16) -> Result<(), I2C::Error> {
        modify(&mut self.inner.i2c, self.inner.addr, reg, clear, set)
    }

    /// Read the raw `VCELL` register.
    pub fn raw_voltage(&mut self) -> Result<u16, I2C::Error> {
        self.rd(REG_VCELL)
    }

    /// Read the raw `SOC` register.
    pub fn raw_soc(&mut self) -> Result<u16, I2C::Error> {
        self.rd(REG_SOC)
    }

    /// Read the charge/discharge rate in %/h (signed `CRATE` × 0.208), positive while charging.
    pub fn charge_rate(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.rd(REG_CRATE)? as i16 as f32 * 0.208)
    }

    /// Read the `VERSION` register (`0x001x`).
    pub fn version(&mut self) -> Result<u16, I2C::Error> {
        self.rd(REG_VERSION)
    }

    /// Read the factory-programmed ID (low byte of `VRESET/ID`).
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.rd(REG_VRESET)? as u8)
    }

    /// Restart the SOC estimate from the present voltage (`MODE.QuickStart`); only use when the
    /// first SOC after power-up is clearly wrong.
    pub fn quick_start(&mut self) -> Result<(), I2C::Error> {
        let v = 0x4000 | if self.sleep_enabled { 0x2000 } else { 0 };
        self.wr(REG_MODE, v)
    }

    /// Power-on reset (`CMD` ← `0x5400`). The chip does not ACK, so a bus error is ignored; waits 1 s.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        let _ = self.wr(REG_CMD, 0x5400);
        delay.delay_ms(1000);
        Ok(())
    }

    /// Write `RCOMP` (`CONFIG` bits 15:8, read-modify-write).
    pub fn set_rcomp(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.rmw(REG_CONFIG, 0xFF00, (value as u16) << 8)
    }

    /// Read `RCOMP`.
    pub fn get_rcomp(&mut self) -> Result<u8, I2C::Error> {
        Ok((self.rd(REG_CONFIG)? >> 8) as u8)
    }

    /// Set `RCOMP` from the datasheet formula with the default coefficients (`RCOMP0 = 0x97`).
    pub fn compensate_temperature(&mut self, celsius: f32) -> Result<(), I2C::Error> {
        let coefficient = if celsius >= 20.0 { -0.5 } else { -5.0 };
        self.set_rcomp(clamp_byte(151.0 + (celsius - 20.0) * coefficient))
    }

    /// Set the empty-alert threshold (`CONFIG.ATHD = 32 − percent`), 1–32 %.
    pub fn set_alert_soc(&mut self, percent: u8) -> Result<(), I2C::Error> {
        let p = percent.clamp(1, 32);
        self.rmw(REG_CONFIG, 0x001F, (32 - p) as u16)
    }

    /// Read the empty-alert threshold in % (1–32).
    pub fn get_alert_soc(&mut self) -> Result<u8, I2C::Error> {
        Ok(32 - (self.rd(REG_CONFIG)? & 0x1F) as u8)
    }

    /// Enable or disable the alert on a SOC change of at least 1 % (`CONFIG.ALSC`).
    pub fn set_soc_change_alert(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.rmw(REG_CONFIG, 0x0040, if enabled { 0x0040 } else { 0 })
    }

    /// Set the voltage alert window in 20 mV steps (`VALRT`); `min_v = 0` and `max_v = 5.1` disable the limits.
    ///
    /// Returns [`Max17048Error::InvalidArgument`] if `min_v > max_v`.
    pub fn set_voltage_alert(&mut self, min_v: f32, max_v: f32) -> Result<(), Max17048Error<I2C::Error>> {
        if min_v > max_v {
            return Err(Max17048Error::InvalidArgument);
        }
        let v = ((clamp_byte(min_v / 0.020) as u16) << 8) | clamp_byte(max_v / 0.020) as u16;
        Ok(self.wr(REG_VALRT, v)?)
    }

    /// Read the voltage alert window as `(min_v, max_v)` in volts.
    pub fn get_voltage_alert(&mut self) -> Result<(f32, f32), I2C::Error> {
        let raw = self.rd(REG_VALRT)?;
        Ok(((raw >> 8) as f32 * 0.020, (raw & 0xFF) as f32 * 0.020))
    }

    /// Set the reset voltage (`VRESET` bits 15:9, 40 mV steps), 0.0–5.08 V.
    pub fn set_reset_voltage(&mut self, volts: f32) -> Result<(), I2C::Error> {
        let steps = if volts <= 0.0 { 0u16 } else { ((volts / 0.040 + 0.5) as u16).min(127) };
        self.rmw(REG_VRESET, 0xFE00, steps << 9)
    }

    /// Read the reset voltage in volts.
    pub fn get_reset_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok((self.rd(REG_VRESET)? >> 9) as f32 * 0.040)
    }

    /// Let a voltage-reset event assert ALRT (`STATUS.ENVR`).
    pub fn enable_voltage_reset_alert(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.rmw(REG_STATUS, 0x4000, if enabled { 0x4000 } else { 0 })
    }

    /// Disable the analog comparator in hibernate mode (`VRESET/ID.DIS`, saves about 0.5 µA).
    pub fn set_comparator_disabled(&mut self, disabled: bool) -> Result<(), I2C::Error> {
        self.rmw(REG_VRESET, 0x0100, if disabled { 0x0100 } else { 0 })
    }

    /// Program the hibernate thresholds (`HIBRT`): hibernate below `hib_thr_pct_per_h` (0.208 %/h steps),
    /// wake when |OCV − VCELL| exceeds `act_thr_v` (1.25 mV steps).
    pub fn set_hibernate_thresholds(&mut self, hib_thr_pct_per_h: f32, act_thr_v: f32) -> Result<(), I2C::Error> {
        let v = ((clamp_byte(hib_thr_pct_per_h / 0.208) as u16) << 8) | clamp_byte(act_thr_v / 0.00125) as u16;
        self.wr(REG_HIBRT, v)
    }

    /// Read the hibernate thresholds as `(hib_thr_pct_per_h, act_thr_v)`.
    pub fn get_hibernate_thresholds(&mut self) -> Result<(f32, f32), I2C::Error> {
        let raw = self.rd(REG_HIBRT)?;
        Ok(((raw >> 8) as f32 * 0.208, (raw & 0xFF) as f32 * 0.00125))
    }

    /// Never hibernate (`HIBRT` ← `0x0000`).
    pub fn disable_hibernate(&mut self) -> Result<(), I2C::Error> {
        self.wr(REG_HIBRT, 0x0000)
    }

    /// Always stay in hibernate (`HIBRT` ← `0xFFFF`).
    pub fn force_hibernate(&mut self) -> Result<(), I2C::Error> {
        self.wr(REG_HIBRT, 0xFFFF)
    }

    /// Read `MODE.HibStat`; an unreadable (write-only) register counts as `false`.
    pub fn is_hibernating(&mut self) -> bool {
        matches!(self.rd(REG_MODE), Ok(v) if v & 0x1000 != 0)
    }

    /// Allow or forbid sleep mode (`MODE.EnSleep`; `MODE` is written whole).
    pub fn enable_sleep(&mut self, enabled: bool) -> Result<(), I2C::Error> {
        self.sleep_enabled = enabled;
        self.wr(REG_MODE, if enabled { 0x2000 } else { 0x0000 })
    }

    /// Enter sleep mode (`CONFIG.SLEEP = 1`); needs `enable_sleep(true)` first.
    pub fn sleep(&mut self) -> Result<(), I2C::Error> {
        self.rmw(REG_CONFIG, 0, 0x0080)
    }

    /// Leave sleep mode (`CONFIG.SLEEP = 0`).
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        self.rmw(REG_CONFIG, 0x0080, 0)
    }

    /// Read the `STATUS` register.
    pub fn read_status(&mut self) -> Result<u16, I2C::Error> {
        self.rd(REG_STATUS)
    }

    /// Clear the given `STATUS` bits and `CONFIG.ALRT` (releases the ALRT pin).
    pub fn clear_status(&mut self, mask: u16) -> Result<(), I2C::Error> {
        self.rmw(REG_STATUS, mask, 0)?;
        self.rmw(REG_CONFIG, 0x0020, 0)
    }

    /// Read the raw `CONFIG` register.
    pub fn read_config(&mut self) -> Result<u16, I2C::Error> {
        self.rd(REG_CONFIG)
    }

    /// Write the raw `CONFIG` register.
    pub fn write_config(&mut self, value: u16) -> Result<(), I2C::Error> {
        self.wr(REG_CONFIG, value)
    }

    /// Enable an alert source that has a control bit (`SOURCE_SOC_CHANGE`, `SOURCE_VRESET`).
    pub fn enable_interrupt(&mut self, source: u16) -> Result<(), I2C::Error> {
        match source {
            SOURCE_SOC_CHANGE => self.set_soc_change_alert(true),
            SOURCE_VRESET => self.enable_voltage_reset_alert(true),
            _ => Ok(()),
        }
    }

    /// Disable an alert source (control bit, or `VALRT.MIN = 0` / `VALRT.MAX = 0xFF`).
    pub fn disable_interrupt(&mut self, source: u16) -> Result<(), I2C::Error> {
        match source {
            SOURCE_SOC_CHANGE => self.set_soc_change_alert(false),
            SOURCE_VRESET => self.enable_voltage_reset_alert(false),
            SOURCE_UNDERVOLTAGE => self.rmw(REG_VALRT, 0xFF00, 0),
            SOURCE_OVERVOLTAGE => self.rmw(REG_VALRT, 0x00FF, 0x00FF),
            _ => Ok(()),
        }
    }

    /// Read `STATUS`, clear the reported flags and `CONFIG.ALRT`; returns the `SOURCE_*` bits (0 = no alert).
    pub fn poll_interrupt(&mut self) -> Result<u16, I2C::Error> {
        let flags = self.rd(REG_STATUS)? & SOURCES;
        if flags != 0 {
            self.clear_status(flags)?;
        }
        Ok(flags)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x36;

    fn rd(reg: u8, v: u16) -> T {
        T::write_read(ADDR, vec![reg], vec![(v >> 8) as u8, v as u8])
    }

    fn wr(reg: u8, v: u16) -> T {
        T::write(ADDR, vec![reg, (v >> 8) as u8, v as u8])
    }

    fn near(a: f32, b: f32, tol: f32) -> bool {
        (a - b).abs() < tol
    }

    #[test]
    fn minimal_api() {
        let t = vec![rd(REG_VCELL, 0xC350), rd(REG_SOC, 0x4B80), rd(REG_SOC, 0xFFFF)];
        let mut g = Max17048Minimal::new(I2cMock::new(&t), ADDR, 1).unwrap();
        assert!(near(g.voltage().unwrap(), 3.90625, 1e-5));
        assert!(near(g.percent().unwrap(), 75.5, 1e-5));
        assert_eq!(g.percent().unwrap(), 100.0);
        g.i2c.done();
        let t2 = vec![rd(REG_VCELL, 0xC350)];
        let mut two = Max17048Minimal::new(I2cMock::new(&t2), ADDR, 2).unwrap();
        assert!(near(two.voltage().unwrap(), 7.8125, 1e-5));
        two.i2c.done();
    }

    #[test]
    fn full_api() {
        let t = vec![
            rd(REG_VCELL, 0xC350),
            rd(REG_SOC, 0x4B80),
            rd(REG_CRATE, 0xFFEC),
            rd(REG_CRATE, 0x0064),
            rd(REG_VERSION, 0x0012),
            rd(REG_VRESET, 0x96AB),
            // set_rcomp(0x80), get_rcomp
            rd(REG_CONFIG, 0x971C), wr(REG_CONFIG, 0x801C),
            rd(REG_CONFIG, 0x971C),
            // compensate_temperature(35) -> 0x90, (5) -> 0xE2
            rd(REG_CONFIG, 0x971C), wr(REG_CONFIG, 0x901C),
            rd(REG_CONFIG, 0x971C), wr(REG_CONFIG, 0xE21C),
            // set_alert_soc(10) -> ATHD 22, get_alert_soc
            rd(REG_CONFIG, 0x971C), wr(REG_CONFIG, 0x9716),
            rd(REG_CONFIG, 0x9716),
            // set_soc_change_alert(true)
            rd(REG_CONFIG, 0x9716), wr(REG_CONFIG, 0x9756),
            // set_voltage_alert(3.3, 4.2), get, disabled window
            wr(REG_VALRT, 0xA5D2),
            rd(REG_VALRT, 0xA5D2),
            wr(REG_VALRT, 0x00FF),
            // set_reset_voltage(3.0) keeps the low bits, get, comparator on/off
            rd(REG_VRESET, 0x015A), wr(REG_VRESET, 0x975A),
            rd(REG_VRESET, 0x975A),
            rd(REG_VRESET, 0x975A), wr(REG_VRESET, 0x965A),
            // enable_voltage_reset_alert(true)
            rd(REG_STATUS, 0x0100), wr(REG_STATUS, 0x4100),
            // hibernate thresholds
            wr(REG_HIBRT, 0x8030),
            rd(REG_HIBRT, 0x8030),
            wr(REG_HIBRT, 0x0000),
            wr(REG_HIBRT, 0xFFFF),
            // is_hibernating (true, false)
            rd(REG_MODE, 0x1000),
            rd(REG_MODE, 0x0000),
            // enable_sleep, quick_start keeps EnSleep, sleep, wake
            wr(REG_MODE, 0x2000),
            wr(REG_MODE, 0x6000),
            rd(REG_CONFIG, 0x971C), wr(REG_CONFIG, 0x979C),
            rd(REG_CONFIG, 0x979C), wr(REG_CONFIG, 0x971C),
            // clear_status(RI)
            rd(REG_STATUS, 0x1100), wr(REG_STATUS, 0x1000),
            rd(REG_CONFIG, 0x973C), wr(REG_CONFIG, 0x971C),
            // poll_interrupt with RI | HD set, then with nothing set
            rd(REG_STATUS, 0x5100),
            rd(REG_STATUS, 0x5100), wr(REG_STATUS, 0x4000),
            rd(REG_CONFIG, 0x973C), wr(REG_CONFIG, 0x971C),
            rd(REG_STATUS, 0x4000),
            // disable_interrupt(UNDERVOLTAGE / OVERVOLTAGE)
            rd(REG_VALRT, 0xA5D2), wr(REG_VALRT, 0x00D2),
            rd(REG_VALRT, 0x00D2), wr(REG_VALRT, 0x00FF),
            // reset: the write is not acknowledged but the error is ignored
            wr(REG_CMD, 0x5400).with_error(embedded_hal::i2c::ErrorKind::NoAcknowledge(
                embedded_hal::i2c::NoAcknowledgeSource::Data)),
        ];
        let mut g = Max17048Full::new(I2cMock::new(&t), ADDR, 1).unwrap();
        assert_eq!(g.raw_voltage().unwrap(), 0xC350);
        assert_eq!(g.raw_soc().unwrap(), 0x4B80);
        assert!(near(g.charge_rate().unwrap(), -4.16, 1e-4));
        assert!(near(g.charge_rate().unwrap(), 20.8, 1e-4));
        assert_eq!(g.version().unwrap(), 0x0012);
        assert_eq!(g.chip_id().unwrap(), 0xAB);
        g.set_rcomp(0x80).unwrap();
        assert_eq!(g.get_rcomp().unwrap(), 0x97);
        g.compensate_temperature(35.0).unwrap();
        g.compensate_temperature(5.0).unwrap();
        g.set_alert_soc(10).unwrap();
        assert_eq!(g.get_alert_soc().unwrap(), 10);
        g.set_soc_change_alert(true).unwrap();
        g.set_voltage_alert(3.3, 4.2).unwrap();
        let (lo, hi) = g.get_voltage_alert().unwrap();
        assert!(near(lo, 3.3, 1e-4) && near(hi, 4.2, 1e-4));
        g.set_voltage_alert(0.0, 5.1).unwrap();
        assert_eq!(g.set_voltage_alert(4.0, 3.0), Err(Max17048Error::InvalidArgument));
        g.set_reset_voltage(3.0).unwrap();
        assert!(near(g.get_reset_voltage().unwrap(), 3.0, 1e-4));
        g.set_comparator_disabled(false).unwrap();
        g.enable_voltage_reset_alert(true).unwrap();
        g.set_hibernate_thresholds(26.624, 0.06).unwrap();
        let (hib, act) = g.get_hibernate_thresholds().unwrap();
        assert!(near(hib, 26.624, 1e-3) && near(act, 0.06, 1e-5));
        g.disable_hibernate().unwrap();
        g.force_hibernate().unwrap();
        assert!(g.is_hibernating());
        assert!(!g.is_hibernating());
        g.enable_sleep(true).unwrap();
        g.quick_start().unwrap();
        g.sleep().unwrap();
        g.wake().unwrap();
        g.clear_status(SOURCE_RESET).unwrap();
        assert_eq!(g.poll_interrupt().unwrap(), 0x1100);
        assert_eq!(g.poll_interrupt().unwrap(), 0);
        g.disable_interrupt(SOURCE_UNDERVOLTAGE).unwrap();
        g.disable_interrupt(SOURCE_OVERVOLTAGE).unwrap();
        g.reset(&mut NoopDelay::new()).unwrap();
        g.inner.i2c.done();
    }
}
