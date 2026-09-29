def to_signed(value: int, bits: int) -> int:
    """Interpret the low `bits` bits of `value` as two's-complement signed.

    Args:
        value: Unsigned raw register value.
        bits: Width of the value in bits (e.g. 16, 24).

    Returns:
        int: The signed interpretation of the low `bits` bits of `value`.
    """
    sign_bit = 1 << (bits - 1)
    return (value & (sign_bit - 1)) - (value & sign_bit)
