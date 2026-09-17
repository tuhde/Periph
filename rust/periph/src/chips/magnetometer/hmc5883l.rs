//! HMC5883L — 3-axis anisotropic magnetoresistive magnetometer (Honeywell).
//!
//! Communicates over I²C at fixed address 0x1E. Reads magnetic field on all three axes
//! in continuous mode with sensible defaults baked in.

use embedded_hal::i2c::I2c;

const REG_CONFIG_A: u8 = 0x00;
const REG_CONFIG_B: u8 = 0x01;
const REG_MODE: u8 = 0x02;
const REG_DATA_X_MSB: u8 = 0x03;
const REG_STATUS: u8 = 0x09;
const REG_ID_A: u8 = 0x0A;
const REG_ID_B: u8 = 0x0B;
const REG_ID_C: u8 = 0x0C;

const GAIN_LSB_PER_GAUSS: [f32; 8] = [
    1370.0, // GN=0: ±0.88 Ga
    1090.0, // GN=1: ±1.3 Ga (default)
    820.0,  // GN=2: ±1.9 Ga
    660.0,  // GN=3: ±2.5 Ga
    440.0,  // GN=4: ±4.0 Ga
    390.0,  // GN=5: ±4.7 Ga
    330.0,  // GN=6: ±5.6 Ga
    230.0,  // GN=7: ±8.1 Ga
];

/// HMC5883L minimal driver — reads magnetic field on all three axes in continuous mode.
///
/// Default behaviour (baked into Minimal):
/// - Averaging: 8 samples (MA=11)
/// - ODR: 15 Hz (DO=100)
/// - Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss
/// - Mode: continuous measurement
pub struct Hmc5883lMinimal<I2C> {
    i2c: I2C,
    addr: u8,
    gain: u8,
    gain_lsb_per_gauss: f32,
}

impl<I2C: I2c> Hmc5883lMinimal<I2C> {
    /// Create a new `Hmc5883lMinimal` with default configuration.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit device address (fixed at `0x1E`).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut s = Self { i2c, addr, gain: 1, gain_lsb_per_gauss: GAIN_LSB_PER_GAUSS[1] };
        s.init_minimal()?;
        Ok(s)
    }

    fn init_minimal(&mut self) -> Result<(), I2C::Error> {
        write_reg8(&mut self.i2c, self.addr, REG_CONFIG_A, 0x70)?;
        write_reg8(&mut self.i2c, self.addr, REG_CONFIG_B, 0x20)?;
        write_reg8(&mut self.i2c, self.addr, REG_MODE, 0x00)?;
        Ok(())
    }

    fn read_reg8(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(buf[0])
    }

    fn read_reg16(&mut self, reg: u8) -> Result<i16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(((buf[0] as i16) << 8) | buf[1] as i16)
    }

    fn write_reg8(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[reg, value])
    }

    fn read_data_burst(&mut self) -> Result<(i16, i16, i16), I2C::Error> {
        let mut buf = [0u8; 6];
        self.i2c.write_read(self.addr, &[REG_DATA_X_MSB], &mut buf)?;
        let raw_x = ((buf[0] as i16) << 8) | buf[1] as i16;
        let raw_z = ((buf[2] as i16) << 8) | buf[3] as i16;
        let raw_y = ((buf[4] as i16) << 8) | buf[5] as i16;
        Ok((raw_x, raw_y, raw_z))
    }

    fn raw_to_tesla(&self, raw: i16) -> Option<f32> {
        if raw == -4096 {
            None
        } else {
            Some((raw as f32 / self.gain_lsb_per_gauss) * 1e-4)
        }
    }

    /// Read magnetic field on all three axes.
    ///
    /// Returns `(x, y, z)` magnetic field strength in Tesla.
    /// Returns `None` for any axis that overflows (raw == -4096).
    pub fn magnetic_field(&mut self) -> Result<(Option<f32>, Option<f32>, Option<f32>), I2C::Error> {
        let (raw_x, raw_y, raw_z) = self.read_data_burst()?;
        Ok((
            self.raw_to_tesla(raw_x),
            self.raw_to_tesla(raw_y),
            self.raw_to_tesla(raw_z),
        ))
    }
}

/// Error type for [`Hmc5883lFull`] configuration methods: wraps the underlying
/// bus error plus out-of-range argument validation.
#[derive(Debug)]
pub enum Hmc5883lError<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// An argument was outside its valid range.
    InvalidArgument,
}

impl<E> From<E> for Hmc5883lError<E> {
    fn from(e: E) -> Self {
        Hmc5883lError::Bus(e)
    }
}

/// HMC5883L full driver — extends [`Hmc5883lMinimal`] with complete chip functionality.
///
/// Adds configuration, single-shot mode, self-test, identification, and status access.
pub struct Hmc5883lFull<I2C> {
    inner: Hmc5883lMinimal<I2C>,
}

