//! DS3502 — 7-bit nonvolatile high-voltage I²C digital potentiometer (Maxim).
//!
//! A single 128-position potentiometer (10 kΩ end to end, ±20 %) whose
//! terminals RH / RL / RW accept up to 15.5 V (V+ = 4.5–15.5 V) while the logic
//! runs from VCC = 2.5–5.5 V. The wiper position lives in the Wiper Register
//! (WR, SRAM); the Initial Value Register (IVR, EEPROM) holds the power-up
//! position (factory default `0x40`). The 7-bit I²C address is `0x28`
//! (A1 = A0 = GND) to `0x2B` (A1 = A0 = VCC).
//!
//! Two registers exist: `0x00` is WR (and, depending on the MODE bit of CR,
//! also IVR; reads always return WR) and `0x02` is the Control Register whose
//! bit 7 selects MODE (1 = writes to `0x00` only change WR). The driver uses
//! register access (1-byte address) and runs with MODE = 1 so routine wiper
//! writes never wear the EEPROM; [`DS3502Full::store_wiper`] is the only
//! operation that writes the IVR.
//!
//! The driver needs a delay provider only for [`DS3502Minimal::init`] (the
//! power-up retry) and the EEPROM write wait ([`DS3502Full::wait_ready`]).

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// 7-bit I²C address with A1 = A0 = GND.
pub const DS3502_I2C_ADDRESS: u8 = 0x28;

const REG_WR: u8 = 0x00;
const REG_CR: u8 = 0x02;
const CR_MODE_SRAM: u8 = 0x80;
const CR_MODE_EEPROM: u8 = 0x00;
const MAX_CODE: u8 = 127;
const R_TOTAL: f32 = 10_000.0;

/// Errors returned by the DS3502 driver.
#[derive(Debug)]
pub enum Ds3502Error<E> {
    /// I²C bus error.
    I2c(E),
    /// The EEPROM write did not finish within the timeout.
    Timeout,
}

impl<E> From<E> for Ds3502Error<E> {
    fn from(e: E) -> Self {
        Ds3502Error::I2c(e)
    }
}

/// Wiper terminal for resistance queries.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ds3502Terminal {
    /// Wiper-to-RL resistance.
    Low,
    /// Wiper-to-RH resistance.
    High,
}

fn clamp_code(code: i32) -> u8 {
    if code < 0 {
        0
    } else if code > MAX_CODE as i32 {
        MAX_CODE
    } else {
        code as u8
    }
}

fn round_f32(x: f32) -> i32 {
    if x >= 0.0 { (x + 0.5) as i32 } else { (x - 0.5) as i32 }
}

/// DS3502 — minimal interface: set and read back the wiper position or the
/// unloaded output voltage (volatile, SRAM only).
pub struct DS3502Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    v_high: f32,
    v_low: f32,
    code: u8,
}

impl<I2C: I2c> DS3502Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). `addr` is `0x28`–`0x2B` ([`DS3502_I2C_ADDRESS`]);
    /// `v_high` / `v_low` are the voltages applied to RH / RL in volts
    /// (defaults 1.0 / 0.0 make the voltage functions ratios).
    pub fn new(i2c: I2C, addr: u8, v_high: f32, v_low: f32) -> Self {
        Self { i2c, addr, v_high, v_low, code: 0 }
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    fn write_code(&mut self, code: i32) -> Result<(), I2C::Error> {
        let code = clamp_code(code);
        self.write_reg(REG_WR, code)?;
        self.code = code;
        Ok(())
    }

    fn read_code(&mut self) -> Result<u8, I2C::Error> {
        let code = self.read_reg(REG_WR)? & MAX_CODE;
        self.code = code;
        Ok(code)
    }

    /// Run the initialization sequence: write CR ← `0x80` (MODE = 1), retrying
    /// once after 3 ms if the chip NACKs because it is still in its power-up
    /// recall, then read WR. The wiper is left where the IVR put it.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Ds3502Error<I2C::Error>> {
        if self.write_reg(REG_CR, CR_MODE_SRAM).is_err() {
            delay.delay_ms(3);
            self.write_reg(REG_CR, CR_MODE_SRAM)?;
        }
        self.read_code()?;
        Ok(())
    }

    /// Set the wiper position (volatile, SRAM only); `code` is clamped to 0–127.
    pub fn set_wiper(&mut self, code: i32) -> Result<(), I2C::Error> {
        self.write_code(code)
    }

    /// Read back the wiper position (WR), 0–127.
    pub fn get_wiper(&mut self) -> Result<u8, I2C::Error> {
        self.read_code()
    }

    /// Set the unloaded RW output voltage to the nearest reachable code
    /// (clamped to the range between `v_low` and `v_high`).
    pub fn set_voltage(&mut self, volts: f32) -> Result<(), I2C::Error> {
        let span = self.v_high - self.v_low;
        if span == 0.0 {
            return self.write_code(0);
        }
        self.write_code(round_f32((volts - self.v_low) / span * MAX_CODE as f32))
    }

    /// Read the unloaded RW voltage for the current wiper code:
    /// `v_low + (v_high - v_low) * code / 127`.
    pub fn get_voltage(&mut self) -> Result<f32, I2C::Error> {
        let code = self.read_code()?;
        Ok(self.v_low + (self.v_high - self.v_low) * code as f32 / MAX_CODE as f32)
    }
}

