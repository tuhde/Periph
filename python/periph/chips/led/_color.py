def _hsv_to_rgb(h, s, v):
    """Convert HSV (0.0–1.0 each) to (r, g, b) integers 0–255."""
    if s == 0.0:
        c = int(v * 255)
        return c, c, c
    i = int(h * 6.0)
    f = h * 6.0 - i
    p = int(v * (1.0 - s) * 255)
    q = int(v * (1.0 - s * f) * 255)
    t = int(v * (1.0 - s * (1.0 - f)) * 255)
    vv = int(v * 255)
    i = i % 6
    if i == 0:
        return vv, t, p
    if i == 1:
        return q, vv, p
    if i == 2:
        return p, vv, t
    if i == 3:
        return p, q, vv
    if i == 4:
        return t, p, vv
    return vv, p, q
