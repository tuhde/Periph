//go:build tinygo

// SIPOConnection is the TinyGo implementation of the serial-shift-register
// (SIPO) connection for chips like TPIC6B595 / 74HC595.
package connection

import "machine"

// SIPOConnection bit-bangs SER IN/SRCK plus RCK/SRCLR/G as machine.Pin
// GPIO lines. Hardware SPI mode is also supported when the caller
// passes a non-nil *machine.SPI. It does not implement the Connection
// interface — see the Linux variant's doc comment — but embeds
// connectionBase for the shared Enable/Disable/IsEnabled/EnPin gating.
// There is no INT line, so IntPin is always nil.
type SIPOConnection struct {
	connectionBase
	serIn machine.Pin
	srck  machine.Pin
	rck   machine.Pin
	srclr machine.Pin // zero = unused
	g     machine.Pin // zero = unused
	spi   *machine.SPI
}

// NewSIPOSoftwareSPI constructs a SIPO connection that bit-bangs SER
// IN/SRCK plus RCK/SRCLR/G. Pass srclr=0 and/or g=0 to omit those pins.
// enPin may be nil if the device's EN line is not wired.
func NewSIPOSoftwareSPI(serIn, srck, rck, srclr, g machine.Pin, enPin OutputPin) *SIPOConnection {
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
	return &SIPOConnection{
		connectionBase: connectionBase{enPin: enPin},
		serIn:           serIn,
		srck:            srck,
		rck:             rck,
		srclr:           srclr,
		g:               g,
	}
}

// Write shifts data out MSB-first, then pulses RCK to latch. Dropped
// when disabled.
func (t *SIPOConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
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
func (t *SIPOConnection) Clear() error {
	if t.srclr == 0 {
		return errSipoNotConfigured
	}
	t.srclr.Low()
	t.srclr.High()
	return nil
}

// SetOutputEnable drives G LOW (true) or HIGH (false).
func (t *SIPOConnection) SetOutputEnable(en bool) error {
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
func (t *SIPOConnection) Close() error {
	return nil
}

var errSipoNotConfigured = &sipoError{"sipo: pin not configured"}

type sipoError struct{ msg string }

func (e *sipoError) Error() string { return e.msg }
