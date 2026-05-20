pub(super) fn hsv_to_rgb(h: f32, s: f32, v: f32) -> (u8, u8, u8) {
    if s == 0.0 {
        let c = (v * 255.0) as u8;
        return (c, c, c);
    }
    let i  = (h * 6.0) as i32;
    let f  = h * 6.0 - i as f32;
    let p  = (v * (1.0 - s) * 255.0) as u8;
    let q  = (v * (1.0 - s * f) * 255.0) as u8;
    let t  = (v * (1.0 - s * (1.0 - f)) * 255.0) as u8;
    let vv = (v * 255.0) as u8;
    match i % 6 {
        0 => (vv, t,  p),
        1 => (q,  vv, p),
        2 => (p,  vv, t),
        3 => (p,  q,  vv),
        4 => (t,  p,  vv),
        _ => (vv, p,  q),
    }
}
