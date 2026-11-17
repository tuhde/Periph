//! LC709203F — 1-cell lithium-ion / polymer battery fuel gauge ("Smart LiB Gauge", onsemi).
//!
//! Communicates over I²C at address `0x0B` (up to 400 kHz, the chip stretches the clock).
//! Every transfer carries a CRC-8 (polynomial `0x07`, init `0x00`, computed over the address
//! bytes as well); the driver appends it on writes and verifies it on reads (one retry on a
//! CRC mismatch or bus error).
//!
//! The constructors run the datasheet starting flow (operational mode, APA, battery profile,
//! I²C temperature mode at 25 °C) and therefore need a [`DelayNs`] provider. The Full driver's
//! interrupt support is [`Lc709203fFull::poll_interrupt`] only: the caller wires it into an ISR
//! (falling edge on ALARMB) or a polling loop.
//!
//! ## Constants
//!
//! The current-direction and alarm constants live in this module
//! (`periph::chips::power::lc709203f::…`) and are not re-exported at the category level.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_BEFORE_RSOC: u8 = 0x04;
const REG_THERMISTOR_B: u8 = 0x06;
const REG_INITIAL_RSOC: u8 = 0x07;
const REG_TEMPERATURE: u8 = 0x08;
const REG_VOLTAGE: u8 = 0x09;
const REG_DIRECTION: u8 = 0x0A;
const REG_APA: u8 = 0x0B;
const REG_APT: u8 = 0x0C;
const REG_RSOC: u8 = 0x0D;
const REG_ITE: u8 = 0x0F;
const REG_IC_VERSION: u8 = 0x11;
const REG_PROFILE: u8 = 0x12;
const REG_ALARM_RSOC: u8 = 0x13;
const REG_ALARM_VOLTAGE: u8 = 0x14;
const REG_POWER_MODE: u8 = 0x15;
const REG_STATUS: u8 = 0x16;
const REG_PROFILE_ID: u8 = 0x1A;

const APA_TABLE: [(u32, u8); 6] = [(100, 0x08), (200, 0x0B), (500, 0x10), (1000, 0x19), (2000, 0x2D), (3000, 0x36)];

/// Current direction: automatic (power-on default).
pub const DIRECTION_AUTO: u16 = 0x0000;
/// Current direction: charge.
pub const DIRECTION_CHARGE: u16 = 0x0001;
/// Current direction: discharge.
pub const DIRECTION_DISCHARGE: u16 = 0xFFFF;
/// Alarm bitmask bit: RSOC below the Alarm Low RSOC threshold.
pub const ALARM_RSOC: u8 = 0x01;
/// Alarm bitmask bit: cell voltage below the Alarm Low Cell Voltage threshold.
pub const ALARM_VOLTAGE: u8 = 0x02;

/// Errors returned by the LC709203F drivers.
#[derive(Debug)]
pub enum Lc709203fError<E> {
    /// Underlying I²C bus error.
    I2c(E),
    /// The CRC-8 of a read did not match, twice in a row.
    Crc,
    /// An argument was outside the range the chip accepts.
    InvalidArgument,
}

impl<E> From<E> for Lc709203fError<E> {
    fn from(e: E) -> Self {
        Lc709203fError::I2c(e)
    }
}

/// CRC-8 (ATM): polynomial `0x07`, init `0x00`, MSB first, no reflection, no final XOR.
pub fn crc8_atm(data: &[u8]) -> u8 {
    let mut crc = 0u8;
    for &b in data {
        crc ^= b;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x07 } else { crc << 1 };
        }
    }
    crc
}

/// APA value interpolated from the datasheet table (Type-01/03) for a design capacity in mAh.
pub fn apa_for(capacity_mah: u32) -> u8 {
    let t = &APA_TABLE;
    if capacity_mah <= t[0].0 {
        return t[0].1;
    }
    if capacity_mah >= t[5].0 {
        return t[5].1;
    }
    for i in 1..t.len() {
        if capacity_mah <= t[i].0 {
            let (c0, a0) = (t[i - 1].0 as f32, t[i - 1].1 as f32);
            let (c1, a1) = (t[i].0 as f32, t[i].1 as f32);
            return libm_round(a0 + (a1 - a0) * (capacity_mah as f32 - c0) / (c1 - c0)) as u8;
        }
    }
    t[5].1
}

