use embedded_hal::i2c::I2c;

const REG_CONFIG: u8 = 0x00;
const REG_SHUNT: u8 = 0x01;
const REG_BUS: u8 = 0x02;
const REG_POWER: u8 = 0x03;
const REG_CURRENT: u8 = 0x04;
const REG_CAL: u8 = 0x05;
const REG_MASK: u8 = 0x06;
const REG_ALERT: u8 = 0x07;
const REG_MFR_ID: u8 = 0xFE;
const REG_DIE_ID: u8 = 0xFF;

const CONFIG_DEFAULT: u16 = 0x4127;

pub struct Ina226Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    current_lsb: f32,
    cal: u16,
}

impl<I2C: I2c> Ina226Minimal<I2C> {
    pub fn new(mut i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let current_lsb = max_current / 32768.0;
        let cal = (0.00512 / (current_lsb * r_shunt)) as u16;
        write_reg(&mut i2c, addr, REG_CONFIG, CONFIG_DEFAULT)?;
        write_reg(&mut i2c, addr, REG_CAL, cal)?;
        Ok(Self { i2c, addr, current_lsb, cal })
    }

    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_BUS)? as f32 * 1.25e-3)
    }

    pub fn shunt_voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_SHUNT)? as f32 * 2.5e-6)
    }

    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg_signed(&mut self.i2c, self.addr, REG_CURRENT)? as f32 * self.current_lsb)
    }

    pub fn power(&mut self) -> Result<f32, I2C::Error> {
        Ok(read_reg(&mut self.i2c, self.addr, REG_POWER)? as f32 * 25.0 * self.current_lsb)
    }
}

pub struct Ina226Full<I2C> {
    inner: Ina226Minimal<I2C>,
    mode: u8,
}

pub const SOL: u16 = 0x8000;
pub const SUL: u16 = 0x4000;
pub const BOL: u16 = 0x2000;
pub const BUL: u16 = 0x1000;
pub const POL: u16 = 0x0800;
pub const CNVR: u16 = 0x0400;
pub const AFF: u16 = 0x0010;

impl<I2C: I2c> Ina226Full<I2C> {
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> {
        let inner = Ina226Minimal::new(i2c, addr, r_shunt, max_current)?;
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

    pub fn configure(&mut self, avg: u8, vbus_ct: u8, vsh_ct: u8, mode: u8) -> Result<(), I2C::Error> {
        let config = ((avg as u16 & 0x07) << 9)
            | ((vbus_ct as u16 & 0x07) << 6)
            | ((vsh_ct as u16 & 0x07) << 3)
            | (mode as u16 & 0x07);
        self.mode = mode & 0x07;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)
    }

    pub fn conversion_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)? & 0x0008 != 0)
    }

    pub fn overflow(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)? & 0x0004 != 0)
    }

    pub fn set_alert(&mut self, function: u16, limit: f32, polarity: bool, latch: bool) -> Result<(), I2C::Error> {
        let raw: u16 = if function == SOL || function == SUL {
            (limit / 2.5e-6) as u16
        } else if function == BOL || function == BUL {
            (limit / 1.25e-3) as u16
        } else if function == POL {
            (limit / (25.0 * self.inner.current_lsb)) as u16
        } else {
            0
        };
        let mask = function | if polarity { 0x0002 } else { 0 } | if latch { 0x0001 } else { 0 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK, mask)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_ALERT, raw)
    }

    pub fn alert_flags(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK)
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

    pub fn manufacturer_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MFR_ID)
    }

    pub fn die_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_DIE_ID)
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
