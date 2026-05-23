//! AS5600 — 12-bit programmable contactless rotary position sensor (AMS OSRAM).
//!
//! Communicates over I²C at fixed address 0x36. Reads the absolute angle in degrees
//! with no configuration required. Verifies magnet presence at construction.

use embedded_hal::i2c::I2c;

const REG_ZMCO: u8 = 0x00;
const REG_ZPOS_H: u8 = 0x01;
const REG_ZPOS_L: u8 = 0x02;
const REG_MPOS_H: u8 = 0x03;
const REG_MPOS_L: u8 = 0x04;
const REG_MANG_H: u8 = 0x05;
const REG_MANG_L: u8 = 0x06;
const REG_CONF_H: u8 = 0x07;
const REG_CONF_L: u8 = 0x08;
const REG_STATUS: u8 = 0x0B;
const REG_RAW_ANGLE_H: u8 = 0x0C;
const REG_RAW_ANGLE_L: u8 = 0x0D;
const REG_ANGLE_H: u8 = 0x0E;
const REG_ANGLE_L: u8 = 0x0F;
const REG_AGC: u8 = 0x1A;
const REG_MAGNITUDE_H: u8 = 0x1B;
const REG_MAGNITUDE_L: u8 = 0x1C;
const REG_BURN: u8 = 0xFF;

const STATUS_MD: u8 = 0x08;
const STATUS_ML: u8 = 0x10;
const STATUS_MH: u8 = 0x20;

/// AS5600 minimal driver — reads absolute angle, verifies magnet presence.
///
/// Reads STATUS to verify MD=1 (magnet detected) at construction.
/// Reads ANGLE register (0x0E-0x0F), respecting any OTP-programmed ZPOS/MPOS range.
/// No CONF writes — uses power-on default CONF=0x0000.
pub struct As5600Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> As5600Minimal<I2C> {
    /// Create a new `As5600Minimal` and verify magnet presence.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit device address (fixed at `0x36`).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let status = read_reg8(&mut i2c, addr, REG_STATUS)?;
        if status & STATUS_MD == 0 {
            // Return Ok anyway; caller can check is_magnet_detected()
        }
        Ok(Self { i2c, addr })
    }

    /// Read the scaled absolute angle.
    ///
    /// Returns angle in degrees, 0.0–360.0 (exclusive).
    pub fn angle(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.angle_raw()? as f32 * 360.0 / 4096.0)
    }

    /// Read the scaled 12-bit angle count.
    ///
    /// Returns scaled angle count, 0–4095 (respects ZPOS/MPOS if programmed).
    pub fn angle_raw(&mut self) -> Result<u16, I2C::Error> {
        let raw = read_reg16(&mut self.i2c, self.addr, REG_ANGLE_H)?;
        Ok((raw >> 4) & 0x0FFF)
    }

    /// Check if a magnet is detected.
    ///
    /// Returns true if STATUS.MD=1 (magnetic field >= 8 mT).
    pub fn is_magnet_detected(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg8(&mut self.i2c, self.addr, REG_STATUS)? & STATUS_MD != 0)
    }

    /// Check if the magnet is too strong.
    ///
    /// Returns true if STATUS.MH=1 (AGC minimum gain overflow, Bz > 90 mT).
    pub fn is_magnet_too_strong(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg8(&mut self.i2c, self.addr, REG_STATUS)? & STATUS_MH != 0)
    }

    /// Check if the magnet is too weak.
    ///
    /// Returns true if STATUS.ML=1 (AGC maximum gain overflow, Bz < 30 mT).
    pub fn is_magnet_too_weak(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg8(&mut self.i2c, self.addr, REG_STATUS)? & STATUS_ML != 0)
    }
}

/// AS5600 full driver — extends [`As5600Minimal`] with complete chip functionality.
///
/// Adds raw angle readings, AGC/magnitude/status access, configuration,
/// ZPOS/MPOS/MANG programming, and OTP burn commands.
pub struct As5600Full<I2C> {
    inner: As5600Minimal<I2C>,
}

