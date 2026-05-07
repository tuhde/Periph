use embedded_hal::i2c::I2c;

const REG_CONFIG: u8 = 0x00;
const REG_SHUNT: u8 = 0x01;
const REG_BUS: u8 = 0x02;
const REG_POWER: u8 = 0x03;
const REG_CURRENT: u8 = 0x04;
const REG_CAL: u8 = 0x05;

pub struct Ina219Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    current_lsb: f32,
    cal: u16,
}

impl<I2C: I2c> Ina219Minimal<I2C> {
    pub fn new(mut i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let current_lsb = max_current / 32768.0;
        let cal = ((0.04096 / (current_lsb * r_shunt)) as u16) & 0xFFFE;
        write_reg(&mut i2c, addr, REG_CAL, cal)?;
        Ok(Self { i2c, addr, current_lsb, cal })
    }

    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok((read_reg(&mut self.i2c, self.addr, REG_BUS)? >> 3) as f32 * 4e-3)
    }

    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_SHUNT)? as f32 * 10e-6)
    }

    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_CURRENT)? as f32 * self.current_lsb)
    }

    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_POWER)? as f32 * 20.0 * self.current_lsb)
    }
}

pub struct Ina219Full<I2C> {
    inner: Ina219Minimal<I2C>,
    mode: u8,
}

pub const PGA_1: u8 = 0x00;
pub const PGA_2: u8 = 0x01;
pub const PGA_4: u8 = 0x02;
pub const PGA_8: u8 = 0x03;

pub const BRNG_16V: u8 = 0x00;
pub const BRNG_32V: u8 = 0x01;

pub const ADC_9BIT: u8 = 0x00;
pub const ADC_10BIT: u8 = 0x01;
pub const ADC_11BIT: u8 = 0x02;
pub const ADC_12BIT: u8 = 0x03;
pub const ADC_AVG_2: u8 = 0x08;
pub const ADC_AVG_4: u8 = 0x09;
pub const ADC_AVG_8: u8 = 0x0A;
pub const ADC_AVG_16: u8 = 0x0B;
pub const ADC_AVG_32: u8 = 0x0C;
pub const ADC_AVG_64: u8 = 0x0D;
pub const ADC_AVG_128: u8 = 0x0E;

pub const MODE_POWERDOWN: u8 = 0x00;
pub const MODE_SHUNT_TRIG: u8 = 0x01;
pub const MODE_BUS_TRIG: u8 = 0x02;
pub const MODE_SHUNT_BUS_TRIG: u8 = 0x03;
pub const MODE_ADC_OFF: u8 = 0x04;
pub const MODE_SHUNT_CONT: u8 = 0x05;
pub const MODE_BUS_CONT: u8 = 0x06;
pub const MODE_SHUNT_BUS_CONT: u8 = 0x07;

impl<I2C: I2c> Ina219Full<I2C> {
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let inner = Ina219Minimal::new(i2c, addr, r_shunt, max_current)?;
        Ok(Self { inner, mode: 0x07 })
    }

    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.voltage()
    }

    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        self.inner.shunt_voltage()
    }

    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        self.inner.current()
    }

    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        self.inner.power()
    }

    pub fn configure(&mut self, brng: u8, pga: u8, badc: u8, sadc: u8, mode: u8) -> Result<(), I2C::Error> {
        let config = ((brng as u16 & 1) << 13)
            | ((pga as u16 & 3) << 11)
            | ((badc as u16 & 0xF) << 7)
            | ((sadc as u16 & 0xF) << 3)
            | (mode as u16 & 7);
        self.mode = mode & 7;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CAL, self.inner.cal)
    }

    pub fn conversion_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_BUS)? & 0x0002 != 0)
    }

    pub fn overflow(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_BUS)? & 0x0001 != 0)
    }

    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, 0x8000)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CAL, self.inner.cal)
    }

    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        self.mode = (config & 0x07) as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config & 0xFFF8)
    }

    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (config & 0xFFF8) | self.mode as u16)
    }

    pub fn trigger(&mut self) -> Result<(), I2C::Error> {
        let config = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (config & 0xFFF8) | self.mode as u16)
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
