//! INA219 — 26 V, 12-bit current/voltage/power monitor (Texas Instruments).
//!
//! Communicates over I²C (up to 400 kHz fast mode, 2.56 MHz HS mode).
//! Current and power readings require the Calibration Register to be programmed;
//! both [`Ina219Minimal::new`] and [`Ina219Full::new`] do this automatically.

use embedded_hal::i2c::I2c;

const REG_CONFIG: u8 = 0x00;
const REG_SHUNT: u8 = 0x01;
const REG_BUS: u8 = 0x02;
const REG_POWER: u8 = 0x03;
const REG_CURRENT: u8 = 0x04;
const REG_CAL: u8 = 0x05;

/// INA219 minimal driver — bus voltage, shunt voltage, current, and power.
///
/// Writes the Calibration Register automatically at construction. The chip's
/// power-on defaults are used (BRNG=1 / 32 V, PG=3 / ÷8, BADC=3 / 12-bit,
/// SADC=3 / 12-bit, MODE=7 / shunt+bus continuous).
pub struct Ina219Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    current_lsb: f32,
    cal: u16,
}

impl<I2C: I2c> Ina219Minimal<I2C> {
    /// Create a new `Ina219Minimal` and program the Calibration Register.
    ///
    /// # Arguments
    /// * `i2c`         — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`        — 7-bit device address (typically `0x40`–`0x4F`).
    /// * `r_shunt`     — Shunt resistor value in ohms.
    /// * `max_current` — Maximum expected current in amperes; determines the current LSB.
    pub fn new(mut i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let current_lsb = max_current / 32768.0;
        let cal = ((0.04096 / (current_lsb * r_shunt)) as u16) & 0xFFFE;
        write_reg(&mut i2c, addr, REG_CAL, cal)?;
        Ok(Self { i2c, addr, current_lsb, cal })
    }

    /// Read bus voltage.
    ///
    /// Returns voltage in volts. LSB = 4 mV (raw value right-shifted by 3).
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok((read_reg(&mut self.i2c, self.addr, REG_BUS)? >> 3) as f32 * 4e-3)
    }

    /// Read differential shunt voltage.
    ///
    /// Returns voltage in volts, signed. LSB = 10 µV.
    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_SHUNT)? as f32 * 10e-6)
    }

    /// Read calculated current through the shunt.
    ///
    /// Returns current in amperes, signed.
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_CURRENT)? as f32 * self.current_lsb)
    }

    /// Read calculated power.
    ///
    /// Returns power in watts. Formula: raw × 20 × current_LSB.
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_POWER)? as f32 * 20.0 * self.current_lsb)
    }
}

/// INA219 full driver — extends [`Ina219Minimal`] with configuration and power management.
///
/// Provides access to the Configuration Register, conversion-ready and overflow
/// flags, and power management (shutdown/wake/trigger).
pub struct Ina219Full<I2C> {
    inner: Ina219Minimal<I2C>,
    saved_mode: u8,
}

impl<I2C: I2c> Ina219Full<I2C> {
    /// Create a new `Ina219Full` and program the Calibration Register.
    ///
    /// Same arguments as [`Ina219Minimal::new`].
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let inner = Ina219Minimal::new(i2c, addr, r_shunt, max_current)?;
        Ok(Self { inner, saved_mode: 7 })
    }

    /// Read bus voltage. Delegates to the inner [`Ina219Minimal`].
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.voltage()
    }

    /// Read shunt voltage. Delegates to the inner [`Ina219Minimal`].
    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.shunt_voltage()
    }

    /// Read current. Delegates to the inner [`Ina219Minimal`].
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        self.inner.current()
    }

    /// Read power. Delegates to the inner [`Ina219Minimal`].
    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        self.inner.power()
    }

    /// Write the Configuration Register.
    ///
    /// # Arguments
    /// * `brng`  — Bus voltage range: 0 = 16 V FSR, 1 = 32 V FSR (default 1).
    /// * `pga`   — Shunt PGA gain: 0 = ÷1, 1 = ÷2, 2 = ÷4, 3 = ÷8 (default 3).
    /// * `badc`  — Bus ADC resolution/averaging: 0x00–0x0F (default 0x03 = 12-bit).
    /// * `sadc`  — Shunt ADC resolution/averaging: 0x00–0x0F (default 0x03 = 12-bit).
    /// * `mode`  — Operating mode 0–7 (default 7 = shunt+bus continuous).
    pub fn configure(&mut self, brng: u8, pga: u8, badc: u8, sadc: u8, mode: u8) -> Result<(), I2C::Error> {
        let config = ((brng as u16 & 1) << 13)
            | ((pga as u16 & 3) << 11)
            | ((badc as u16 & 0x0F) << 7)
            | ((sadc as u16 & 0x0F) << 3)
            | (mode as u16 & 7);
        self.saved_mode = mode & 7;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CAL, self.inner.cal)
    }

    /// Read the Conversion Ready Flag (CNVR) from the Bus Voltage register.
    pub fn conversion_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_BUS)? & 0x02 != 0)
    }

    /// Read the Math Overflow Flag (OVF) from the Bus Voltage register.
    pub fn overflow(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_BUS)? & 0x01 != 0)
    }

    /// Reset all registers to power-on defaults and re-write the Calibration Register.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, 0x8000)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CAL, self.inner.cal)
    }

    /// Enter power-down mode (MODE = 000), saving the current mode for [`wake`](Self::wake).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        self.saved_mode = (config & 0x07) as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config & 0xFFF8)
    }

    /// Restore the operating mode saved by [`shutdown`](Self::shutdown).
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (config & 0xFFF8) | self.saved_mode as u16)
    }

    /// Re-write the current mode to trigger a single-shot conversion.
    ///
    /// Only effective when the current mode is a triggered mode (1, 2, or 3).
    pub fn trigger(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)
    }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    let buf = [reg, (value >> 8) as u8, (value & 0xFF) as u8];
    i2c.write(addr, &buf)
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(((buf[0] as u16) << 8) | buf[1] as u16)
}