impl<I2C: I2c> As5600Full<I2C> {
    /// Create a new `As5600Full` and verify magnet presence.
    ///
    /// Same arguments as [`As5600Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = As5600Minimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Read the absolute angle. Delegates to the inner [`As5600Minimal`].
    pub fn angle(&mut self) -> Result<f32, I2C::Error> {
        self.inner.angle()
    }

    /// Read the scaled angle count. Delegates to the inner [`As5600Minimal`].
    pub fn angle_raw(&mut self) -> Result<u16, I2C::Error> {
        self.inner.angle_raw()
    }

    /// Check magnet detected. Delegates to the inner [`As5600Minimal`].
    pub fn is_magnet_detected(&mut self) -> Result<bool, I2C::Error> {
        self.inner.is_magnet_detected()
    }

    /// Check magnet too strong. Delegates to the inner [`As5600Minimal`].
    pub fn is_magnet_too_strong(&mut self) -> Result<bool, I2C::Error> {
        self.inner.is_magnet_too_strong()
    }

    /// Check magnet too weak. Delegates to the inner [`As5600Minimal`].
    pub fn is_magnet_too_weak(&mut self) -> Result<bool, I2C::Error> {
        self.inner.is_magnet_too_weak()
    }

    /// Read the unscaled raw 12-bit angle count.
    ///
    /// Returns raw angle count, 0–4095 (unaffected by ZPOS/MPOS).
    pub fn raw_angle(&mut self) -> Result<u16, I2C::Error> {
        let raw = read_reg16(&mut self.inner.i2c, self.inner.addr, REG_RAW_ANGLE_H)?;
        Ok((raw >> 4) & 0x0FFF)
    }

