use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx710bFull;
use periph::connection::hx711::HX711Connection;

// Battery-powered load cell demo: tare at startup, then print weight
// continuously, watching the DVDD−AVDD supply-difference reading for
// drift that signals a low battery. Replace SCALE_FACTOR with the value
// calibrated for your load cell and wiring topology. Calibration: (1)
// call tare() with nothing on the scale; (2) place a known 100 g reference
// weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
const SCALE_FACTOR: f32 = 420.0;
const LOW_BATT_DELTA: i32 = 50000;  // supply-diff drift threshold from baseline

fn main() {
    let chip_path  = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32 = std::env::var("HX710B_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32 = std::env::var("HX710B_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dout_handle = chip.get_line(dout_offset).expect("get dout line")
        .request(LineRequestFlags::INPUT, 0, "hx710b_demo").expect("request dout");
    let sck_handle = chip.get_line(sck_offset).expect("get sck line")
        .request(LineRequestFlags::OUTPUT, 0, "hx710b_demo").expect("request pd_sck");

    let dout   = CdevPin::new(dout_handle).expect("dout pin");
    let pd_sck = CdevPin::new(sck_handle).expect("pd_sck pin");

    let connection = HX711Connection::new(dout, pd_sck);
    let mut chip = Hx710bFull::new(connection).expect("init HX710B");  // Create HX710B driver — discards first conversion, (connection) → Result<Hx710bFull, _>

    // --- Tare the scale before use ---
    // Averaging 10 readings with nothing on the scale suppresses noise in
    // the zero-offset capture, so later weight readings aren't skewed by drift.
    println!("Taring — keep scale empty...");
    chip.tare(10).expect("tare");                                   // Capture zero offset from 10-reading average, (times: u8) → Result<(), _>
    chip.set_scale(SCALE_FACTOR);                                   // Set calibration scale factor, (factor: f32) → ()
    println!("Tare done. Place weight on scale.");

    // --- Capture the supply-difference baseline at full charge ---
    // The datasheet gives no absolute LSB-to-volts conversion for this
    // channel — it is only useful for relative drift tracking. Capture one
    // reading at startup as a "known-good battery" baseline, then compare
    // later readings against it to detect discharge.
    let baseline_supp_diff: i32 = chip.read_supply_diff_raw().expect("read_supply_diff_raw");  // Read raw DVDD−AVDD supply-difference code, () → Result<i32, _>

    let mut prev_weight: Option<f32> = None;
    let mut iteration: u32 = 0;
    loop {
        let weight = chip.read_weight(3).expect("read_weight");     // Return calibrated weight, (times: u8) → Result<f32, _>
        let rounded = (weight * 10.0).round() / 10.0;
        if prev_weight.map_or(true, |p| (rounded - p).abs() > 1.0) {
            println!("→ {:.1} g", rounded);
            prev_weight = Some(rounded);
        }

        if iteration % 20 == 0 {
            // --- Sample the DVDD−AVDD supply-difference channel every ~10 s ---
            // Uncalibrated ADC code, intended only for relative drift
            // tracking against a known-good baseline (the datasheet's
            // stated purpose for this channel in battery-powered
            // weigh-scale applications) — not an absolute voltage reading.
            let supp_diff: i32 = chip.read_supply_diff_raw().expect("read_supply_diff_raw");  // Read raw DVDD−AVDD supply-difference code, () → Result<i32, _>
            let drift = supp_diff - baseline_supp_diff;
            if drift.abs() > LOW_BATT_DELTA {
                println!("LOW BATTERY (supply_diff={}, drift={})", supp_diff, drift);
            }
        }
        iteration += 1;

        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
