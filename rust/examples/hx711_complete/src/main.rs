use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx711Full;
use periph::transport::hx711::HX711Transport;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32  = std::env::var("HX711_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32  = std::env::var("HX711_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let chip = gpiocdev::Chip::new(&chip_path).expect("open gpio chip");
    let dout_line = chip
        .request_line(gpiocdev::Request::input([dout_offset]).consumer("hx711_complete"))
        .expect("request dout");
    let sck_line = chip
        .request_line(gpiocdev::Request::output([sck_offset]).consumer("hx711_complete"))
        .expect("request pd_sck");

    let dout   = CdevPin::new(dout_line, dout_offset).expect("dout pin");
    let pd_sck = CdevPin::new(sck_line,  sck_offset).expect("pd_sck pin");

    let transport = HX711Transport::new(dout, pd_sck);
    let mut chip = Hx711Full::new(transport).expect("init HX711");  // Create HX711 driver — discards first conversion, (transport) → Result<Hx711Full, _>

    loop {
        let _ready = chip.is_ready().expect("is_ready");                // Check if conversion is ready (non-blocking), () → Result<bool, _>
                                                                         // returns true when DOUT is LOW
        let raw = chip.read_raw().expect("read_raw");                   // Read signed 24-bit ADC value at current gain, () → Result<i32, _>
                                                                         // blocks until DOUT goes LOW, then clocks out 24 bits

        chip.set_gain(64).expect("set_gain 64");                        // Select channel and gain, (gain: u8) → Result<(), _>
                                                                         // 128 → Channel A, 64 → Channel A, 32 → Channel B; issues dummy read to apply
        chip.set_gain(128).expect("set_gain 128");

        let avg = chip.read_average(10).expect("read_average");         // Average multiple raw readings, (times: u8) → Result<i32, _>
                                                                         // blocks for `times` complete conversions

        chip.tare(10).expect("tare");                                   // Capture zero offset from 10-reading average, (times: u8) → Result<(), _>
                                                                         // stores result in internal offset; call with nothing on the scale
        let offset = chip.get_offset();                                 // Return stored tare offset, () → i32

        chip.set_scale(420.0);                                          // Set calibration scale factor, (factor: f32) → ()
                                                                         // factor = (read_average() - offset) / known_weight_in_target_unit
        let scale = chip.get_scale();                                   // Return current scale factor, () → f32

        let weight = chip.read_weight(5).expect("read_weight");         // Return calibrated weight, (times: u8) → Result<f32, _>
                                                                         // computes (read_average(times) - offset) / scale
        println!("raw={} avg={} offset={} scale={:.1} weight={:.1}", raw, avg, offset, scale, weight);

        chip.power_down().expect("power_down");                         // Enter power-down mode, () → Result<(), _>
                                                                         // holds PD_SCK HIGH; caller waits >60 µs before other operations
        std::thread::sleep(std::time::Duration::from_micros(65));
        chip.power_up().expect("power_up");                             // Exit power-down, reset chip, discard settling conversion, () → Result<(), _>
                                                                         // resets to Channel A Gain 128; first post-reset conversion discarded internally

        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