/// DS3502 — full interface: ratio and resistance helpers, increment, EEPROM
/// storage of the power-up value, MODE control and acknowledge polling.
pub struct DS3502Full<I2C> {
    inner: DS3502Minimal<I2C>,
}

impl<I2C: I2c> DS3502Full<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). See [`DS3502Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, v_high: f32, v_low: f32) -> Self {
        Self { inner: DS3502Minimal::new(i2c, addr, v_high, v_low) }
    }

    /// Run the initialization sequence. See [`DS3502Minimal::init`].
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Ds3502Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Set the wiper position; see [`DS3502Minimal::set_wiper`].
    pub fn set_wiper(&mut self, code: i32) -> Result<(), I2C::Error> {
        self.inner.set_wiper(code)
    }

    /// Read back the wiper position; see [`DS3502Minimal::get_wiper`].
    pub fn get_wiper(&mut self) -> Result<u8, I2C::Error> {
        self.inner.get_wiper()
    }

    /// Set the output voltage; see [`DS3502Minimal::set_voltage`].
    pub fn set_voltage(&mut self, volts: f32) -> Result<(), I2C::Error> {
        self.inner.set_voltage(volts)
    }

    /// Read the output voltage; see [`DS3502Minimal::get_voltage`].
    pub fn get_voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.get_voltage()
    }

    /// Set the wiper by divider ratio V(RW−RL)/V(RH−RL); the code is
    /// `round(ratio * 127)`, clamped.
    pub fn set_ratio(&mut self, ratio: f32) -> Result<(), I2C::Error> {
        self.inner.write_code(round_f32(ratio * MAX_CODE as f32))
    }

    /// Read the divider ratio, `code / 127`.
    pub fn get_ratio(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.inner.read_code()? as f32 / MAX_CODE as f32)
    }

    /// Nominal wiper-to-RL or wiper-to-RH resistance for the current code
    /// (RTOTAL = 10 kΩ ±20 %, so absolute values are approximate; the wiper
    /// resistance is not included).
    pub fn get_resistance(&mut self, terminal: Ds3502Terminal) -> Result<f32, I2C::Error> {
        let code = self.inner.read_code()? as f32;
        Ok(match terminal {
            Ds3502Terminal::Low => code / MAX_CODE as f32 * R_TOTAL,
            Ds3502Terminal::High => (MAX_CODE as f32 - code) / MAX_CODE as f32 * R_TOTAL,
        })
    }

    /// Move the wiper relative to its current position (SRAM only); returns
    /// the new code, clamped to 0–127.
    pub fn increment(&mut self, steps: i32) -> Result<u8, I2C::Error> {
        let code = clamp_code(self.inner.read_code()? as i32 + steps);
        self.inner.write_code(code as i32)?;
        Ok(code)
    }

    /// Write the Initial Value Register (EEPROM power-up position).
    ///
    /// Sets MODE = 0, writes WR / IVR, waits for the EEPROM write
    /// (acknowledge polling, ≤ 20 ms) and restores MODE = 1, also on error.
    /// The wiper moves to `code` (default: the current wiper position). Each
    /// call counts toward the EEPROM endurance (50 000 writes at 70 °C,
    /// 200 000 at 25 °C).
    pub fn store_wiper<D: DelayNs>(&mut self, delay: &mut D, code: Option<u8>) -> Result<(), Ds3502Error<I2C::Error>> {
        let code = match code {
            Some(c) => clamp_code(c as i32),
            None => self.inner.read_code()?,
        };
        self.inner.write_reg(REG_CR, CR_MODE_EEPROM)?;
        let result = (|| {
            self.inner.write_reg(REG_WR, code)?;
            self.inner.code = code;
            self.wait_ready(delay, 30)
        })();
        let restore = self.inner.write_reg(REG_CR, CR_MODE_SRAM);
        result?;
        restore?;
        Ok(())
    }

    /// Select what a write to the wiper register updates (advanced;
    /// [`Self::store_wiper`] handles it). `eeprom = true` is MODE = 0 (writes
    /// also program the IVR), `false` is MODE = 1 (SRAM only).
    pub fn set_mode(&mut self, eeprom: bool) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CR, if eeprom { CR_MODE_EEPROM } else { CR_MODE_SRAM })
    }

    /// Read the MODE bit of the Control Register; `true` if MODE = 0 (writes
    /// reach the EEPROM).
    pub fn get_mode(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CR)? & CR_MODE_SRAM == 0)
    }

    /// Acknowledge polling: repeat a read of CR until the chip ACKs (it
    /// NACKs while an EEPROM write is in progress), waiting 1 ms between
    /// attempts; returns [`Ds3502Error::Timeout`] after `timeout_ms`.
    pub fn wait_ready<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<(), Ds3502Error<I2C::Error>> {
        for _ in 0..=timeout_ms {
            if self.inner.read_reg(REG_CR).is_ok() {
                return Ok(());
            }
            delay.delay_ms(1);
        }
        Err(Ds3502Error::Timeout)
    }

    /// Change the RH / RL voltages used by the conversions (no I²C traffic).
    pub fn set_endpoints(&mut self, v_high: f32, v_low: f32) {
        self.inner.v_high = v_high;
        self.inner.v_low = v_low;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = DS3502_I2C_ADDRESS;

    fn w(reg: u8, v: u8) -> T {
        T::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![v])
    }

    fn init_t(wr: u8) -> Vec<T> {
        vec![w(REG_CR, 0x80), r(REG_WR, wr)]
    }

    fn full(t: &[T], v_high: f32, v_low: f32) -> (I2cMock, DS3502Full<I2cMock>) {
        let i2c = I2cMock::new(t);
        let mut chip = DS3502Full::new(i2c.clone(), ADDR, v_high, v_low);
        chip.init(&mut NoopDelay::new()).unwrap();
        (i2c, chip)
    }

    #[test]
    fn init_sets_mode_and_reads_wr() {
        let (mut i2c, mut chip) = full(&init_t(0x23), 1.0, 0.0);
        assert_eq!(chip.inner.code, 0x23);
        i2c.done();
    }

    #[test]
    fn init_retries_once_after_nack() {
        let t = vec![
            w(REG_CR, 0x80).with_error(ErrorKind::Other),
            w(REG_CR, 0x80),
            r(REG_WR, 0x40),
        ];
        let mut i2c = I2cMock::new(&t);
        let mut chip = DS3502Minimal::new(i2c.clone(), ADDR, 1.0, 0.0);
        chip.init(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn minimal_wiper_and_voltage() {
        let mut t = init_t(0x40);
        t.extend(vec![
            w(REG_WR, 100),                 // set_wiper(100)
            w(REG_WR, 127),                 // set_wiper(300) clamps
            w(REG_WR, 0),                   // set_wiper(-5) clamps
            r(REG_WR, 0xFF),                // get_wiper masks bit 7
            w(REG_WR, 51),                  // set_voltage(6.0) at 15 V span
            r(REG_WR, 51),                  // get_voltage
        ]);
        let i2c = I2cMock::new(&t);
        let mut chip = DS3502Minimal::new(i2c.clone(), ADDR, 15.0, 0.0);
        chip.init(&mut NoopDelay::new()).unwrap();
        chip.set_wiper(100).unwrap();
        chip.set_wiper(300).unwrap();
        chip.set_wiper(-5).unwrap();
        assert_eq!(chip.get_wiper().unwrap(), 127);
        chip.set_voltage(6.0).unwrap();
        let v = chip.get_voltage().unwrap();
        assert!((v - 15.0 * 51.0 / 127.0).abs() < 1e-4);
        let mut i2c = i2c;
        i2c.done();
    }

    #[test]
    fn voltage_offsets_and_zero_span() {
        let mut t = init_t(0x40);
        t.extend(vec![w(REG_WR, 64)]);
        let (mut i2c, mut chip) = full(&t, 12.0, 2.0);
        chip.set_voltage(7.0).unwrap();
        i2c.done();
        let mut t = init_t(0x40);
        t.extend(vec![w(REG_WR, 0)]);
        let (mut i2c, mut chip) = full(&t, 5.0, 5.0);
        chip.set_voltage(5.0).unwrap();
        i2c.done();
    }

    #[test]
    fn full_ratio_resistance_increment() {
        let mut t = init_t(0x40);
        t.extend(vec![
            w(REG_WR, 64),                  // set_ratio(0.5)
            w(REG_WR, 127),                 // set_ratio(2.0) clamps
            r(REG_WR, 127),                 // get_ratio
            r(REG_WR, 0), r(REG_WR, 0),     // get_resistance Low / High at code 0
            r(REG_WR, 127), r(REG_WR, 127), // at code 127
            r(REG_WR, 0x40), w(REG_WR, 69), // increment(5)
            r(REG_WR, 125), w(REG_WR, 127), // increment(10) clamps
            r(REG_WR, 3), w(REG_WR, 0),     // increment(-10) clamps
        ]);
        let (mut i2c, mut chip) = full(&t, 1.0, 0.0);
        chip.set_ratio(0.5).unwrap();
        chip.set_ratio(2.0).unwrap();
        assert_eq!(chip.get_ratio().unwrap(), 1.0);
        assert_eq!(chip.get_resistance(Ds3502Terminal::Low).unwrap(), 0.0);
        assert_eq!(chip.get_resistance(Ds3502Terminal::High).unwrap(), 10_000.0);
        assert_eq!(chip.get_resistance(Ds3502Terminal::Low).unwrap(), 10_000.0);
        assert_eq!(chip.get_resistance(Ds3502Terminal::High).unwrap(), 0.0);
        assert_eq!(chip.increment(5).unwrap(), 69);
        assert_eq!(chip.increment(10).unwrap(), 127);
        assert_eq!(chip.increment(-10).unwrap(), 0);
        i2c.done();
    }

    #[test]
    fn mode_and_store_wiper() {
        let mut t = init_t(0x40);
        t.extend(vec![
            r(REG_CR, 0x80),                // get_mode -> false
            w(REG_CR, 0x00), r(REG_CR, 0x00), // set_mode(true), get_mode -> true
            w(REG_CR, 0x80),                // set_mode(false)
            // store_wiper(Some(77)): CR <- 0, WR <- 77, poll, CR <- 0x80
            w(REG_CR, 0x00), w(REG_WR, 77), r(REG_CR, 0x00), w(REG_CR, 0x80),
            // store_wiper(None): reads WR first
            r(REG_WR, 33), w(REG_CR, 0x00), w(REG_WR, 33), r(REG_CR, 0x00), w(REG_CR, 0x80),
        ]);
        let (mut i2c, mut chip) = full(&t, 1.0, 0.0);
        assert!(!chip.get_mode().unwrap());
        chip.set_mode(true).unwrap();
        assert!(chip.get_mode().unwrap());
        chip.set_mode(false).unwrap();
        chip.store_wiper(&mut NoopDelay::new(), Some(77)).unwrap();
        chip.store_wiper(&mut NoopDelay::new(), None).unwrap();
        i2c.done();
    }

    #[test]
    fn store_wiper_restores_mode_on_timeout() {
        let mut t = init_t(0x40);
        t.extend(vec![w(REG_CR, 0x00), w(REG_WR, 10)]);
        for _ in 0..=30 {
            t.push(r(REG_CR, 0).with_error(ErrorKind::Other)); // 31 NACKed polls: 30 ms timeout
        }
        t.push(w(REG_CR, 0x80)); // MODE = 1 is restored even though the wait failed
        let (mut i2c, mut chip) = full(&t, 1.0, 0.0);
        let res = chip.store_wiper(&mut NoopDelay::new(), Some(10));
        assert!(matches!(res, Err(Ds3502Error::Timeout)));
        i2c.done();
    }

    #[test]
    fn wait_ready_polls_until_ack_and_endpoints() {
        let mut t = init_t(0x40);
        t.extend(vec![
            r(REG_CR, 0).with_error(ErrorKind::Other),
            r(REG_CR, 0).with_error(ErrorKind::Other),
            r(REG_CR, 0x80),
            r(REG_WR, 127),                 // get_voltage after set_endpoints
        ]);
        let (mut i2c, mut chip) = full(&t, 1.0, 0.0);
        chip.wait_ready(&mut NoopDelay::new(), 30).unwrap();
        chip.set_endpoints(10.0, 0.0);
        assert!((chip.get_voltage().unwrap() - 10.0).abs() < 1e-4);
        i2c.done();
    }
}