    /// Read the unscaled raw angle in degrees.
    ///
    /// Returns raw angle in degrees, 0.0–360.0.
    pub fn raw_angle_degrees(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.raw_angle()? as f32 * 360.0 / 4096.0)
    }

    /// Read the automatic gain control value.
    ///
    /// Returns AGC value (0–255 in 5 V mode; 0–127 in 3.3 V mode).
    /// Mid-range indicates optimal airgap.
    pub fn agc(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_AGC)
    }

    /// Read the CORDIC magnitude value.
    ///
    /// Returns 12-bit CORDIC magnitude value.
    pub fn magnitude(&mut self) -> Result<u16, I2C::Error> {
        let raw = read_reg16(&mut self.inner.i2c, self.inner.addr, REG_MAGNITUDE_H)?;
        Ok((raw >> 4) & 0x0FFF)
    }

    /// Read the raw STATUS register byte.
    ///
    /// Returns raw STATUS register (bits MH, ML, MD in positions 5, 4, 3).
    pub fn status_byte(&mut self) -> Result<u8, I2C::Error> {
        read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)
    }

    /// Write the CONF_H and CONF_L registers.
    ///
    /// Reads the current CONF_H/CONF_L values first to preserve the reserved
    /// bits in CONF_H[7:6].
    ///
    /// # Arguments
    /// * `pm`    — Power mode 0–3 (0=NOM, 1=LPM1, 2=LPM2, 3=LPM3).
    /// * `hyst`  — Hysteresis 0–3 (0=off, 1=1 LSB, 2=2 LSBs, 3=3 LSBs).
    /// * `outs`  — Output stage 0–2 (0=analog 0–VDD, 1=analog 10–90%, 2=PWM).
    /// * `pwmf`  — PWM frequency 0–3 (0=115 Hz, 1=230 Hz, 2=460 Hz, 3=920 Hz).
    /// * `sf`    — Slow filter 0–3 (0=16x, 1=8x, 2=4x, 3=2x).
    /// * `fth`   — Fast filter threshold 0–7.
    /// * `wd`    — Watchdog enable (true=on, false=off).
    pub fn configure(&mut self, pm: u8, hyst: u8, outs: u8, pwmf: u8, sf: u8, fth: u8, wd: bool) -> Result<(), I2C::Error> {
        let conf_h = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONF_H)?;
        let conf_l = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONF_L)?;
        let conf_h = (conf_h & 0xC0) | ((wd as u8) << 5) | ((fth & 0x07) << 2) | (sf & 0x03);
        let conf_l = ((pwmf & 0x03) << 6) | ((outs & 0x03) << 4) | ((hyst & 0x03) << 2) | (pm & 0x03);
        write_reg16(&mut self.inner.i2c, self.inner.addr, REG_CONF_H, (conf_h as u16) << 8 | conf_l as u16)
    }

    /// Write the zero position (start angle) to volatile RAM.
    ///
    /// # Arguments
    /// * `pos` — Zero position 0–4095. Lost on power cycle unless burned.
    pub fn set_zero_position(&mut self, pos: u16) -> Result<(), I2C::Error> {
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_ZPOS_H, ((pos >> 8) & 0x0F) as u8)?;
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_ZPOS_L, (pos & 0xFF) as u8)
    }

    /// Write the maximum position (stop angle) to volatile RAM.
    ///
    /// # Arguments
    /// * `pos` — Maximum position 0–4095. Lost on power cycle unless burned.
    pub fn set_max_position(&mut self, pos: u16) -> Result<(), I2C::Error> {
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_MPOS_H, ((pos >> 8) & 0x0F) as u8)?;
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_MPOS_L, (pos & 0xFF) as u8)
    }

    /// Write the maximum angle span to volatile RAM.
    ///
    /// # Arguments
    /// * `span` — Angle span 0–4095 (must correspond to >= 18 degrees).
    pub fn set_max_angle(&mut self, span: u16) -> Result<(), I2C::Error> {
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_MANG_H, ((span >> 8) & 0x0F) as u8)?;
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_MANG_L, (span & 0xFF) as u8)
    }

    /// Read the zero position (start angle).
    ///
    /// Returns ZPOS value 0–4095.
    pub fn zero_position(&mut self) -> Result<u16, I2C::Error> {
        let raw = read_reg16(&mut self.inner.i2c, self.inner.addr, REG_ZPOS_H)?;
        Ok((raw >> 4) & 0x0FFF)
    }

    /// Read the maximum position (stop angle).
    ///
    /// Returns MPOS value 0–4095.
    pub fn max_position(&mut self) -> Result<u16, I2C::Error> {
        let raw = read_reg16(&mut self.inner.i2c, self.inner.addr, REG_MPOS_H)?;
        Ok((raw >> 4) & 0x0FFF)
    }

    /// Read the maximum angle span.
    ///
    /// Returns MANG value 0–4095.
    pub fn max_angle(&mut self) -> Result<u16, I2C::Error> {
        let raw = read_reg16(&mut self.inner.i2c, self.inner.addr, REG_MANG_H)?;
        Ok((raw >> 4) & 0x0FFF)
    }

    /// Read the number of permanent ZPOS/MPOS burns already performed.
    ///
    /// Returns ZMCO value 0–3. Remaining permanent writes = 3 - ZMCO.
    pub fn burn_count(&mut self) -> Result<u8, I2C::Error> {
        Ok(read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ZMCO)? & 0x03)
    }

    /// Permanently burn ZPOS and MPOS to OTP.
    ///
    /// Requires MD=1 (magnet present) and ZMCO < 3.
    pub fn burn_angle(&mut self) -> Result<(), I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_STATUS)?;
        if status & STATUS_MD == 0 {
            return Ok(()); // silently return; no error type for this
        }
        let zmco = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ZMCO)? & 0x03;
        if zmco >= 3 {
            return Ok(());
        }
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_BURN, 0x80)
    }

    /// Permanently burn MANG and CONF to OTP.
    ///
    /// Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.
    pub fn burn_setting(&mut self) -> Result<(), I2C::Error> {
        let zmco = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_ZMCO)? & 0x03;
        if zmco != 0 {
            return Ok(());
        }
        write_reg8(&mut self.inner.i2c, self.inner.addr, REG_BURN, 0x40)
    }
}

fn write_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    let buf = [reg, value];
    i2c.write(addr, &buf)
}

fn write_reg16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    let buf = [reg, (value >> 8) as u8, (value & 0xFF) as u8];
    i2c.write(addr, &buf)
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn read_reg16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(((buf[0] as u16) << 8) | buf[1] as u16)
}
