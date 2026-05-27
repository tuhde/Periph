//! ENS160 digital multi-gas sensor driver.
//!
//! Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
//! configuration required beyond the transport. The sensor performs automatic
//! baseline correction and on-chip signal processing.
//!
//! Default: STANDARD mode (gas sensing active), polling only, no external
//! T/RH compensation.

use embedded_hal::i2c::I2c;

const REG_PART_ID: u8 = 0x00;
const REG_OPMODE: u8 = 0x10;
const REG_CONFIG: u8 = 0x11;
const REG_COMMAND: u8 = 0x12;
const REG_TEMP_IN: u8 = 0x13;
const REG_RH_IN: u8 = 0x15;
const REG_DEVICE_STATUS: u8 = 0x20;
const REG_DATA_AQI: u8 = 0x21;
const REG_DATA_TVOC: u8 = 0x22;
const REG_DATA_ECO2: u8 = 0x24;
const REG_DATA_T: u8 = 0x30;
const REG_DATA_RH: u8 = 0x32;
const REG_GPR_READ: u8 = 0x48;

const OPMODE_DEEP_SLEEP: u8 = 0x00;
const OPMODE_IDLE: u8 = 0x01;
const OPMODE_STANDARD: u8 = 0x02;

const PART_ID_EXPECTED: u16 = 0x0160;

/// Validity flag: OK.
pub const VALIDITY_OK: u8 = 0;
/// Validity flag: Warm-up.
pub const VALIDITY_WARMUP: u8 = 1;
/// Validity flag: Initial Start-up.
pub const VALIDITY_INITIAL_STARTUP: u8 = 2;
/// Validity flag: Invalid.
pub const VALIDITY_INVALID: u8 = 3;

fn delay_ms(ms: u32) {
    #[cfg(feature = "std")]
    std::thread::sleep(std::time::Duration::from_millis(ms as u64));
    #[cfg(not(feature = "std"))]
    let _ = ms;
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn write_reg_le16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, (value & 0xFF) as u8, ((value >> 8) & 0xFF) as u8])
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

fn read_reg_le16<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    read_reg(i2c, addr, reg, &mut buf)?;
    Ok(u16::from_le_bytes(buf))
}

fn read_device_status<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    read_reg(i2c, addr, REG_DEVICE_STATUS, &mut buf)?;
    Ok(buf[0])
}

fn wait_for_new_data<I2C: I2c>(i2c: &mut I2C, addr: u8, timeout_ms: u32) -> Result<u8, I2C::Error> {
    let mut elapsed = 0;
    loop {
        let status = read_device_status(i2c, addr)?;
        if status & 0x02 != 0 {
            return Ok(status);
        }
        if elapsed >= timeout_ms {
            panic!("ENS160: NEWDAT not set within {} ms", timeout_ms);
        }
        delay_ms(10);
        elapsed += 10;
    }
}

/// ENS160 minimal driver — air quality (AQI, TVOC, eCO2).
///
/// Default: STANDARD mode (gas sensing active), polling only, no external
/// T/RH compensation.
pub struct Ens160Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Ens160Minimal<I2C> {
    /// Create a new `Ens160Minimal` and verify PART_ID.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x52 or 0x53).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        write_reg(&mut i2c, addr, REG_OPMODE, OPMODE_IDLE)?;
        delay_ms(1);
        let part_id = read_reg_le16(&mut i2c, addr, REG_PART_ID)?;
        if part_id != PART_ID_EXPECTED {
            panic!("ENS160 not found: expected PART_ID 0x0160, got 0x{:04X}", part_id);
        }
        write_reg(&mut i2c, addr, REG_OPMODE, OPMODE_STANDARD)?;
        Ok(Self { i2c, addr })
    }

    /// Read the VALIDITY_FLAG from DEVICE_STATUS.
    ///
    /// Returns validity flag (0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output).
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let status = read_device_status(&mut self.i2c, self.addr)?;
        Ok((status >> 2) & 0x03)
    }

    /// Read calibrated air quality values.
    ///
    /// Polls until NEWDAT is set, then checks VALIDITY_FLAG. Only returns
    /// data when validity is 0 (OK). Reads AQI, TVOC, and eCO2 in a single
    /// burst to ensure consistency.
    ///
    /// Returns (aqi, tvoc_ppb, eco2_ppm).
    pub fn read_air_quality(&mut self) -> Result<(u8, f32, f32), I2C::Error> {
        let status = wait_for_new_data(&mut self.i2c, self.addr, 5000)?;
        let validity = (status >> 2) & 0x03;
        if validity != 0 {
            panic!("ENS160: data not valid (VALIDITY_FLAG={})", validity);
        }
        let mut data = [0u8; 5];
        read_reg(&mut self.i2c, self.addr, REG_DATA_AQI, &mut data)?;
        let aqi = data[0] & 0x07;
        let tvoc_ppb = u16::from_le_bytes([data[1], data[2]]) as f32;
        let eco2_ppm = u16::from_le_bytes([data[3], data[4]]) as f32;
        Ok((aqi, tvoc_ppb, eco2_ppm))
    }
}

/// ENS160 full driver — extends minimal with compensation, raw readings, and power control.
pub struct Ens160Full<I2C> {
    inner: Ens160Minimal<I2C>,
}

