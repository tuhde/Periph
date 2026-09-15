use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx710aFull;
use periph::connection::hx711::HX711Connection;

// Temperature-monitored load cell demo: tare at startup, then print weight
// continuously, sampling the on-chip temperature sensor periodically.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// 100 g reference weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
const SCALE_FACTOR: f32 = 420.0;

fn main() {
    let chip_path  = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32 = std::env::var("HX710A_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32 = std::env::var("HX710A_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dout_handle = chip.get_line(dout_offset).expect("get dout line")
        .request(LineRequestFlags::INPUT, 0, "hx710a_demo").expect("request dout");
    let sck_handle = chip.get_line(sck_offset).expect("get sck line")
        .request(LineRequestFlags::OUTPUT, 0, "hx710a_demo").expect("request pd_sck");

    let dout   = CdevPin::new(dout_handle).expect("dout pin");
    let pd_sck = CdevPin::new(sck_handle).expect("pd_sck pin");

    let connection = HX711Connection::new(dout, pd_sck);
    let mut chip = Hx710aFull::new(connection).expect("init HX710A");  // Create HX710A driver — discards first conversion, (connection) → Result<Hx710aFull, _>

    // --- Tare the scale before use ---
    // Averaging 10 readings with nothing on the scale suppresses noise in
    // the zero-offset capture, so later weight readings aren't skewed by drift.
    println!("Taring — keep scale empty...");
    chip.tare(10).expect("tare");                                   // Capture zero offset from 10-reading average, (times: u8) → Result<(), _>
    chip.set_scale(SCALE_FACTOR);                                   // Set calibration scale factor, (factor: f32) → ()
    println!("Tare done. Place weight on scale.");

    let mut prev_weight: Option<f32> = None;
    let mut iteration: u32 = 0;
    loop {
        let weight = chip.read_weight(3).expect("read_weight");     // Return calibrated weight, (times: u8) → Result<f32, _>
        let rounded = (weight * 10.0).round() / 10.0;
        if prev_weight.map_or(true, |p| (rounded - p).abs() > 1.0) {
            println!("→ {:.1} g", rounded);
            prev_weight = Some(rounded);
        }

        if iteration % 10 == 0 {
            // --- Sample the on-chip temperature sensor every ~5 s ---
            // This is an uncalibrated raw ADC code (~20.4 LSB/°C, chip-to-chip
            // offset/gain vary per the datasheet), intended only for the
            // datasheet's stated purpose of relative drift compensation of
            // the weight reading — not as an absolute °C measurement.
            let temp_raw = chip.read_temperature_raw().expect("read_temperature_raw");  // Read raw on-chip temperature code, () → Result<i32, _>
            println!("temp raw={}", temp_raw);
        }
        iteration += 1;

        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