fn read_reg_signed<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<i16, I2C::Error> {
    Ok(read_reg(i2c, addr, reg)? as i16)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x40;
    // r_shunt=0.1, max_current=2.0 -> current_lsb=2.0/32768,
    // cal=(0.04096/(current_lsb*r_shunt)) as u16 & 0xFFFE = 0x1A36.
    const CAL: u16 = 0x1A36;

    #[test]
    fn full_api() {
        let transactions = vec![
            // new(): writes Calibration.
            I2cTransaction::write(ADDR, vec![REG_CAL, 0x1A, 0x36]),
            // voltage()/conversion_ready()/overflow(): Bus Voltage raw=(1000<<3)|0b010=0x1F42.
            I2cTransaction::write_read(ADDR, vec![REG_BUS], vec![0x1F, 0x42]),
            I2cTransaction::write_read(ADDR, vec![REG_BUS], vec![0x1F, 0x42]),
            I2cTransaction::write_read(ADDR, vec![REG_BUS], vec![0x1F, 0x42]),
            // overflow() again: raw=(1000<<3)|0b001=0x1F41 -> OVF=1.
            I2cTransaction::write_read(ADDR, vec![REG_BUS], vec![0x1F, 0x41]),
            // shunt_voltage(): raw=-500 (0xFE0C).
            I2cTransaction::write_read(ADDR, vec![REG_SHUNT], vec![0xFE, 0x0C]),
            // current(): raw=1000 (0x03E8).
            I2cTransaction::write_read(ADDR, vec![REG_CURRENT], vec![0x03, 0xE8]),
            // power(): raw=2000 (0x07D0).
            I2cTransaction::write_read(ADDR, vec![REG_POWER], vec![0x07, 0xD0]),
            // configure(0, 1, 0x0B, 0x02, 5) -> config=0x0D95; re-writes Calibration.
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x0D, 0x95]),
            I2cTransaction::write(ADDR, vec![REG_CAL, 0x1A, 0x36]),
            // shutdown(): reads CONFIG (0x0D95), writes MODE=0 -> 0x0D90.
            I2cTransaction::write_read(ADDR, vec![REG_CONFIG], vec![0x0D, 0x95]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x0D, 0x90]),
            // wake(): reads CONFIG (0x0D90), restores mode 5 -> 0x0D95.
            I2cTransaction::write_read(ADDR, vec![REG_CONFIG], vec![0x0D, 0x90]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x0D, 0x95]),
            // trigger(): reads CONFIG (0x0D95), re-writes unchanged.
            I2cTransaction::write_read(ADDR, vec![REG_CONFIG], vec![0x0D, 0x95]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x0D, 0x95]),
            // reset(): sets RST, re-writes Calibration (no config restore in this driver).
            I2cTransaction::write(ADDR, vec![REG_CONFIG, 0x80, 0x00]),
            I2cTransaction::write(ADDR, vec![REG_CAL, 0x1A, 0x36]),
        ];
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Ina219Full::new(i2c, ADDR, 0.1, 2.0).expect("init");
        assert_eq!(sensor.inner.cal, CAL);

        assert!((sensor.voltage().unwrap() - 4.0).abs() < 1e-6);
        assert!(sensor.conversion_ready().unwrap());
        assert!(!sensor.overflow().unwrap());

        assert!(sensor.overflow().unwrap());

        assert!((sensor.shunt_voltage().unwrap() - (-0.005)).abs() < 1e-6);

        let expected_current = 1000.0 * (2.0 / 32768.0);
        assert!((sensor.current().unwrap() - expected_current).abs() < 1e-6);

        let expected_power = 2000.0 * 20.0 * (2.0 / 32768.0);
        assert!((sensor.power().unwrap() - expected_power).abs() < 1e-6);

        sensor.configure(0, 1, 0x0B, 0x02, 5).unwrap();
        sensor.shutdown().unwrap();
        sensor.wake().unwrap();
        sensor.trigger().unwrap();
        sensor.reset().unwrap();

        sensor.inner.i2c.done();
    }
}