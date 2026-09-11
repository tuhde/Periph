use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Lps22dfFull, LPS22DF_FIFO_FIFO};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps = Lps22dfFull::new(dev, addr, false).expect("init LPS22DF"); // Create LPS22DF driver, (i2c, addr=0x5C)
    lps.configure(3, 0, false, 0, true).expect("configure");           // Configure chip, (odr=10 Hz, avg=4, en_lpfp=false, lfpf_cfg=0, bdu=true) → ()
    lps.oneshot().expect("oneshot");                                    // Trigger one-shot conversion, () → ()
    let p = lps.pressure().expect("read pressure");                     // Read pressure, () → f32 Pa
                                                                          // 24-bit two's complement, 4096 LSB/hPa → Pa
    let t = lps.temperature().expect("read temperature");               // Read temperature, () → f32 °C
                                                                          // 16-bit two's complement, 100 LSB/°C
    let alt = lps.altitude(101325.0).expect("altitude");                // Compute altitude, (sea_level_pa=101325.0) → f32 m
                                                                          // barometric formula
    lps.software_reset().expect("reset");                                // Reset chip, () → ()
    lps.set_pressure_offset(-50.0).expect("offset");                     // Set pressure offset, (offset_pa=-50.0) → ()
    lps.set_pressure_threshold(102000.0).expect("threshold");           // Set pressure threshold, (threshold_pa=102000.0) → ()
    lps.configure_interrupt(false, false, true, false, true, false, false, false).expect("configure_interrupt");  // Configure interrupt, (int_h_l, pp_od, drdy, drdy_pls, int_en, int_f_wtm, int_f_full, int_f_ovr) → ()
    lps.configure_pressure_event(true, false, false).expect("pressure_event");  // Configure pressure event, (phe=true, ple=false, lir=false) → ()
    lps.autozero().expect("autozero");                                   // Capture AUTOZERO reference, () → ()
    lps.reset_reference().expect("reset_reference");                     // Reset reference, () → ()
    let ref_p = lps.reference_pressure().expect("ref");                  // Read reference pressure, () → f32 Pa
    lps.set_fifo_mode(LPS22DF_FIFO_FIFO).expect("set_fifo_mode");        // Set FIFO mode, (mode 0–5) → ()
    lps.set_fifo_watermark(64).expect("watermark");                      // Set FIFO watermark, (level 0–127) → ()
    let count = lps.fifo_sample_count().expect("fifo_sample_count");    // Read FIFO sample count, () → u8
    let mut samples = [0.0f32; 128];
    let n_read = lps.read_fifo(&mut samples).expect("read_fifo");       // Read FIFO samples, (out: &mut [f32]) → u8
    let src = lps.interrupt_source().expect("interrupt_source");         // Read interrupt source, () → u8
    println!("T={:.2} C, P={:.0} Pa, alt={:.1} m", t, p, alt);
    println!("ref={:.0} Pa, fifo={}/{}, src=0x{:02X}", ref_p, n_read, count, src);
}