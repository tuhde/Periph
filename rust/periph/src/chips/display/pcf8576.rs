use embedded_hal::i2c::I2c;

const CMD_MODE_SET: u8 = 0x80;
const CMD_LOAD_DP: u8 = 0x20;
const CMD_DEV_SEL: u8 = 0x40;
const CMD_BANK: u8 = 0x60;
const CMD_BLINK: u8 = 0x70;

pub const SEG_7SEG: [u8; 11] = [
    0xED, 0x60, 0xA7, 0xE3, 0x6A, 0xCB, 0xCF, 0xE0, 0xEF, 0xEB, 0x00
];

pub struct Pcf8576Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    subaddress: u8,
}

impl<I2C: I2c> Pcf8576Minimal<I2C> {
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let subaddress = 0;
        write_cmd(&mut i2c, addr, &[CMD_DEV_SEL | 0x01 | (subaddress << 1)])?;
        write_cmd(&mut i2c, addr, &[0x88])?;
        write_cmd(&mut i2c, addr, &[CMD_LOAD_DP | 0x00])?;
        write_all(&mut i2c, addr, 40, &[0u8; 40])?;
        Ok(Self { i2c, addr, subaddress })
    }

    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        write_cmd(&mut self.i2c, self.addr, &[CMD_LOAD_DP | 0x00, 0x00])?;
        write_all(&mut self.i2c, self.addr, 40, &[0u8; 40])
    }

    pub fn write_raw(&mut self, address: u8, data: &[u8]) -> Result<(), I2C::Error> {
        if address >= 40 || (address as usize + data.len()) > 40 {
            return Ok(());
        }
        write_cmd(&mut self.i2c, self.addr, &[CMD_LOAD_DP | address])?;
        self.i2c.write(self.addr, data)
    }

    pub fn set_digit_7seg(&mut self, position: u8, segments: u8) -> Result<(), I2C::Error> {
        if position >= 20 {
            return Ok(());
        }
        let addr = position * 2;
        write_cmd(&mut self.i2c, self.addr, &[CMD_LOAD_DP | addr])?;
        self.i2c.write(self.addr, &[segments])
    }
}

pub const MUX_STATIC: u8 = 0;
pub const MUX_1_2: u8 = 1;
pub const MUX_1_3: u8 = 3;
pub const MUX_1_4: u8 = 2;

pub const BLINK_OFF: u8 = 0;
pub const BLINK_2HZ: u8 = 1;
pub const BLINK_1HZ: u8 = 2;
pub const BLINK_05HZ: u8 = 3;

pub struct Pcf8576Full<I2C> {
    inner: Pcf8576Minimal<I2C>,
}

impl<I2C: I2c> Pcf8576Full<I2C> {
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Pcf8576Minimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    pub fn clear(&mut self) -> Result<(), I2C::Error> {
        self.inner.clear()
    }

    pub fn write_raw(&mut self, address: u8, data: &[u8]) -> Result<(), I2C::Error> {
        self.inner.write_raw(address, data)
    }

    pub fn set_digit_7seg(&mut self, position: u8, segments: u8) -> Result<(), I2C::Error> {
        self.inner.set_digit_7seg(position, segments)
    }

    fn build_mode_set(backplanes: u8, bias: u8, enable: bool) -> u8 {
        let e = if enable { 0x08 } else { 0x00 };
        let b = if bias != 0 { 0x04 } else { 0x00 };
        let m = if backplanes == 4 { 0 }
            else if backplanes == 1 { 1 }
            else if backplanes == 2 { 2 }
            else { 3 };
        0x80 | e | b | m
    }

    pub fn enable(&mut self) -> Result<(), I2C::Error> {
        write_cmd(&mut self.inner.i2c, self.inner.addr, &[Self::build_mode_set(4, 0, true)])
    }

    pub fn disable(&mut self) -> Result<(), I2C::Error> {
        write_cmd(&mut self.inner.i2c, self.inner.addr, &[Self::build_mode_set(4, 0, false)])
    }

    pub fn set_mode(&mut self, backplanes: u8, bias: u8) -> Result<(), I2C::Error> {
        write_cmd(&mut self.inner.i2c, self.inner.addr, &[Self::build_mode_set(backplanes, bias, true)])
    }

    pub fn set_blink(&mut self, frequency: u8, alternate_bank: bool) -> Result<(), I2C::Error> {
        let ab = if alternate_bank { 0x04 } else { 0x00 };
        write_cmd(&mut self.inner.i2c, self.inner.addr, &[CMD_BLINK | ab | (frequency & 0x03)])
    }

    pub fn set_bank(&mut self, input_bank: u8, output_bank: u8) -> Result<(), I2C::Error> {
        let i = if input_bank != 0 { 0x02 } else { 0x00 };
        let o = if output_bank != 0 { 0x01 } else { 0x00 };
        write_cmd(&mut self.inner.i2c, self.inner.addr, &[CMD_BANK | i | o])
    }

    pub fn device_select(&mut self, subaddress: u8) -> Result<(), I2C::Error> {
        if subaddress > 7 {
            return Ok(());
        }
        self.inner.subaddress = subaddress;
        write_cmd(&mut self.inner.i2c, self.inner.addr, &[CMD_DEV_SEL | 0x01 | (subaddress << 1)])
    }
}

fn write_cmd<I2C: I2c>(i2c: &mut I2C, addr: u8, data: &[u8]) -> Result<(), I2C::Error> {
    i2c.write(addr, data)
}

fn write_all<I2C: I2c>(i2c: &mut I2C, addr: u8, len: usize, data: &[u8]) -> Result<(), I2C::Error> {
    i2c.write(addr, data)
}