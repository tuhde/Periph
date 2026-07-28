//go:build tinygo

package connection

import "machine"

// GpioOutputPin drives a machine.Pin configured as output.
type GpioOutputPin struct {
	pin machine.Pin
}

// NewGpioOutputPin configures pin as an output.
func NewGpioOutputPin(pin machine.Pin) *GpioOutputPin {
	pin.Configure(machine.PinConfig{Mode: machine.PinOutput})
	return &GpioOutputPin{pin: pin}
}

// Set drives the pin high or low.
func (p *GpioOutputPin) Set(high bool) error {
	if high {
		p.pin.High()
	} else {
		p.pin.Low()
	}
	return nil
}

// Close is a no-op on TinyGo: machine.Pin has no explicit release.
func (p *GpioOutputPin) Close() error {
	return nil
}
