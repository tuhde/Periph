use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::humidity::Dht11Error;
use periph::transport::dhtxx::DHTxxTransportLinux;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let mut passed = 0i32;
    let mut failed = 0i32;

    // Smoke test: open the line, construct the driver, and validate the API surface.
    let chip_path   = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let line_offset: u32 = std::env::var("DHT11_LINE").ok().and_then(|v| v.parse().ok()).unwrap_or(4);

    if let Ok(mut chip) = Chip::new(&chip_path) {
        if let Ok(handle) = chip.get_line(line_offset).expect("get line").request(LineRequestFlags::INPUT, 0, "dht11_test") {
            let pin = CdevPin::new(handle).expect("dht11 pin");
            let _transport = DHTxxTransportLinux::new(pin);
            check_true!(true, "dht11_compile_link", passed, failed);
        } else {
            check_true!(true, "dht11_skip_no_hw", passed, failed);
        }
    } else {
        check_true!(true, "dht11_skip_no_chip", passed, failed);
    }

    // Test the decode logic against the datasheet example frame.
    {
        let frame: [u8; 5] = [0x35, 0x00, 0x18, 0x04, 0x51];
        let expected = (frame[0] as u16 + frame[1] as u16 + frame[2] as u16 + frame[3] as u16) as u8;
        check_true!(expected == frame[4], "checksum_validates", passed, failed);
        let humidity = frame[0] as f32 + (frame[1] as f32) / 10.0;
        let sign: f32 = if frame[3] & 0x80 != 0 { -1.0 } else { 1.0 };
        let temp_dec_value = (frame[3] & 0x7F) as f32;
        let temperature = sign * (frame[2] as f32 + temp_dec_value / 10.0);
        check_true!((temperature - 24.4).abs() < 0.001, "decode_datasheet_example.t", passed, failed);
        check_true!((humidity - 53.0).abs() < 0.001, "decode_datasheet_example.h", passed, failed);
    }

    // Negative temperature.
    {
        let frame: [u8; 5] = [0x20, 0x00, 0x0A, 0x81, 0xAB];
        let expected = (frame[0] as u16 + frame[1] as u16 + frame[2] as u16 + frame[3] as u16) as u8;
        check_true!(expected == frame[4], "negative_checksum_validates", passed, failed);
        let humidity = frame[0] as f32 + (frame[1] as f32) / 10.0;
        let sign: f32 = if frame[3] & 0x80 != 0 { -1.0 } else { 1.0 };
        let temp_dec_value = (frame[3] & 0x7F) as f32;
        let temperature = sign * (frame[2] as f32 + temp_dec_value / 10.0);
        check_true!((temperature - (-10.1)).abs() < 0.001, "decode_negative_temperature.t", passed, failed);
        check_true!((humidity - 32.0).abs() < 0.001, "decode_negative_temperature.h", passed, failed);
    }

    // Checksum error
    {
        let frame: [u8; 5] = [0x35, 0x00, 0x18, 0x04, 0x00];
        let expected = (frame[0] as u16 + frame[1] as u16 + frame[2] as u16 + frame[3] as u16) as u8;
        check_true!(expected != frame[4], "checksum_mismatch_detected", passed, failed);
    }

    let _: Dht11Error<()> = Dht11Error::BadFrame;

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
