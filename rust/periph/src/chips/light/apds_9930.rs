//! APDS-9930 — digital ambient light and proximity sensor (Broadcom/Avago).
//!
//! Communicates over I²C (up to 400 kHz fast mode, address 0x39 fixed).
//! Uses a command-register protocol: every register access is prefixed by
//! a command byte (0x80|reg for write, 0xA0|reg for auto-increment 16-bit
//! read, 0xE0|function for special-function commands). The two-channel
//! ALS architecture (Ch0 visible + IR, Ch1 IR-only) compensates for the
//! IR component of ambient light, and the integrated 850 nm IR LED +
//! synchronous driver provide factory-calibrated proximity detection
//! to ~100 mm.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_ENABLE: u8 = 0x00;
const REG_ATIME: u8 = 0x01;
const REG_PTIME: u8 = 0x02;
const REG_WTIME: u8 = 0x03;
const REG_AILTL: u8 = 0x04;
const REG_AILTH: u8 = 0x05;
const REG_AIHTL: u8 = 0x06;
const REG_AIHTH: u8 = 0x07;
const REG_PILTL: u8 = 0x08;
const REG_PILTH: u8 = 0x09;
const REG_PIHTL: u8 = 0x0A;
const REG_PIHTH: u8 = 0x0B;
const REG_PERS: u8 = 0x0C;
const REG_CONFIG: u8 = 0x0D;
const REG_PPULSE: u8 = 0x0E;
const REG_CONTROL: u8 = 0x0F;
const REG_ID: u8 = 0x12;
const REG_STATUS: u8 = 0x13;
const REG_CH0DATAL: u8 = 0x14;
const REG_CH0DATAH: u8 = 0x15;
const REG_CH1DATAL: u8 = 0x16;
const REG_CH1DATAH: u8 = 0x17;
const REG_PDATAL: u8 = 0x18;
const REG_PDATAH: u8 = 0x19;
const REG_POFFSET: u8 = 0x1E;

const CFN_CLEAR_PROXIMITY: u8 = 0x05;
const CFN_CLEAR_ALS: u8 = 0x06;
const CFN_CLEAR_BOTH: u8 = 0x07;

const CMD_WRITE: u8 = 0x80;
const CMD_READ: u8 = 0xA0;
const CMD_SPECIAL: u8 = 0xE0;

const ATIME_DEFAULT: u8 = 0xDB;
const PTIME_DEFAULT: u8 = 0xFF;
const PPULSE_DEFAULT: u8 = 0x08;
const CONTROL_DEFAULT: u8 = 0x20;
const ENABLE_DEFAULT: u8 = 0x07;

const fn cmd_write(reg: u8) -> u8 { CMD_WRITE | (reg & 0x1F) }
const fn cmd_read(reg: u8) -> u8 { CMD_READ | (reg & 0x1F) }
const fn cmd_special(f: u8) -> u8 { CMD_SPECIAL | (f & 0x1F) }

fn again_factor(again_idx: u8, agl: bool) -> f32 {
    if !agl {
        match again_idx & 0x03 {
            0 => 1.0,
            1 => 8.0,
            2 => 16.0,
            _ => 120.0,
        }
    } else {
        match again_idx & 0x03 {
            0 => 1.0 / 6.0,
            1 => 8.0 / 6.0,
            2 => 16.0 / 6.0,
            _ => 20.0,
        }
    }
}

