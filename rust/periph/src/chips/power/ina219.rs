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
        Ok((read_reg(&mut self.i2c, self.addr, REG_BUS)? >> 3) as f32 * 4.0e-3)
    }

    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_SHUNT)? as f32 * 10.0e-6)
    }

    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_CURRENT)? as f32 * self.current_lsb)
    }

    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_POWER)? as f32 * 20.0 * self.current_lsb)
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
