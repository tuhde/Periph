use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp581Full, FIFO_BOTH, FIFO_STREAM, IIR_BYPASS, IIR_COEFF_3, MODE_NORMAL, OSR_1X};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x46);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp581Full::new(dev, addr, false).expect("init BMP581"); // Create BMP581 driver, (i2c, addr=0x46)
    let cid = bmp.chip_id().expect("chip_id");                            // Read chip ID, () → u8
    println!("chip_id={:#x} (expect 0x50)", cid);

    bmp.configure(0x1C, OSR_1X, OSR_1X, true).expect("configure");        // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en bool) → Result<(), E>
    bmp.set_mode(MODE_NORMAL).expect("set_mode");                          // Set power mode, (mode 0/1/2/3) → Result<(), E>
    bmp.set_iir_filter(IIR_COEFF_3, IIR_BYPASS).expect("set_iir");          // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → Result<(), E>
    bmp.configure_fifo(FIFO_BOTH, FIFO_STREAM, 8).expect("configure_fifo"); // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → Result<(), E>
    let n = bmp.fifo_count().expect("fifo_count");                         // Read FIFO frame count, () → Result<u8, E>
    bmp.enable_drdy_interrupt(true).expect("enable_drdy");                 // Enable data-ready interrupt, (enable bool) → Result<(), E>
    let drdy = bmp.data_ready().expect("data_ready");                      // Check data ready, () → Result<bool, E>
    let (p_f, t_f) = bmp.forced().expect("forced");                        // Trigger FORCED measurement, () → Result<(Pa, °C), E>
    let (p_pa, t_c) = bmp.both().expect("both");                           // Read both atomically, () → Result<(Pa, °C), E>
    let alt = bmp.altitude(101325.0).expect("altitude");                    // Compute altitude, (sea_level_pa=101325.0) → Result<f32, E>
    let st = bmp.status().expect("status");                                // Read STATUS, () → Result<u8, E>
    let ist = bmp.interrupt_status().expect("interrupt_status");           // Read INT_STATUS, () → Result<u8, E>
    let (op, ot) = bmp.effective_osr().expect("effective_osr");             // Read effective OSR, () → Result<(u8, u8), E>
    bmp.set_oor_threshold(110000.0, 200.0, 1).expect("set_oor");            // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → Result<(), E>
    bmp.software_reset().expect("software_reset");                         // Soft reset chip, () → Result<(), E>

    println!("P={:.1} Pa, T={:.2} C, alt={:.1} m, frames={}, drdy={}, eff=({}, {})", p_pa, t_c, alt, n, drdy, op, ot);
}