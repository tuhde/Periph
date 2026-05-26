use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx711Full;
use periph::transport::hx711::HX711Transport;

// Kitchen scale demo: tare at startup, then print weight continuously.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// weight W grams; (3) SCALE_FACTOR = (read_average() - get_offset()) / W.
const SCALE_FACTOR: f32 = 420.0;

fn main() {
    let chip_path  = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32 = std::env::var("HX711_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32 = std::env::var("HX711_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dout_handle = chip.get_line(dout_offset).expect("get dout line")
        .request(LineRequestFlags::INPUT, 0, "hx711_demo").expect("request dout");
    let sck_handle = chip.get_line(sck_offset).expect("get sck line")
        .request(LineRequestFlags::OUTPUT, 0, "hx711_demo").expect("request pd_sck");

    let dout   = CdevPin::new(dout_handle).expect("dout pin");
    let pd_sck = CdevPin::new(sck_handle).expect("pd_sck pin");

    let transport = HX711Transport::new(dout, pd_sck);
    let mut chip = Hx711Full::new(transport).expect("init HX711");  // Create HX711 driver — discards first conversion, (transport) → Result<Hx711Full, _>

    println!("Taring — keep scale empty...");
    chip.tare(10).expect("tare");                                   // Capture zero offset from 10-reading average, (times: u8) → Result<(), _>
    chip.set_scale(SCALE_FACTOR);                                   // Set calibration scale factor, (factor: f32) → ()
    println!("Tare done. Place weight on scale.");

    let mut prev_weight: Option<f32> = None;
    loop {
        let weight = chip.read_weight(3).expect("read_weight");     // Return calibrated weight, (times: u8) → Result<f32, _>
        let rounded = (weight * 10.0).round() / 10.0;
        if prev_weight.map_or(true, |p| (rounded - p).abs() > 1.0) {
            println!("→ {:.1} g", rounded);
            prev_weight = Some(rounded);
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
