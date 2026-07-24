//go:build tinygo

// SIPOTransport is the TinyGo implementation of the serial-shift-register
// (SIPO) transport for chips like TPIC6B595 / 74HC595.
package transport

import "machine"

// SIPOTransport bit-bangs SER IN/SRCK plus RCK/SRCLR/G as machine.Pin
// GPIO lines. Hardware SPI mode is also supported when the caller
// passes a non-nil *machine.SPI.
type SIPOTransport struct {
	serIn  machine.Pin
	srck   machine.Pin
	rck    machine.Pin
	srclr  machine.Pin // zero = unused
	g      machine.Pin // zero = unused
	spi    *machine.SPI
}

// NewSIPOSoftwareSPI constructs a SIPO transport that bit-bangs SER IN/SRCK
// plus RCK/SRCLR/G. Pass srclr=0 and/or g=0 to omit those pins.
func NewSIPOSoftwareSPI(serIn, srck, rck, srclr, g machine.Pin) *SIPOTransport {
	serIn.Configure(machine.PinConfig{Mode: machine.PinOutput})
	srck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	rck.Configure(machine.PinConfig{Mode: machine.PinOutput})
	serIn.Low()
	srck.Low()
	rck.Low()
	if srclr != 0 {
		srclr.Configure(machine.PinConfig{Mode: machine.PinOutput})
		srclr.High()
	}
	if g != 0 {
		g.Configure(machine.PinConfig{Mode: machine.PinOutput})
		g.Low()
	}
	return &SIPOTransport{serIn: serIn, srck: srck, rck: rck, srclr: srclr, g: g}
}

// Write shifts data out MSB-first, then pulses RCK to latch.
func (t *SIPOTransport) Write(data []byte) error {
	for _, b := range data {
		for bit := 7; bit >= 0; bit-- {
			if (b>>bit)&1 == 1 {
				t.serIn.High()
			} else {
				t.serIn.Low()
			}
			t.srck.High()
			t.srck.Low()
		}
	}
	t.rck.High()
	t.rck.Low()
	return nil
}

// Clear pulses SRCLR LOW then HIGH.
func (t *SIPOTransport) Clear() error {
	if t.srclr == 0 {
		return errSipoNotConfigured
	}
	t.srclr.Low()
	t.srclr.High()
	return nil
}

// SetOutputEnable drives G LOW (true) or HIGH (false).
func (t *SIPOTransport) SetOutputEnable(en bool) error {
	if t.g == 0 {
		return errSipoNotConfigured
	}
	if en {
		t.g.Low()
	} else {
		t.g.High()
	}
	return nil
}

// Close is a no-op on TinyGo.
func (t *SIPOTransport) Close() error {
	return nil
}

var errSipoNotConfigured = &sipoError{"sipo: pin not configured"}

type sipoError struct{ msg string }

func (e *sipoError) Error() string { return e.msg }