/// APDS-9930 minimal driver — illuminance (lux) and proximity readings.
///
/// Writes sensible defaults at construction: ATIME=0xDB (101 ms integration,
/// rejects 50/60 Hz fluorescent flicker), PTIME=0xFF, PPULSE=8 pulses at
/// 100 mA drive, CONTROL=0x20, ENABLE=0x07 (PON + AEN + PEN).
pub struct Apds9930Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Apds9930Minimal<I2C> {
    /// Create a new `Apds9930Minimal` and initialize the ALS and proximity engines.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (always `0x39`).
    /// * `delay` — Delay provider; used for the 6 ms power-up wait and 12 ms
    ///             first-conversion wait. The driver's [`chip_id`] method
    ///             can be used to verify the device identity (expects `0x39`).
    pub fn new(mut i2c: I2C, addr: u8, delay: &mut impl DelayNs) -> Result<Self, I2C::Error> {
        delay.delay_ms(6);
        write_reg(&mut i2c, addr, REG_ENABLE, 0x00)?;
        write_reg(&mut i2c, addr, REG_ATIME, ATIME_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_PTIME, PTIME_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_PPULSE, PPULSE_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_CONTROL, CONTROL_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_ENABLE, ENABLE_DEFAULT)?;
        delay.delay_ms(12);
        Ok(Self { i2c, addr })
    }

    /// Read the device ID register.
    ///
    /// Returns the device ID (`0x39` for APDS-9930).
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        read_reg(&mut self.i2c, self.addr, REG_ID)
    }

    /// Read the ambient illuminance.
    ///
    /// Uses Ch0 (visible + IR) and Ch1 (IR-only) to compensate for the
    /// IR component of ambient light, then applies the open-air lux
    /// coefficients from the datasheet.
    ///
    /// Returns illuminance in lux.
    pub fn lux(&mut self) -> Result<f32, I2C::Error> {
        let ch0 = read_reg16(&mut self.i2c, self.addr, REG_CH0DATAL)?;
        let ch1 = read_reg16(&mut self.i2c, self.addr, REG_CH1DATAL)?;
        let ctrl = read_reg(&mut self.i2c, self.addr, REG_CONTROL)?;
        let cfg = read_reg(&mut self.i2c, self.addr, REG_CONFIG)?;
        let atime = read_reg(&mut self.i2c, self.addr, REG_ATIME)?;
        let alsit_ms = 2.73f32 * (256.0 - atime as f32);
        let again_x = again_factor(ctrl & 0x03, (cfg & 0x04) != 0);
        let iac1 = (ch0 as f32) - 1.862 * (ch1 as f32);
        let iac2 = 0.746 * (ch0 as f32) - 1.291 * (ch1 as f32);
        let mut iac = iac1;
        if iac2 > iac { iac = iac2; }
        if iac < 0.0 { iac = 0.0; }
        let lpc = (0.49f32 * 52.0) / (alsit_ms * again_x);
        Ok(iac * lpc)
    }

    /// Read the proximity ADC count.
    ///
    /// Higher counts mean a closer object. Realistically limited to
    /// 10 bits (0-1023) at the default PTIME=0xFF (one ADC cycle).
    ///
    /// Returns the raw 16-bit proximity count.
    pub fn proximity(&mut self) -> Result<u16, I2C::Error> {
        read_reg16(&mut self.i2c, self.addr, REG_PDATAL)
    }
}

/// APDS-9930 full driver — extends [`Apds9930Minimal`] with ALS/proximity
/// configuration, raw channel reads, interrupt thresholds with persistence,
/// status decoding, sleep-after-interrupt, and proximity offset compensation.
pub struct Apds9930Full<I2C> {
    inner: Apds9930Minimal<I2C>,
}

