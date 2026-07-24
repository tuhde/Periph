//go:build tinygo

// HX711Transport is the TinyGo implementation of the HX711 bit-bang
// transport. DOUT and PD_SCK are machine.Pin values the caller
// passes in. No sleep is needed between polls or clock edges —
// TinyGo's per-call overhead exceeds the HX711's 0.2 µs timing
// minimums.
package transport

import (
	"fmt"
	"machine"
)

// HX711Transport is a TinyGo machine.Pin-backed bit-bang transport
// for the HX711 24-bit ADC.
type HX711Transport struct {
	dout   machine.Pin
	pdSck  machine.Pin
}

// NewHX711Transport binds the given DOUT (input) and PD_SCK (output)
// pins. Configures them for the correct direction.
func NewHX711Transport(dout, pdSck machine.Pin) *HX711Transport {
	dout.Configure(machine.PinConfig{Mode: machine.PinInput})
	pdSck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	pdSck.Low()
	return &HX711Transport{dout: dout, pdSck: pdSck}
}

// Close is a no-op on TinyGo: pins are not released back to a
// "free" state. Provided for API symmetry with the Linux transport.
func (t *HX711Transport) Close() error {
	return nil
}

// IsReady returns true if DOUT is LOW (chip has data ready).
func (t *HX711Transport) IsReady() (bool, error) {
	return !t.dout.Get(), nil
}

// ReadRaw waits for DOUT LOW, then clocks out numPulses pulses
// (25, 26, or 27) sampling DOUT at the rising edge of PD_SCK.
// Returns the sign-extended 24-bit value.
func (t *HX711Transport) ReadRaw(numPulses int) (int32, error) {
	if numPulses != 25 && numPulses != 26 && numPulses != 27 {
		return 0, fmt.Errorf("hx711: numPulses must be 25, 26, or 27 (got %d)", numPulses)
	}
	// Wait for DOUT LOW.
	for !t.dout.Get() {
		// busy wait
	}
	var raw uint32
	for i := 0; i < numPulses; i++ {
		if i < 24 {
			bit := uint32(0)
			if t.dout.Get() {
				bit = 1
			}
			raw = (raw << 1) | bit
		}
		t.pdSck.High()
		t.pdSck.Low()
	}
	signed := int32(raw)
	if raw&0x800000 != 0 {
		signed = int32(raw) - 0x1000000
	}
	return signed, nil
}

// PowerDown holds PD_SCK HIGH for >60 µs.
func (t *HX711Transport) PowerDown() error {
	t.pdSck.High()
	return nil
}

// PowerUp drives PD_SCK LOW to reset the chip.
func (t *HX711Transport) PowerUp() error {
	t.pdSck.Low()
	return nil
}
