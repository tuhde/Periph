use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp280Full, OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_4, T_SB_62_5_MS, STATUS_MEASURING, STATUS_IM_UPDATE};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok().and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok()).unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp280Full::new(dev, addr, 1, 1, 1, 0, 0).expect("init BMP280");  // Create BMP280 driver, (transport, addr=0x76)

    let cid = bmp.chip_id().expect("read chip id");                      // Read chip ID, () → u8
    println!("chip_id=0x{:02x}", cid);                                    // expect 0x58
    let s = bmp.status().expect("read status");                           // Read status register, () → u8
    println!("status=0x{:02x} (measuring={}, im_update={})", s,
             (s & STATUS_MEASURING) != 0, (s & STATUS_IM_UPDATE) != 0);

    bmp.configure(OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_4, T_SB_62_5_MS).expect("configure");  // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → ()
    bmp.set_oversampling(1, 1).expect("set_oversampling");                // Update oversampling, (osrs_t, osrs_p) → ()
    bmp.set_filter(0).expect("set_filter");                              // Update IIR filter, (coeff 0–4) → ()
    bmp.set_standby(3).expect("set_standby");                           // Update standby time, (t_sb 0–7) → ()
    bmp.reset().expect("soft reset");                                    // Soft reset and re-init, () → ()

    let t = bmp.temperature().expect("read temperature");                 // Read temperature, () → f32 C
    let p = bmp.pressure().expect("read pressure");                       // Read pressure, () → f32 hPa
    println!("T={:.1} C, P={:.1} hPa", t, p);
}