fn libm_round(x: f32) -> f32 {
    // x is always positive here: round half up without std/libm.
    (x + 0.5) as u32 as f32
}

fn read_word<I2C: I2c>(i2c: &mut I2C, addr: u8, cmd: u8) -> Result<u16, Lc709203fError<I2C::Error>> {
    let mut last = Lc709203fError::Crc;
    for _ in 0..2 {
        let mut buf = [0u8; 3];
        match register::read_register(i2c, addr, cmd as u32, 1, &mut buf) {
            Ok(()) => {
                if crc8_atm(&[addr << 1, cmd, (addr << 1) | 1, buf[0], buf[1]]) == buf[2] {
                    return Ok(buf[0] as u16 | ((buf[1] as u16) << 8));
                }
                last = Lc709203fError::Crc;
            }
            Err(e) => last = Lc709203fError::I2c(e),
        }
    }
    Err(last)
}

fn write_word_opt<I2C: I2c>(i2c: &mut I2C, addr: u8, cmd: u8, value: u16, retry: bool) -> Result<(), I2C::Error> {
    let (low, high) = ((value & 0xFF) as u8, (value >> 8) as u8);
    let data = [low, high, crc8_atm(&[addr << 1, cmd, low, high])];
    match register::write_register(i2c, addr, cmd as u32, 1, &data) {
        Err(_) if retry => register::write_register(i2c, addr, cmd as u32, 1, &data),
        r => r,
    }
}

fn write_word<I2C: I2c>(i2c: &mut I2C, addr: u8, cmd: u8, value: u16) -> Result<(), I2C::Error> {
    write_word_opt(i2c, addr, cmd, value, true)
}

fn wake_and_operational<I2C: I2c, D: DelayNs>(i2c: &mut I2C, addr: u8, delay: &mut D) {
    // Written twice, ignoring a bus error: the first transfer may be lost while the chip wakes up.
    let _ = write_word_opt(i2c, addr, REG_POWER_MODE, 0x0001, false);
    let _ = write_word_opt(i2c, addr, REG_POWER_MODE, 0x0001, false);
    delay.delay_ms(1);
}

fn set_temperature<I2C: I2c>(i2c: &mut I2C, addr: u8, celsius: f32) -> Result<(), I2C::Error> {
    let c = celsius.clamp(-20.0, 60.0);
    let raw = ((c + 273.15) * 10.0 + 0.5) as u16;
    write_word(i2c, addr, REG_TEMPERATURE, raw)
}

/// LC709203F minimal driver — cell voltage, state of charge and temperature.
///
/// The constructor performs the starting flow: operational mode, APA from the design capacity,
/// battery profile, I²C temperature mode with 25 °C.
pub struct Lc709203fMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Lc709203fMinimal<I2C> {
    /// Create a new `Lc709203fMinimal` and run the starting flow.
    ///
    /// # Arguments
    /// * `i2c`          — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`         — 7-bit device address (`0x0B`).
    /// * `capacity_mah` — Design capacity in mAh (clamped to 100–3000 for the APA interpolation).
    /// * `profile`      — Battery profile written to register `0x12` (0 or 1).
    /// * `delay`        — Delay provider (TINIT = 90 ms re-initialization wait).
    pub fn new<D: DelayNs>(
        mut i2c: I2C, addr: u8, capacity_mah: u32, profile: u8, delay: &mut D,
    ) -> Result<Self, Lc709203fError<I2C::Error>> {
        wake_and_operational(&mut i2c, addr, delay);
        write_word(&mut i2c, addr, REG_APA, apa_for(capacity_mah) as u16)?;
        write_word(&mut i2c, addr, REG_PROFILE, (profile & 1) as u16)?;
        delay.delay_ms(100);
        wake_and_operational(&mut i2c, addr, delay);
        write_word(&mut i2c, addr, REG_STATUS, 0x0000)?;
        set_temperature(&mut i2c, addr, 25.0)?;
        Ok(Self { i2c, addr })
    }

    /// Read the cell voltage in volts (raw × 1 mV).
    pub fn voltage(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        Ok(read_word(&mut self.i2c, self.addr, REG_VOLTAGE)? as f32 / 1000.0)
    }

    /// Read the state of charge from the ITE register in % (0.0–100.0).
    pub fn percent(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        Ok(read_word(&mut self.i2c, self.addr, REG_ITE)? as f32 / 10.0)
    }