impl<I2C: I2c> Apds9930Full<I2C> {
    /// Create a new `Apds9930Full` and initialize the ALS and proximity engines.
    ///
    /// Same arguments as [`Apds9930Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, delay: &mut impl DelayNs) -> Result<Self, I2C::Error> {
        let inner = Apds9930Minimal::new(i2c, addr, delay)?;
        Ok(Self { inner })
    }

    /// Read the device ID register. Delegates to [`Apds9930Minimal`].
    pub fn chip_id(&mut self) -> Result<u8, I2C::Error> {
        self.inner.chip_id()
    }

    /// Read the illuminance. Delegates to [`Apds9930Minimal`].
    pub fn lux(&mut self) -> Result<f32, I2C::Error> {
        self.inner.lux()
    }

    /// Read the proximity count. Delegates to [`Apds9930Minimal`].
    pub fn proximity(&mut self) -> Result<u16, I2C::Error> {
        self.inner.proximity()
    }

    /// Configure ALS integration time, AGAIN index, and AGL flag.
    ///
    /// # Arguments
    /// * `atime` — ATIME register value 0-255.
    /// * `again` — ALS gain index 0-3 (0=1x, 1=8x, 2=16x, 3=120x).
    /// * `agl`   — `true` to enable the AGL divide-by-6 gain-level bit.
    pub fn configure_als(&mut self, atime: u8, again: u8, agl: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ATIME, atime)?;
        let mut ctrl = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl = (ctrl & 0xFC) | (again & 0x03);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)?;
        let mut cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        if agl { cfg |= 0x04; } else { cfg &= !0x04; }
        cfg &= !0x06;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg)
    }

    /// Configure proximity LED pulses, gain, drive, and ADC integration time.
    ///
    /// # Arguments
    /// * `ppulse` — Number of LED pulses 1-255.
    /// * `pgain`  — Proximity gain index 0-3 (0=1x, 1=2x, 2=4x, 3=8x).
    /// * `pdrive` — LED drive current index 0-3 (0=100 mA, 1=50 mA, 2=25 mA, 3=12.5 mA).
    /// * `pdl`    — `true` to enable PDL (reduces drive to 1/9 of PDRIVE).
    /// * `ptime`  — PTIME register value 0-255.
    pub fn configure_proximity(
        &mut self, ppulse: u8, pgain: u8, pdrive: u8, pdl: bool, ptime: u8,
    ) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PPULSE, ppulse)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PTIME, ptime)?;
        let mut ctrl = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl = (ctrl & 0x03)
             | ((pdrive & 0x03) << 6)
             | 0x20
             | ((pgain & 0x03) << 2);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)?;
        let mut cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        if pdl { cfg |= 0x01; } else { cfg &= !0x01; }
        cfg &= !0x06;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg)
    }

    /// Configure wait time and enable the wait timer.
    ///
    /// # Arguments
    /// * `wtime` — WTIME register value 0-255.
    /// * `wlong` — `true` to enable WLONG (multiplies wait by 12x).
    pub fn configure_wait(&mut self, wtime: u8, wlong: bool) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_WTIME, wtime)?;
        let mut cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        if wlong { cfg |= 0x02; } else { cfg &= !0x02; }
        cfg &= !0x04;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg)?;
        let en = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)? | 0x08;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, en)
    }

    /// Clear WEN in ENABLE (disable the wait timer).
    pub fn disable_wait(&mut self) -> Result<(), I2C::Error> {
        let en = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)? & !0x08;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, en)
    }

    /// Read the raw Ch0 (visible + IR) ADC count.
    pub fn ch0(&mut self) -> Result<u16, I2C::Error> {
        read_reg16(&mut self.inner.i2c, self.inner.addr, REG_CH0DATAL)
    }

    /// Read the raw Ch1 (IR-only) ADC count.
    pub fn ch1(&mut self) -> Result<u16, I2C::Error> {
        read_reg16(&mut self.inner.i2c, self.inner.addr, REG_CH1DATAL)
    }

    /// Read the STATUS register decoded into named fields.
    pub fn status(&mut self) -> Result<Status, I2C::Error> {
        let s = read_reg(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        Ok(Status {
            avalid: (s & 0x01) != 0,
            pvalid: (s & 0x02) != 0,
            psat:   (s & 0x40) != 0,
            aint:   (s & 0x10) != 0,
            pint:   (s & 0x20) != 0,
        })
    }

    /// Set ALS interrupt thresholds and enable AIEN.
    ///
    /// Thresholds are evaluated against raw Ch0 counts, not lux.
    pub fn set_als_thresholds(&mut self, low: u16, high: u16, persistence: u8) -> Result<(), I2C::Error> {
        let high = if low > high { low } else { high };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AILTL, (low & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AILTH, ((low >> 8) & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AIHTL, (high & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AIHTH, ((high >> 8) & 0xFF) as u8)?;
        let pers = (read_reg(&mut self.inner.i2c, self.inner.addr, REG_PERS)? & 0xF0) | (persistence & 0x0F);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PERS, pers)?;
        let en = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)? | 0x10;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, en)
    }

    /// Set proximity interrupt thresholds and enable PIEN.
    pub fn set_proximity_thresholds(&mut self, low: u16, high: u16, persistence: u8) -> Result<(), I2C::Error> {
        let high = if low > high { low } else { high };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PILTL, (low & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PILTH, ((low >> 8) & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PIHTL, (high & 0xFF) as u8)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PIHTH, ((high >> 8) & 0xFF) as u8)?;
        let pers = (read_reg(&mut self.inner.i2c, self.inner.addr, REG_PERS)? & 0x0F) | ((persistence & 0x0F) << 4);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PERS, pers)?;
        let en = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)? | 0x20;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, en)
    }

    /// Clear pending interrupt(s).
    ///
    /// # Arguments
    /// * `channel` — 0=both, 1=ALS, 2=proximity.
    pub fn clear_interrupt(&mut self, channel: u8) -> Result<(), I2C::Error> {
        let f = match channel {
            1 => CFN_CLEAR_ALS,
            2 => CFN_CLEAR_PROXIMITY,
            _ => CFN_CLEAR_BOTH,
        };
        self.inner.i2c.write(self.inner.addr, &[cmd_special(f)])
    }

    /// Set the proximity offset (sign-magnitude).
    ///
    /// # Arguments
    /// * `offset` — Signed integer -127..+127 (positive shifts data up).
    pub fn set_proximity_offset(&mut self, offset: i8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_POFFSET, encode_offset(offset))
    }

    /// Enable or disable SAI (sleep after interrupt).
    pub fn sleep_after_interrupt(&mut self, enable: bool) -> Result<(), I2C::Error> {
        let en = read_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE)?;
        let en = if enable { en | 0x40 } else { en & !0x40 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ENABLE, en)
    }
}

