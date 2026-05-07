use embedded_hal::i2c::I2c;

const FAST_WRITE: u8 = 0x00;
const WRITE_DAC: u8 = 0x40;
const WRITE_DAC_EEPROM: u8 = 0x60;
const GENERAL_CALL_ADDR: u8 = 0x00;
const GEN_CALL_RESET: u8 = 0x06;
const GEN_CALL_WAKEUP: u8 = 0x09;

pub struct Mcp4725Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Mcp4725Minimal<I2C> {
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    pub fn set_voltage(&mut self, fraction: f32) -> Result<(), I2C::Error> {
        let code = ((fraction.max(0.0).min(1.0)) * 4095.0) as u16;
        self.set_raw(code)
    }

    pub fn set_raw(&mut self, code: u16) -> Result<(), I2C::Error> {
        let code = code.min(4095);
        let buf = [(code >> 8) as u8, code as u8];
        self.i2c.write(self.addr, &buf)
    }
}

pub struct Mcp4725Full<I2C> {
    inner: Mcp4725Minimal<I2C>,
}

impl<I2C: I2c> Mcp4725Full<I2C> {
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Mcp4725Minimal::new(i2c, addr) }
    }

    pub fn set_voltage(&mut self, fraction: f32) -> Result<(), I2C::Error> {
        self.inner.set_voltage(fraction)
    }

    pub fn set_raw(&mut self, code: u16) -> Result<(), I2C::Error> {
        self.inner.set_raw(code)
    }

    pub fn set_voltage_eeprom(&mut self, fraction: f32) -> Result<(), I2C::Error> {
        let code = ((fraction.max(0.0).min(1.0)) * 4095.0) as u16;
        self.set_raw_eeprom(code)
    }

    pub fn set_raw_eeprom(&mut self, code: u16) -> Result<(), I2C::Error> {
        let code = code.min(4095);
        let buf = [WRITE_DAC_EEPROM | ((code >> 8) as u8), code as u8, 0x00];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    pub fn read(&mut self) -> Result<Mcp4725ReadResult, I2C::Error> {
        let mut buf = [0u8; 5];
        self.inner.i2c.write_read(self.inner.addr, &[0x00], &mut buf)?;

        let code = (((buf[1] as u16) << 8) | buf[2] as u16) >> 4;
        Ok(Mcp4725ReadResult {
            code,
            voltage_fraction: code as f32 / 4095.0,
            power_down: (buf[0] >> 2) & 0x03,
            eeprom_code: (((buf[3] & 0x0F) as u16) << 8) | buf[4] as u16,
            eeprom_power_down: (buf[3] >> 4) & 0x03,
            eeprom_ready: (buf[0] & 0x80) != 0,
            por: (buf[0] & 0x40) != 0,
        })
    }

    pub fn set_power_down(&mut self, mode: u8) -> Result<(), I2C::Error> {
        let mut raw = [0u8; 1];
        self.inner.i2c.write_read(self.inner.addr, &[0x00], &mut raw)?;
        let pd_bits = (mode & 0x03) << 4;
        let buf = [(raw[0] & 0x0F) | pd_bits, 0x00];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    pub fn wake_up(&mut self) -> Result<(), I2C::Error> {
        let buf = [GENERAL_CALL_ADDR, GEN_CALL_WAKEUP];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        let buf = [GENERAL_CALL_ADDR, GEN_CALL_RESET];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    pub fn is_eeprom_ready(&mut self) -> Result<bool, I2C::Error> {
        let mut raw = [0u8; 1];
        self.inner.i2c.write_read(self.inner.addr, &[0x00], &mut raw)?;
        Ok((raw[0] & 0x80) != 0)
    }
}

pub struct Mcp4725ReadResult {
    pub code: u16,
    pub voltage_fraction: f32,
    pub power_down: u8,
    pub eeprom_code: u16,
    pub eeprom_power_down: u8,
    pub eeprom_ready: bool,
    pub por: bool,
}