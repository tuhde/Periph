//go:build linux && !tinygo

package connection

// NewDisplayConnectionHardwareSPI opens /dev/spidevBUS.DEVICE (SPI mode 0,
// MSB first, kernel-managed CS) for a DisplayConnection. dc is required;
// res and busy may be nil. WireModeHalfDuplexHW is rejected
// (ErrHalfDuplexHWUnsupported): spidev's 3-wire flag is not wired through
// this package — use WireModeHalfDuplexPin with the MOSI/MISO resistor.
func NewDisplayConnectionHardwareSPI(busNum, deviceNum int, maxSpeedHz uint32, dc, res OutputPin, busy InputPin, mode WireMode) (*DisplayConnection, error) {
	if mode == WireModeHalfDuplexHW {
		return nil, ErrHalfDuplexHWUnsupported
	}
	spi, err := NewSPIConnection(busNum, deviceNum, 0, maxSpeedHz, nil, nil)
	if err != nil {
		return nil, err
	}
	c, err := newDisplayConnection(spi, dc, res, busy, mode, spi.Close)
	if err != nil {
		_ = spi.Close()
		return nil, err
	}
	return c, nil
}