    /// Read the cell temperature in °C.
    pub fn temperature(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        Ok(read_word(&mut self.i2c, self.addr, REG_TEMPERATURE)? as f32 / 10.0 - 273.15)
    }

    /// Supply the cell temperature (I²C temperature mode), clamped to −20…+60 °C.
    pub fn set_temperature(&mut self, celsius: f32) -> Result<(), Lc709203fError<I2C::Error>> {
        Ok(set_temperature(&mut self.i2c, self.addr, celsius)?)
    }
}

/// LC709203F full driver — extends [`Lc709203fMinimal`] with RSOC control, thermistor mode,
/// alarms, battery profiles and power mode.
///
/// Interrupt support is [`poll_interrupt`](Self::poll_interrupt) only; the caller is
/// responsible for wiring it into an ISR (falling edge on ALARMB) or a polling loop.
pub struct Lc709203fFull<I2C> {
    inner: Lc709203fMinimal<I2C>,
    alarm_rsoc: u16,
    alarm_voltage: u16,
}

impl<I2C: I2c> Lc709203fFull<I2C> {
    /// Create a new `Lc709203fFull` and run the starting flow. Same arguments as [`Lc709203fMinimal::new`].
    pub fn new<D: DelayNs>(
        i2c: I2C, addr: u8, capacity_mah: u32, profile: u8, delay: &mut D,
    ) -> Result<Self, Lc709203fError<I2C::Error>> {
        let mut inner = Lc709203fMinimal::new(i2c, addr, capacity_mah, profile, delay)?;
        let alarm_rsoc = read_word(&mut inner.i2c, addr, REG_ALARM_RSOC)?;
        let alarm_voltage = read_word(&mut inner.i2c, addr, REG_ALARM_VOLTAGE)?;
        Ok(Self { inner, alarm_rsoc, alarm_voltage })
    }

    /// Read the cell voltage in volts. Delegates to the inner [`Lc709203fMinimal`].
    pub fn voltage(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        self.inner.voltage()
    }

    /// Read the state of charge in %. Delegates to the inner [`Lc709203fMinimal`].
    pub fn percent(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        self.inner.percent()
    }

    /// Read the cell temperature in °C. Delegates to the inner [`Lc709203fMinimal`].
    pub fn temperature(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        self.inner.temperature()
    }

    /// Supply the cell temperature in °C. Delegates to the inner [`Lc709203fMinimal`].
    pub fn set_temperature(&mut self, celsius: f32) -> Result<(), Lc709203fError<I2C::Error>> {
        self.inner.set_temperature(celsius)
    }

    fn rd(&mut self, cmd: u8) -> Result<u16, Lc709203fError<I2C::Error>> {
        read_word(&mut self.inner.i2c, self.inner.addr, cmd)
    }

    fn wr(&mut self, cmd: u8, value: u16) -> Result<(), Lc709203fError<I2C::Error>> {
        Ok(write_word(&mut self.inner.i2c, self.inner.addr, cmd, value)?)
    }

    /// Read the RSOC register in % (0–100).
    pub fn rsoc(&mut self) -> Result<u8, Lc709203fError<I2C::Error>> {
        Ok(self.rd(REG_RSOC)? as u8)
    }