impl<I2C: I2c> Hmc5883lFull<I2C> {
    /// Create a new `Hmc5883lFull` with default configuration.
    ///
    /// Same arguments as [`Hmc5883lMinimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Hmc5883lMinimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Read magnetic field. Delegates to the inner [`Hmc5883lMinimal`].
    pub fn magnetic_field(&mut self) -> Result<(Option<f32>, Option<f32>, Option<f32>), I2C::Error> {
        self.inner.magnetic_field()
    }

    /// Write Configuration Registers A and B.
    ///
    /// # Arguments
    /// * `odr` — Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
    /// * `averaging` — Samples averaged per output. Valid: 1, 2, 4, 8.
    /// * `gain` — Gain index 0–7.
    pub fn configure(&mut self, odr: f32, averaging: u8, gain: u8) -> Result<(), Hmc5883lError<I2C::Error>> {
        let ma = match averaging {
            1 => 0b00,
            2 => 0b01,
            4 => 0b10,
            8 => 0b11,
            _ => return Err(Hmc5883lError::InvalidArgument),
        };

        let do_bits = if (odr - 0.75).abs() < 0.01 { 0b000 }
        else if (odr - 1.5).abs() < 0.01 { 0b001 }
        else if (odr - 3.0).abs() < 0.01 { 0b010 }
        else if (odr - 7.5).abs() < 0.01 { 0b011 }
        else if (odr - 15.0).abs() < 0.01 { 0b100 }
        else if (odr - 30.0).abs() < 0.01 { 0b101 }
        else if (odr - 75.0).abs() < 0.01 { 0b110 }
        else { return Err(Hmc5883lError::InvalidArgument) };

        if gain > 7 {
            return Err(Hmc5883lError::InvalidArgument);
        }

        let config_a = (ma << 5) | (do_bits << 2);
        self.inner.write_reg8(REG_CONFIG_A, config_a)?;

        let config_b = gain << 5;
        self.inner.write_reg8(REG_CONFIG_B, config_b)?;

        self.inner.gain = gain;
        self.inner.gain_lsb_per_gauss = GAIN_LSB_PER_GAUSS[gain as usize];
        Ok(())
    }

    /// Update the gain setting (GN bits in Config B).
    ///
    /// # Arguments
    /// * `gain` — Gain index 0–7.
    pub fn set_gain(&mut self, gain: u8) -> Result<(), Hmc5883lError<I2C::Error>> {
        if gain > 7 {
            return Err(Hmc5883lError::InvalidArgument);
        }
        self.inner.write_reg8(REG_CONFIG_B, gain << 5)?;
        self.inner.gain = gain;
        self.inner.gain_lsb_per_gauss = GAIN_LSB_PER_GAUSS[gain as usize];
        Ok(())
    }

    /// Set the operating mode.
    ///
    /// # Arguments
    /// * `mode` — `0` = continuous, `1` = single, `2` = idle.
    pub fn set_mode(&mut self, mode: u8) -> Result<(), Hmc5883lError<I2C::Error>> {
        if mode > 2 {
            return Err(Hmc5883lError::InvalidArgument);
        }
        self.inner.write_reg8(REG_MODE, mode)?;
        Ok(())
    }

    /// Check if new measurement data is ready.
    ///
    /// Returns `true` if RDY bit is set in Status Register.
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg8(REG_STATUS)? & 0x01 != 0)
    }

    /// Read the raw Status Register.
    ///
    /// Returns raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg8(REG_STATUS)
    }

    /// Take a single measurement in single-shot mode.
    ///
    /// Returns `(x, y, z)` magnetic field strength in Tesla.
    /// Returns `None` for any axis that overflows (raw == -4096).
    pub fn single_measurement(&mut self) -> Result<(Option<f32>, Option<f32>, Option<f32>), I2C::Error> {
        self.inner.write_reg8(REG_MODE, 0x01)?;
        // Caller should wait 6 ms before reading
        self.inner.magnetic_field()
    }

    /// Read the identification registers.
    ///
    /// Returns `(id_a, id_b, id_c)` — expected `(0x48, 0x34, 0x33)` = ASCII "H43".
    pub fn identify(&mut self) -> Result<(u8, u8, u8), I2C::Error> {
        let id_a = self.inner.read_reg8(REG_ID_A)?;
        let id_b = self.inner.read_reg8(REG_ID_B)?;
        let id_c = self.inner.read_reg8(REG_ID_C)?;
        Ok((id_a, id_b, id_c))
    }

    /// Run self-test with positive or negative bias.
    ///
    /// # Arguments
    /// * `positive` — `true` for positive bias (MS=01), `false` for negative bias (MS=10).
    ///
    /// Returns `(x, y, z)` magnetic field deflection in Tesla during self-test.
    /// Returns `None` for any axis that overflows.
    pub fn self_test(&mut self, positive: bool) -> Result<(Option<f32>, Option<f32>, Option<f32>), I2C::Error> {
        let config_a = self.inner.read_reg8(REG_CONFIG_A)?;
        let ms = if positive { 0b01 } else { 0b10 };
        self.inner.write_reg8(REG_CONFIG_A, (config_a & 0xFC) | ms)?;

        self.inner.write_reg8(REG_MODE, 0x01)?;
        // Caller should wait 6 ms
        let result = self.inner.magnetic_field()?;

        self.inner.write_reg8(REG_CONFIG_A, (config_a & 0xFC) | 0b00)?;
        Ok(result)
    }
}

