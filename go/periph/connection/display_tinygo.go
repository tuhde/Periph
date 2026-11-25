//go:build tinygo

package connection

import "machine"

// NewDisplayConnectionHardwareSPI builds a DisplayConnection over an already
// configured machine.SPI (mode 0, MSB first) with cs as the active-low chip
// select driven around each transfer. dc is required; res and busy may be
// nil. WireModeHalfDuplexHW is rejected (ErrHalfDuplexHWUnsupported):
// machine.SPI has no 3-wire mode — use WireModeHalfDuplexPin with the
// MOSI/MISO resistor.
func NewDisplayConnectionHardwareSPI(spi *machine.SPI, cs machine.Pin, dc, res OutputPin, busy InputPin, mode WireMode) (*DisplayConnection, error) {
	if mode == WireModeHalfDuplexHW {
		return nil, ErrHalfDuplexHWUnsupported
	}
	cs.Configure(machine.PinConfig{Mode: machine.PinOutput})
	cs.High()
	return newDisplayConnection(NewSPIConnection(spi, cs, nil, nil), dc, res, busy, mode, nil)
}