    /// Force the RSOC register (0–100 %); use sleep mode to keep the value.
    pub fn set_rsoc(&mut self, percent: u8) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_RSOC, percent.min(100) as u16)
    }

    /// Initialize RSOC from the present voltage (`0x07` ← `0xAA55`); waits 2 ms.
    pub fn init_rsoc<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_INITIAL_RSOC, 0xAA55)?;
        delay.delay_ms(2);
        Ok(())
    }

    /// Initialize RSOC from the maximum voltage since reset (`0x04` ← `0xAA55`); waits 2 ms.
    pub fn before_rsoc<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_BEFORE_RSOC, 0xAA55)?;
        delay.delay_ms(2);
        Ok(())
    }

    /// Switch to thermistor temperature mode with the given B constant (K).
    pub fn use_thermistor(&mut self, b_constant: u16) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_THERMISTOR_B, b_constant)?;
        self.wr(REG_STATUS, 0x0001)
    }

    /// Switch to I²C temperature mode and write a temperature in °C.
    pub fn use_i2c_temperature(&mut self, celsius: f32) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_STATUS, 0x0000)?;
        self.set_temperature(celsius)
    }

    /// Return `true` if the temperature source is the thermistor.
    pub fn is_thermistor_mode(&mut self) -> Result<bool, Lc709203fError<I2C::Error>> {
        Ok(self.rd(REG_STATUS)? == 0x0001)
    }

    /// Read the thermistor B constant (K).
    pub fn get_b_constant(&mut self) -> Result<u16, Lc709203fError<I2C::Error>> {
        self.rd(REG_THERMISTOR_B)
    }

    /// Write the Adjustment Pack Application register (0–255).
    pub fn set_apa(&mut self, value: u8) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_APA, value as u16)
    }

    /// Read the Adjustment Pack Application register.
    pub fn get_apa(&mut self) -> Result<u8, Lc709203fError<I2C::Error>> {
        Ok(self.rd(REG_APA)? as u8)
    }

    /// Write the Adjustment Pack Thermistor register.
    pub fn set_apt(&mut self, value: u16) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_APT, value)
    }

    /// Read the Adjustment Pack Thermistor register.
    pub fn get_apt(&mut self) -> Result<u16, Lc709203fError<I2C::Error>> {
        self.rd(REG_APT)
    }

    /// Set the current direction ([`DIRECTION_AUTO`], [`DIRECTION_CHARGE`] or [`DIRECTION_DISCHARGE`]);
    /// any other value returns [`Lc709203fError::InvalidArgument`].
    pub fn set_current_direction(&mut self, mode: u16) -> Result<(), Lc709203fError<I2C::Error>> {
        if mode != DIRECTION_AUTO && mode != DIRECTION_CHARGE && mode != DIRECTION_DISCHARGE {
            return Err(Lc709203fError::InvalidArgument);
        }
        self.wr(REG_DIRECTION, mode)
    }

    /// Read the current direction register.
    pub fn get_current_direction(&mut self) -> Result<u16, Lc709203fError<I2C::Error>> {
        self.rd(REG_DIRECTION)
    }

    /// Select battery profile 0 or 1 (re-initializes the chip; waits 90 ms, then re-enters operational mode).
    pub fn set_profile<D: DelayNs>(&mut self, profile: u8, delay: &mut D) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_PROFILE, (profile & 1) as u16)?;
        delay.delay_ms(100);
        wake_and_operational(&mut self.inner.i2c, self.inner.addr, delay);
        Ok(())
    }

    /// Read the selected battery profile (0 or 1).
    pub fn get_profile(&mut self) -> Result<u8, Lc709203fError<I2C::Error>> {
        Ok(self.rd(REG_PROFILE)? as u8)
    }

    /// Read the Number of the Parameter register (e.g. `0x0301`).
    pub fn profile_id(&mut self) -> Result<u16, Lc709203fError<I2C::Error>> {
        self.rd(REG_PROFILE_ID)
    }

    /// Read the IC Version register.
    pub fn ic_version(&mut self) -> Result<u16, Lc709203fError<I2C::Error>> {
        self.rd(REG_IC_VERSION)
    }

    /// Set the low-RSOC alarm threshold in % (0 disables, 1–100).
    pub fn set_alarm_rsoc(&mut self, percent: u8) -> Result<(), Lc709203fError<I2C::Error>> {
        let p = percent.min(100) as u16;
        self.wr(REG_ALARM_RSOC, p)?;
        self.alarm_rsoc = p;
        Ok(())
    }

    /// Read the low-RSOC alarm threshold in %.
    pub fn get_alarm_rsoc(&mut self) -> Result<u8, Lc709203fError<I2C::Error>> {
        Ok(self.rd(REG_ALARM_RSOC)? as u8)
    }

    /// Set the low-voltage alarm threshold in volts (0 disables; resolution 1 mV).
    pub fn set_alarm_voltage(&mut self, volts: f32) -> Result<(), Lc709203fError<I2C::Error>> {
        let raw = (volts * 1000.0 + 0.5).clamp(0.0, 65535.0) as u16;
        self.wr(REG_ALARM_VOLTAGE, raw)?;
        self.alarm_voltage = raw;
        Ok(())
    }

    /// Read the low-voltage alarm threshold in volts.
    pub fn get_alarm_voltage(&mut self) -> Result<f32, Lc709203fError<I2C::Error>> {
        Ok(self.rd(REG_ALARM_VOLTAGE)? as f32 / 1000.0)
    }

    /// Enter sleep mode (only I²C stays alive; charge counting stops).
    pub fn sleep(&mut self) -> Result<(), Lc709203fError<I2C::Error>> {
        self.wr(REG_POWER_MODE, 0x0002)
    }

    /// Wake from sleep: dummy transaction (SDA pulse), wait 400 µs, enter operational mode.
    pub fn wake<D: DelayNs>(&mut self, delay: &mut D) {
        wake_and_operational(&mut self.inner.i2c, self.inner.addr, delay);
    }

    /// Return `true` if the chip is in sleep mode (the bus is woken first).
    pub fn is_sleeping<D: DelayNs>(&mut self, delay: &mut D) -> Result<bool, Lc709203fError<I2C::Error>> {
        let _ = self.inner.i2c.write(self.inner.addr, &[REG_POWER_MODE]);
        delay.delay_ms(1);
        Ok(self.rd(REG_POWER_MODE)? == 0x0002)
    }

    /// Evaluate RSOC and voltage against the cached thresholds; returns a bitmask of
    /// [`ALARM_RSOC`] / [`ALARM_VOLTAGE`] (0 = no alarm).
    ///
    /// The caller is responsible for wiring this into an ISR (falling edge on ALARMB) or a polling loop.
    pub fn poll_interrupt(&mut self) -> Result<u8, Lc709203fError<I2C::Error>> {
        let mut status = 0u8;
        if self.alarm_rsoc != 0 && self.rd(REG_RSOC)? < self.alarm_rsoc {
            status |= ALARM_RSOC;
        }
        if self.alarm_voltage != 0 && self.rd(REG_VOLTAGE)? < self.alarm_voltage {
            status |= ALARM_VOLTAGE;
        }
        Ok(status)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = 0x0B;

    fn rd(cmd: u8, value: u16) -> T {
        let (lo, hi) = ((value & 0xFF) as u8, (value >> 8) as u8);
        T::write_read(ADDR, vec![cmd], vec![lo, hi, crc8_atm(&[0x16, cmd, 0x17, lo, hi])])
    }

    fn wr(cmd: u8, value: u16) -> T {
        let (lo, hi) = ((value & 0xFF) as u8, (value >> 8) as u8);
        T::write(ADDR, vec![cmd, lo, hi, crc8_atm(&[0x16, cmd, lo, hi])])
    }

    fn init_transactions(apa: u16, profile: u16) -> Vec<T> {
        vec![
            wr(REG_POWER_MODE, 1), wr(REG_POWER_MODE, 1),
            wr(REG_APA, apa), wr(REG_PROFILE, profile),
            wr(REG_POWER_MODE, 1), wr(REG_POWER_MODE, 1),
            wr(REG_STATUS, 0), wr(REG_TEMPERATURE, 0x0BA6),
        ]
    }

    #[test]
    fn crc_datasheet_examples() {
        assert_eq!(crc8_atm(&[0x16, 0x09, 0x17, 0xC2, 0x0E]), 0x86);
        assert_eq!(crc8_atm(&[0x16, 0x09, 0x55, 0xAA]), 0x3B);
    }

    #[test]
    fn apa_interpolation() {
        assert_eq!(apa_for(700), 0x14);
        assert_eq!(apa_for(50), 0x08);
        assert_eq!(apa_for(9000), 0x36);
        assert_eq!(apa_for(1000), 0x19);
    }

    #[test]
    fn minimal_api() {
        let mut t = init_transactions(0x19, 1);
        t.push(rd(REG_VOLTAGE, 3778));
        t.push(rd(REG_ITE, 755));
        t.push(rd(REG_TEMPERATURE, 0x0BA6));
        t.push(wr(REG_TEMPERATURE, 0x0D04)); // 100 °C clamped to +60 °C
        t.push(wr(REG_TEMPERATURE, 0x09E4)); // −50 °C clamped to −20 °C
        let mut i2c = I2cMock::new(&t);
        let mut chip = Lc709203fMinimal::new(i2c.clone(), ADDR, 1000, 1, &mut NoopDelay::new()).unwrap();
        assert!((chip.voltage().unwrap() - 3.778).abs() < 1e-4);
        assert!((chip.percent().unwrap() - 75.5).abs() < 1e-4);
        assert!((chip.temperature().unwrap() - 25.0).abs() < 0.06);
        chip.set_temperature(100.0).unwrap();
        chip.set_temperature(-50.0).unwrap();
        i2c.done();
    }

    #[test]
    fn crc_mismatch_retries_then_fails() {
        let mut t = init_transactions(0x19, 1);
        t.push(T::write_read(ADDR, vec![REG_VOLTAGE], vec![0xC2, 0x0E, 0x00]));
        t.push(T::write_read(ADDR, vec![REG_VOLTAGE], vec![0xC2, 0x0E, 0x00]));
        t.push(T::write_read(ADDR, vec![REG_VOLTAGE], vec![0xC2, 0x0E, 0x00]));
        t.push(rd(REG_VOLTAGE, 3778));
        let mut i2c = I2cMock::new(&t);
        let mut chip = Lc709203fMinimal::new(i2c.clone(), ADDR, 1000, 1, &mut NoopDelay::new()).unwrap();
        assert!(matches!(chip.voltage(), Err(Lc709203fError::Crc)));
        // a following good read succeeds
        assert!((chip.voltage().unwrap() - 3.778).abs() < 1e-4);
        i2c.done();
    }

    #[test]
    fn full_api() {
        let mut t = init_transactions(0x19, 1);
        t.push(rd(REG_ALARM_RSOC, 8));
        t.push(rd(REG_ALARM_VOLTAGE, 0));
        t.push(rd(REG_RSOC, 42));
        t.push(wr(REG_RSOC, 55));
        t.push(wr(REG_INITIAL_RSOC, 0xAA55));
        t.push(wr(REG_BEFORE_RSOC, 0xAA55));
        t.push(wr(REG_THERMISTOR_B, 3380));
        t.push(wr(REG_STATUS, 1));
        t.push(rd(REG_STATUS, 1));
        t.push(wr(REG_STATUS, 0));
        t.push(wr(REG_TEMPERATURE, 0x0BA6));
        t.push(wr(REG_DIRECTION, 0xFFFF));
        t.push(wr(REG_APA, 0x2D));
        t.push(wr(REG_APT, 0x1234));
        t.push(wr(REG_PROFILE, 0));
        t.push(wr(REG_POWER_MODE, 1));
        t.push(wr(REG_POWER_MODE, 1));
        t.push(rd(REG_PROFILE_ID, 0x0301));
        t.push(rd(REG_IC_VERSION, 0x1234));
        t.push(wr(REG_POWER_MODE, 2));
        t.push(T::write(ADDR, vec![REG_POWER_MODE]));
        t.push(rd(REG_POWER_MODE, 2));
        t.push(wr(REG_ALARM_RSOC, 20));
        t.push(wr(REG_ALARM_VOLTAGE, 3500));
        t.push(rd(REG_RSOC, 10));
        t.push(rd(REG_VOLTAGE, 3400));
        t.push(rd(REG_RSOC, 50));
        t.push(rd(REG_VOLTAGE, 3900));
        let mut i2c = I2cMock::new(&t);
        let mut d = NoopDelay::new();
        let mut chip = Lc709203fFull::new(i2c.clone(), ADDR, 1000, 1, &mut d).unwrap();
        assert_eq!(chip.rsoc().unwrap(), 42);
        chip.set_rsoc(55).unwrap();
        chip.init_rsoc(&mut d).unwrap();
        chip.before_rsoc(&mut d).unwrap();
        chip.use_thermistor(3380).unwrap();
        assert!(chip.is_thermistor_mode().unwrap());
        chip.use_i2c_temperature(25.0).unwrap();
        chip.set_current_direction(DIRECTION_DISCHARGE).unwrap();
        assert!(matches!(chip.set_current_direction(5), Err(Lc709203fError::InvalidArgument)));
        chip.set_apa(0x2D).unwrap();
        chip.set_apt(0x1234).unwrap();
        chip.set_profile(0, &mut d).unwrap();
        assert_eq!(chip.profile_id().unwrap(), 0x0301);
        assert_eq!(chip.ic_version().unwrap(), 0x1234);
        chip.sleep().unwrap();
        assert!(chip.is_sleeping(&mut d).unwrap());
        chip.set_alarm_rsoc(20).unwrap();
        chip.set_alarm_voltage(3.5).unwrap();
        assert_eq!(chip.poll_interrupt().unwrap(), ALARM_RSOC | ALARM_VOLTAGE);
        assert_eq!(chip.poll_interrupt().unwrap(), 0);
        i2c.done();
    }
}