impl<I2C: I2c> Ens160Full<I2C> {
    /// Create a new `Ens160Full`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus.
    /// * `addr` — 7-bit I²C address (0x52 or 0x53).
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let inner = Ens160Minimal::new(i2c, addr)?;
        Ok(Self { inner })
    }

    /// Write external temperature and humidity for compensation.
    ///
    /// # Arguments
    /// * `temp_celsius` — Ambient temperature in degrees Celsius.
    /// * `rh_percent` — Ambient relative humidity in percent (0–100).
    pub fn set_compensation(&mut self, temp_celsius: f32, rh_percent: f32) -> Result<(), I2C::Error> {
        let temp_raw = ((temp_celsius + 273.15) * 64.0) as u16;
        let rh_raw = (rh_percent * 512.0) as u16;
        write_reg_le16(&mut self.inner.i2c, self.inner.addr, REG_TEMP_IN, temp_raw)?;
        write_reg_le16(&mut self.inner.i2c, self.inner.addr, REG_RH_IN, rh_raw)
    }

    /// Read TVOC concentration.
    ///
    /// Returns TVOC in ppb.
    pub fn read_tvoc(&mut self) -> Result<f32, I2C::Error> {
        wait_for_new_data(&mut self.inner.i2c, self.inner.addr, 5000)?;
        let raw = read_reg_le16(&mut self.inner.i2c, self.inner.addr, REG_DATA_TVOC)?;
        Ok(raw as f32)
    }

    /// Read equivalent CO2 concentration.
    ///
    /// Returns eCO2 in ppm.
    pub fn read_eco2(&mut self) -> Result<f32, I2C::Error> {
        wait_for_new_data(&mut self.inner.i2c, self.inner.addr, 5000)?;
        let raw = read_reg_le16(&mut self.inner.i2c, self.inner.addr, REG_DATA_ECO2)?;
        Ok(raw as f32)
    }

    /// Read Air Quality Index (UBA scale).
    ///
    /// Returns AQI value 1–5 (1=Excellent, 5=Unhealthy).
    pub fn read_aqi(&mut self) -> Result<u8, I2C::Error> {
        wait_for_new_data(&mut self.inner.i2c, self.inner.addr, 5000)?;
        let mut data = [0u8; 1];
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_DATA_AQI, &mut data)?;
        Ok(data[0] & 0x07)
    }

    /// Read ethanol concentration estimate.
    ///
    /// Returns ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
    pub fn read_ethanol(&mut self) -> Result<f32, I2C::Error> {
        wait_for_new_data(&mut self.inner.i2c, self.inner.addr, 5000)?;
        let raw = read_reg_le16(&mut self.inner.i2c, self.inner.addr, REG_DATA_TVOC)?;
        Ok(raw as f32)
    }

    /// Read raw sensor resistance from GPR_READ registers.
    ///
    /// # Arguments
    /// * `sensor` — Sensor number (1 or 4).
    ///
    /// Returns resistance in Ohms.
    pub fn read_raw_resistance(&mut self, sensor: u8) -> Result<f32, I2C::Error> {
        let offset = match sensor {
            1 => 0,
            4 => 6,
            _ => panic!("sensor must be 1 or 4, got {}", sensor),
        };
        let raw = read_reg_le16(&mut self.inner.i2c, self.inner.addr, REG_GPR_READ + offset)?;
        Ok(libm::powf(2.0, raw as f32 / 2048.0))
    }

    /// Read the temperature and humidity values used by the sensor.
    ///
    /// Returns (temp_celsius, rh_percent).
    pub fn read_compensation_actuals(&mut self) -> Result<(f32, f32), I2C::Error> {
        let mut data = [0u8; 4];
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_DATA_T, &mut data)?;
        let temp_raw = u16::from_le_bytes([data[0], data[1]]);
        let rh_raw = u16::from_le_bytes([data[2], data[3]]);
        let temp_celsius = (temp_raw as f32 / 64.0) - 273.15;
        let rh_percent = rh_raw as f32 / 512.0;
        Ok((temp_celsius, rh_percent))
    }

    /// Query firmware version (requires IDLE mode).
    ///
    /// Switches to IDLE, issues GET_APPVER command, reads GPR_READ, then
    /// returns to STANDARD mode.
    ///
    /// Returns (major, minor, release).
    pub fn get_firmware_version(&mut self) -> Result<(u8, u8, u8), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OPMODE, OPMODE_IDLE)?;
        delay_ms(1);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_COMMAND, 0x0E)?;
        delay_ms(1);
        let mut data = [0u8; 3];
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_GPR_READ + 4, &mut data)?;
        let major = data[0];
        let minor = data[1];
        let release = data[2];
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OPMODE, OPMODE_STANDARD)?;
        Ok((major, minor, release))
    }

    /// Configure the INTn interrupt pin.
    ///
    /// # Arguments
    /// * `enabled` — Enable interrupt pin.
    /// * `active_high` — True for active-high polarity, false for active-low.
    /// * `push_pull` — True for push-pull drive, false for open-drain.
    /// * `on_data` — Assert on new DATA_xxx data.
    /// * `on_gpr` — Assert on new GPR_READ data.
    pub fn configure_interrupt(&mut self, enabled: bool, active_high: bool, push_pull: bool, on_data: bool, on_gpr: bool) -> Result<(), I2C::Error> {
        let mut config = 0u8;
        if enabled { config |= 0x01; }
        if on_data { config |= 0x02; }
        if on_gpr { config |= 0x08; }
        if push_pull { config |= 0x20; }
        if active_high { config |= 0x40; }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)
    }

    /// Enter DEEP SLEEP mode for power saving.
    pub fn sleep(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OPMODE, OPMODE_DEEP_SLEEP)
    }

    /// Wake from DEEP SLEEP and resume STANDARD gas sensing.
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OPMODE, OPMODE_IDLE)?;
        delay_ms(1);
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_OPMODE, OPMODE_STANDARD)
    }

    /// Read the VALIDITY_FLAG from DEVICE_STATUS.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        self.inner.status()
    }

    /// Read calibrated air quality values.
    pub fn read_air_quality(&mut self) -> Result<(u8, f32, f32), I2C::Error> {
        self.inner.read_air_quality()
    }
}
