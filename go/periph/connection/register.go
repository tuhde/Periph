// Package connection: ToSigned is a sign-extension helper for register
// values — pure integer math, no bus dependency, shared by every
// RegisterConnection implementation and any chip driver that needs it
// (e.g. HX711's 24-bit ADC result, which has no register address at all).
package connection

// ToSigned interprets the low bits bits of value as two's-complement signed.
func ToSigned(value uint32, bits uint) int32 {
	signBit := uint32(1) << (bits - 1)
	return int32((value & (signBit - 1)) - (value & signBit))
}
