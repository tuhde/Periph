//go:build linux && !tinygo

// HX711Transport is the Linux implementation of the HX711 bit-bang
// transport. DOUT (input) and PD_SCK (output) are requested on
// /dev/gpiochip0 via the GPIO character-device ioctls — no cgo, no
// libgpiod bindings.
package transport

import (
	"fmt"
	"time"
)

// HX711Transport is a Linux /dev/gpiochip0-backed bit-bang transport
// for the HX711 24-bit ADC.
type HX711Transport struct {
	doutLine int
	pdSckLine int
}

// NewHX711Transport requests DOUT (input) and PD_SCK (output) on
// /dev/gpiochip0 by line offset. Returns an error if either line
// cannot be reserved.
func NewHX711Transport(doutLine, pdSckLine int) (*HX711Transport, error) {
	if err := requestGpioLines(doutLine, pdSckLine); err != nil {
		return nil, err
	}
	// Set PD_SCK low.
	if err := setGpioLine(pdSckLine, false); err != nil {
		return nil, err
	}
	return &HX711Transport{doutLine: doutLine, pdSckLine: pdSckLine}, nil
}

// Close releases the line handle and closes the gpiochip fd.
func (t *HX711Transport) Close() error {
	return releaseGpioLines(t.doutLine, t.pdSckLine)
}

// IsReady returns true if DOUT is LOW (chip has data ready).
func (t *HX711Transport) IsReady() (bool, error) {
	v, err := readGpioLine(t.doutLine)
	if err != nil {
		return false, err
	}
	return !v, nil
}

// ReadRaw waits up to 1 s for DOUT LOW, then clocks out numPulses
// pulses (25, 26, or 27 — channel/gain select), sampling DOUT at
// each rising edge of PD_SCK. Returns the sign-extended 24-bit value.
func (t *HX711Transport) ReadRaw(numPulses int) (int32, error) {
	if numPulses != 25 && numPulses != 26 && numPulses != 27 {
		return 0, fmt.Errorf("hx711: numPulses must be 25, 26, or 27 (got %d)", numPulses)
	}
	// Wait for DOUT LOW with 1 ms polling to avoid spinning a CPU core.
	deadline := time.Now().Add(time.Second)
	for {
		ready, err := t.IsReady()
		if err != nil {
			return 0, err
		}
		if ready {
			break
		}
		if time.Now().After(deadline) {
			return 0, fmt.Errorf("hx711: timeout waiting for DOUT LOW")
		}
		time.Sleep(time.Millisecond)
	}
	var raw uint32
	for i := 0; i < numPulses; i++ {
		// The chip presents data on the rising edge of PD_SCK;
		// sample DOUT before the high transition for the first 24 bits.
		if i < 24 {
			v, err := readGpioLine(t.doutLine)
			if err != nil {
				return 0, err
			}
			bit := uint32(0)
			if v {
				bit = 1
			}
			raw = (raw << 1) | bit
		}
		if err := setGpioLine(t.pdSckLine, true); err != nil {
			return 0, err
		}
		if err := setGpioLine(t.pdSckLine, false); err != nil {
			return 0, err
		}
	}
	// Sign-extend.
	if raw&0x800000 != 0 {
		return int32(raw) - 0x1000000, nil
	}
	return int32(raw), nil
}

// PowerDown holds PD_SCK HIGH for >60 µs to put the HX711 into
// power-down mode.
func (t *HX711Transport) PowerDown() error {
	if err := setGpioLine(t.pdSckLine, true); err != nil {
		return err
	}
	time.Sleep(100 * time.Microsecond)
	return nil
}

// PowerUp drives PD_SCK LOW to reset the chip to Channel A, Gain 128.
func (t *HX711Transport) PowerUp() error {
	return setGpioLine(t.pdSckLine, false)
}