/// STATUS register decoded fields.
pub struct Status {
    pub avalid: bool,
    pub pvalid: bool,
    pub psat: bool,
    pub aint: bool,
    pub pint: bool,
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[cmd_write(reg), value])
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[cmd_read(reg)], &mut buf)?;
    Ok(buf[0])
}

fn read_reg16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[cmd_read(reg)], &mut buf)?;
    Ok(((buf[0] as u16) << 8) | (buf[1] as u16))
}

fn encode_offset(value: i8) -> u8 {
    if value >= 0 {
        0x80 | (value as u8 & 0x7F)
    } else {
        (-value) as u8 & 0x7F
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x39;

    #[test]
    fn full_api() {
        let mut delay = NoopDelay::new();
        let mut transactions: Vec<I2cTransaction> = vec![
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x00]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ATIME), ATIME_DEFAULT]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PTIME), PTIME_DEFAULT]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PPULSE), PPULSE_DEFAULT]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_CONTROL), CONTROL_DEFAULT]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), ENABLE_DEFAULT]),
            // chip_id()
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ID)], vec![0x39]),
            // lux(): Ch0=0x1000 (BE), Ch1=0x0000, CONTROL=0x20, CONFIG=0x00, ATIME=0xDB
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CH0DATAL)], vec![0x10, 0x00]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CH1DATAL)], vec![0x00, 0x00]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONTROL)], vec![0x20]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONFIG)], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ATIME)], vec![0xDB]),
            // proximity(): PDATA=0x1234 (BE)
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_PDATAL)], vec![0x12, 0x34]),
            // configure_als(0xF6, 2, false): writes ATIME, reads CONTROL 0x20, writes CONTROL 0x22
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ATIME), 0xF6]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONTROL)], vec![0x20]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_CONTROL), 0x22]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONFIG)], vec![0x00]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_CONFIG), 0x00]),
            // configure_proximity(8, 1, 2, false, 0xFF): PPULSE, PTIME, CONTROL 0x22->0xA4, CONFIG 0x00->0x00
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PPULSE), 8]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PTIME), 0xFF]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONTROL)], vec![0x22]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_CONTROL), 0xA4]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONFIG)], vec![0x00]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_CONFIG), 0x00]),
            // configure_wait(0x80, true): WTIME, CONFIG 0x00->0x02, ENABLE 0x07->0x0F
            I2cTransaction::write(ADDR, vec![cmd_write(REG_WTIME), 0x80]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CONFIG)], vec![0x00]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_CONFIG), 0x02]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ENABLE)], vec![0x07]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x0F]),
            // disable_wait(): ENABLE 0x0F->0x07
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ENABLE)], vec![0x0F]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x07]),
            // ch0(): Ch0=0x0001
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CH0DATAL)], vec![0x00, 0x01]),
            // ch1(): Ch1=0x0002
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_CH1DATAL)], vec![0x00, 0x02]),
            // status(): STATUS=0x01 (AVALID)
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_STATUS)], vec![0x01]),
            // set_als_thresholds(100, 60000, 1): writes 4 threshold bytes, PERS 0x00->0x01, ENABLE 0x07->0x17
            I2cTransaction::write(ADDR, vec![cmd_write(REG_AILTL), 100]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_AILTH), 0]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_AIHTL), 0x60]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_AIHTH), 0xEA]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_PERS)], vec![0x00]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PERS), 0x01]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ENABLE)], vec![0x07]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x17]),
            // set_proximity_thresholds(10, 200, 1): writes 4 threshold bytes, PERS 0x01->0x11, ENABLE 0x17->0x37
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PILTL), 10]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PILTH), 0]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PIHTL), 200]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PIHTH), 0]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_PERS)], vec![0x01]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_PERS), 0x11]),
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ENABLE)], vec![0x17]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x37]),
            // clear_interrupt(0): special-function 0xE7
            I2cTransaction::write(ADDR, vec![cmd_special(CFN_CLEAR_BOTH)]),
            // set_proximity_offset(-50): sign-magnitude -> 0x32
            I2cTransaction::write(ADDR, vec![cmd_write(REG_POFFSET), 0x32]),
            // sleep_after_interrupt(true): ENABLE 0x37->0x77
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ENABLE)], vec![0x37]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x77]),
            // sleep_after_interrupt(false): ENABLE 0x77->0x37
            I2cTransaction::write_read(ADDR, vec![cmd_read(REG_ENABLE)], vec![0x77]),
            I2cTransaction::write(ADDR, vec![cmd_write(REG_ENABLE), 0x37]),
        ];
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Apds9930Full::new(i2c, ADDR, &mut delay).expect("init");

        assert_eq!(sensor.chip_id().unwrap(), 0x39);
        assert!(sensor.lux().unwrap() > 0.0);
        assert_eq!(sensor.proximity().unwrap(), 0x1234);

        sensor.configure_als(0xF6, 2, false).unwrap();
        sensor.configure_proximity(8, 1, 2, false, 0xFF).unwrap();
        sensor.configure_wait(0x80, true).unwrap();
        sensor.disable_wait().unwrap();
        assert_eq!(sensor.ch0().unwrap(), 0x0001);
        assert_eq!(sensor.ch1().unwrap(), 0x0002);

        let st = sensor.status().unwrap();
        assert!(st.avalid);
        assert!(!st.pvalid);

        sensor.set_als_thresholds(100, 60000, 1).unwrap();
        sensor.set_proximity_thresholds(10, 200, 1).unwrap();
        sensor.clear_interrupt(0).unwrap();
        sensor.set_proximity_offset(-50).unwrap();
        sensor.sleep_after_interrupt(true).unwrap();
        sensor.sleep_after_interrupt(false).unwrap();

        sensor.inner.i2c.done();
    }
}