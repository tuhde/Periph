//go:build linux && !tinygo

// SIPOConnection is the Linux implementation of the serial-shift-register
// (SIPO) connection for chips like TPIC6B595 / 74HC595.
package connection

import "fmt"

// SIPOConnection drives SER IN/SRCK plus RCK/SRCLR/G, either by
// bit-banging all of them as raw GPIO lines on /dev/gpiochip0
// (NewSIPOSoftwareSPI) or by driving SER IN/SRCK over hardware
// /dev/spidevB.D with RCK/SRCLR/G still as GPIO lines
// (NewSIPOHardwareSPI). It does not implement the Connection interface
// — SIPO is write-only with no byte-oriented read/writeRead — but
// embeds connectionBase for the shared Enable/Disable/IsEnabled/EnPin
// gating. There is no INT line, so IntPin is always nil.
type SIPOConnection struct {
	connectionBase
	// Software SPI (bit-bang): serIn/srck/rck/srclr/g GPIO line offsets.
	serIn int
	srck  int
	rck   int
	srclr int // -1 = unused
	g     int // -1 = unused
	// Hardware SPI (optional): busNum/deviceNum > -1.
	spi      *SPIConnection
	hasHwSpi bool
}

// NewSIPOSoftwareSPI constructs a SIPO connection that bit-bangs SER
// IN/SRCK plus RCK/SRCLR/G as GPIO lines on /dev/gpiochip0. srclr and g
// may be passed as -1 if the chip does not wire those pins. enPin may be
// nil if the device's EN line is not wired.
func NewSIPOSoftwareSPI(serIn, srck, rck, srclr, g int, enPin OutputPin) (*SIPOConnection, error) {
	if err := requestGpioLines(serIn, srck, rck, srclr, g); err != nil {
		return nil, err
	}
	if err := setGpioLine(serIn, false); err != nil {
		return nil, err
	}
	if err := setGpioLine(srck, false); err != nil {
		return nil, err
	}
	if err := setGpioLine(rck, false); err != nil {
		return nil, err
	}
	if srclr >= 0 {
		if err := setGpioLine(srclr, true); err != nil {
			return nil, err
		}
	}
	if g >= 0 {
		if err := setGpioLine(g, false); err != nil {
			return nil, err
		}
	}
	return &SIPOConnection{
		connectionBase: connectionBase{enPin: enPin},
		serIn:           serIn,
		srck:            srck,
		rck:             rck,
		srclr:           srclr,
		g:               g,
	}, nil
}

// NewSIPOHardwareSPI constructs a SIPO connection that drives SER IN/SRCK
// over hardware /dev/spidevBUS.DEVICE (SPI mode 0, per the SIPO transport
// spec) and bit-bangs RCK/SRCLR/G as GPIO lines on /dev/gpiochip0. srclr
// and g may be passed as -1 if the chip does not wire those pins. enPin
// may be nil if the device's EN line is not wired.
func NewSIPOHardwareSPI(busNum, deviceNum int, maxSpeedHz uint32, rck, srclr, g int, enPin OutputPin) (*SIPOConnection, error) {
	spi, err := NewSPIConnection(busNum, deviceNum, 0, maxSpeedHz, nil, nil) // SPI mode 0 per specs/transport_sipo.md
	if err != nil {
		return nil, err
	}
	if err := requestGpioLines(rck, srclr, g); err != nil {
		_ = spi.Close()
		return nil, err
	}
	if err := setGpioLine(rck, false); err != nil {
		_ = spi.Close()
		return nil, err
	}
	if srclr >= 0 {
		if err := setGpioLine(srclr, true); err != nil {
			_ = spi.Close()
			return nil, err
		}
	}
	if g >= 0 {
		if err := setGpioLine(g, false); err != nil {
			_ = spi.Close()
			return nil, err
		}
	}
	return &SIPOConnection{
		connectionBase: connectionBase{enPin: enPin},
		serIn:           -1,
		srck:            -1,
		rck:             rck,
		srclr:           srclr,
		g:               g,
		spi:             spi,
		hasHwSpi:        true,
	}, nil
}

// Write shifts data out MSB-first, then pulses RCK to latch. Dropped
// when disabled.
func (t *SIPOConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
	if t.hasHwSpi {
		if err := t.spi.Write(data); err != nil {
			return err
		}
	} else {
		for _, b := range data {
			for bit := 7; bit >= 0; bit-- {
				v := (b>>bit)&1 == 1
				if err := setGpioLine(t.serIn, v); err != nil {
					return err
				}
				if err := setGpioLine(t.srck, true); err != nil {
					return err
				}
				if err := setGpioLine(t.srck, false); err != nil {
					return err
				}
			}
		}
	}
	// Pulse RCK HIGH then LOW to latch.
	if err := setGpioLine(t.rck, true); err != nil {
		return err
	}
	if err := setGpioLine(t.rck, false); err != nil {
		return err
	}
	return nil
}

// Clear pulses SRCLR LOW then HIGH.
func (t *SIPOConnection) Clear() error {
	if t.srclr < 0 {
		return fmt.Errorf("sipo: srclr not configured")
	}
	if err := setGpioLine(t.srclr, false); err != nil {
		return err
	}
	return setGpioLine(t.srclr, true)
}

// SetOutputEnable drives G LOW (true) or HIGH (false).
func (t *SIPOConnection) SetOutputEnable(en bool) error {
	if t.g < 0 {
		return fmt.Errorf("sipo: g not configured")
	}
	return setGpioLine(t.g, !en)
}

// Close releases the GPIO lines and, in hardware SPI mode, the underlying
// /dev/spidevB.D file descriptor.
func (t *SIPOConnection) Close() error {
	if t.hasHwSpi {
		if err := t.spi.Close(); err != nil {
			return err
		}
	}
	return releaseGpioLines(t.serIn, t.srck, t.rck, t.srclr, t.g)
}
