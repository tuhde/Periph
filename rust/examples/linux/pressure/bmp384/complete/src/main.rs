use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp384Full, MODE_NORMAL, MODE_FORCED};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp384Full::new(dev, addr, false).expect("init BMP384"); // Create BMP384 driver, (i2c, addr=0x76)

    bmp.configure(4, 1, 2, 0x03).expect("configure");             // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → Result<()>
                                                                    // sets oversampling, IIR coefficient, and output data rate
    bmp.set_mode(MODE_NORMAL).expect("set_mode");                  // Set power mode, (mode) → Result<()>
    let ready = bmp.is_data_ready().expect("is_data_ready");       // Check data-ready flag, () → Result<bool>
                                                                    // true if STATUS.drdy_press is set
    let t = bmp.temperature().expect("temperature");               // Read temperature, () → f32 °C
    let p = bmp.pressure().expect("pressure");                     // Read pressure, () → f32 hPa
    let (_p, _t) = bmp.read().expect("read");                      // Read both values in one burst, () → (f32, f32)
    let (_p2, _t2) = bmp.read_forced().expect("read_forced");      // Trigger forced measurement and read, () → (f32, f32)
    bmp.fifo_configure(true, true, 64, false).expect("fifo_configure"); // Configure FIFO, (press_en bool, temp_en bool, wtm u16, stop_on_full bool) → Result<()>
                                                                    // enables FIFO, sets watermark, arms pressure+temperature frames
    let mut frames: [periph::chips::pressure::Bmp384FifoFrame; 16] = unsafe { std::mem::zeroed() };
    let _n = bmp.fifo_read(&mut frames).expect("fifo_read");       // Read and parse FIFO frames, (&mut [Bmp384FifoFrame]) → Result<usize>
    bmp.fifo_flush().expect("fifo_flush");                         // Flush FIFO contents, () → Result<()>
    let alt = bmp.altitude(1013.25).expect("altitude");            // Compute altitude, (sea_level_hpa f32) → f32 m
                                                                    // uses barometric formula to convert pressure to metres
    bmp.softreset().expect("softreset");                           // Soft reset chip, () → Result<()>
                                                                    // writes 0xB6 to CMD, waits 2 ms, re-reads calibration

    println!("T={:.1} C, P={:.1} hPa, ready={}, alt={:.1} m", t, p, ready, alt);
    // Avoid unused-variable warning for MODE_FORCED reference.
    let _ = MODE_FORCED;
}
