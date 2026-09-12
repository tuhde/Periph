use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::Hx710aFull;
use periph::connection::hx711::HX711Connection;

fn main() {
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32  = std::env::var("HX710A_DOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(5);
    let sck_offset:  u32  = std::env::var("HX710A_PD_SCK").ok().and_then(|v| v.parse().ok()).unwrap_or(6);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dout_handle = chip.get_line(dout_offset).expect("get dout line")
        .request(LineRequestFlags::INPUT, 0, "hx710a_complete").expect("request dout");
    let sck_handle = chip.get_line(sck_offset).expect("get sck line")
        .request(LineRequestFlags::OUTPUT, 0, "hx710a_complete").expect("request pd_sck");

    let dout   = CdevPin::new(dout_handle).expect("dout pin");
    let pd_sck = CdevPin::new(sck_handle).expect("pd_sck pin");

    let connection = HX711Connection::new(dout, pd_sck);
    let mut chip = Hx710aFull::new(connection).expect("init HX710A");  // Create HX710A driver — discards first conversion, (connection) → Result<Hx710aFull, _>

    loop {
        let _ready = chip.is_ready().expect("is_ready");                // Check if conversion is ready (non-blocking), () → Result<bool, _>
                                                                         // returns true when DOUT is LOW
        let raw = chip.read_raw().expect("read_raw");                   // Read signed 24-bit differential-input value, () → Result<i32, _>
                                                                         // blocks until DOUT goes LOW, then clocks out 24 bits

        chip.set_rate(40).expect("set_rate 40");                        // Select differential-input output rate, (rate: u8) → Result<(), _>
                                                                         // takes effect after next read; issues dummy read to apply
        chip.set_rate(10).expect("set_rate 10");                        // (restores default 10 SPS)

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

        let temp_raw = chip.read_temperature_raw().expect("read_temperature_raw");  // Read raw on-chip temperature code, () → Result<i32, _>
                                                                         // uncalibrated ADC code (~20.4 LSB/°C), not a °C value
        println!("raw={} avg={} offset={} scale={:.1} weight={:.1} temp_raw={}", raw, avg, offset, scale, weight, temp_raw);

        chip.power_down().expect("power_down");                         // Enter power-down mode, () → Result<(), _>
                                                                         // holds PD_SCK HIGH; caller waits >60 µs before other operations
        std::thread::sleep(std::time::Duration::from_micros(65));
        chip.power_up().expect("power_up");                             // Exit power-down, reset chip, discard settling conversion, () → Result<(), _>
                                                                         // resets to differential input, gain 128, 10 SPS

        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