fn write_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn write_reg16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, (value >> 8) as u8, (value & 0xFF) as u8])
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x1E;

    fn init_transactions() -> Vec<I2cTransaction> {
        vec![
            // init_minimal: write CONFIG_A=0x70, CONFIG_B=0x20, MODE=0x00
            I2cTransaction::write(ADDR, vec![REG_CONFIG_A, 0x70]),
            I2cTransaction::write(ADDR, vec![REG_CONFIG_B, 0x20]),
            I2cTransaction::write(ADDR, vec![REG_MODE, 0x00]),
        ]
    }

    #[test]
    fn minimal_magnetic_field() {
        let mut transactions = init_transactions();
        transactions.push(
            // magnetic_field: read 6 bytes from 0x03
            // X=0x0100 (256), Z=0x0200 (512), Y=0x0300 (768)
            I2cTransaction::write_read(ADDR, vec![REG_DATA_X_MSB], vec![0x01, 0x00, 0x02, 0x00, 0x03, 0x00]),
        );
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Hmc5883lMinimal::new(i2c, ADDR).expect("init");
        let (x, y, z) = sensor.magnetic_field().expect("read");

        // gain=1 (1090 LSb/Ga), 1 Ga = 1e-4 T
        // x = 256 / 1090 * 1e-4 = 23.486 µT
        // y = 768 / 1090 * 1e-4 = 70.459 µT
        // z = 512 / 1090 * 1e-4 = 46.972 µT
        assert!((x.unwrap() - 256.0 / 1090.0 * 1e-4).abs() < 1e-9);
        assert!((y.unwrap() - 768.0 / 1090.0 * 1e-4).abs() < 1e-9);
        assert!((z.unwrap() - 512.0 / 1090.0 * 1e-4).abs() < 1e-9);

        i2c.done();
    }

    #[test]
    fn full_identify() {
        let mut transactions = init_transactions();
        transactions.push(
            I2cTransaction::write_read(ADDR, vec![REG_ID_A], vec![0x48]),
        );
        transactions.push(
            I2cTransaction::write_read(ADDR, vec![REG_ID_B], vec![0x34]),
        );
        transactions.push(
            I2cTransaction::write_read(ADDR, vec![REG_ID_C], vec![0x33]),
        );
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Hmc5883lFull::new(i2c, ADDR).expect("init");
        let (a, b, c) = sensor.identify().expect("identify");
        assert_eq!(a, 0x48);
        assert_eq!(b, 0x34);
        assert_eq!(c, 0x33);

        i2c.done();
    }

    #[test]
    fn full_configure() {
        let mut transactions = init_transactions();
        transactions.push(
            I2cTransaction::write(ADDR, vec![REG_CONFIG_A, 0b11111000]), // MA=11(8), DO=100(15Hz)
        );
        transactions.push(
            I2cTransaction::write(ADDR, vec![REG_CONFIG_B, 0x20]), // GN=1
        );
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Hmc5883lFull::new(i2c, ADDR).expect("init");
        sensor.configure(15.0, 8, 1).expect("configure");

        i2c.done();
    }

    #[test]
    fn full_self_test() {
        let mut transactions = init_transactions();
        transactions.push(
            I2cTransaction::write_read(ADDR, vec![REG_CONFIG_A], vec![0x70]),
        );
        transactions.push(
            I2cTransaction::write(ADDR, vec![REG_CONFIG_A, 0x71]), // positive bias
        );
        transactions.push(
            I2cTransaction::write(ADDR, vec![REG_MODE, 0x01]),
        );
        // magnetic_field: X=0x0100, Z=0x0200, Y=0x0300
        transactions.push(
            I2cTransaction::write_read(ADDR, vec![REG_DATA_X_MSB], vec![0x01, 0x00, 0x02, 0x00, 0x03, 0x00]),
        );
        transactions.push(
            I2cTransaction::write(ADDR, vec![REG_CONFIG_A, 0x70]), // restore normal
        );
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Hmc5883lFull::new(i2c, ADDR).expect("init");
        let (x, y, z) = sensor.self_test(true).expect("self_test");

        assert!((x.unwrap() - 256.0 / 1090.0 * 1e-4).abs() < 1e-9);
        assert!((y.unwrap() - 768.0 / 1090.0 * 1e-4).abs() < 1e-9);
        assert!((z.unwrap() - 512.0 / 1090.0 * 1e-4).abs() < 1e-9);

        i2c.done();
    }
}