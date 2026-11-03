// SoftSPI is a bit-banged SPI master over the OutputPin / InputPin
// abstractions. No build tag: it needs only GPIO, so it builds identically
// on Linux and TinyGo.
package connection

// SoftSPI is a bit-banged SPI master (mode 0, MSB first).
//
// It is the software side of the DisplayConnection: the platform's hardware
// SPI is used when the bus pins are on a controller, SoftSPI when they are
// arbitrary GPIOs. Chip select is not part of the bus object; the
// connection drives CS itself.
//
// Reading needs the optional MISO input. For a display controller whose DIN
// and DOUT share one pin, MISO is wired to that pin and MOSI is joined to it
// through a series resistor, so the host keeps driving MOSI (dummy bytes)
// while the chip drives the line and wins — no direction switch is needed.
type SoftSPI struct {
	sck  OutputPin
	mosi OutputPin
	miso InputPin // nil for a write-only bus
}

// NewSoftSPI parks the clock low (mode 0 idle). miso may be nil for a
// write-only bus.
func NewSoftSPI(sck, mosi OutputPin, miso InputPin) (*SoftSPI, error) {
	if err := sck.Set(false); err != nil {
		return nil, err
	}
	return &SoftSPI{sck: sck, mosi: mosi, miso: miso}, nil
}

// CanRead reports whether a MISO pin was given.
func (s *SoftSPI) CanRead() bool { return s.miso != nil }

// Write shifts data out MSB-first, SPI mode 0.
func (s *SoftSPI) Write(data []byte) error {
	for _, b := range data {
		for bit := 7; bit >= 0; bit-- {
			if err := s.mosi.Set((b>>uint(bit))&1 == 1); err != nil {
				return err
			}
			if err := s.sck.Set(true); err != nil {
				return err
			}
			if err := s.sck.Set(false); err != nil {
				return err
			}
		}
	}
	return nil
}

// Read clocks in n bytes from MISO while sending fill on MOSI during every
// clocked byte. It returns an error if no MISO pin was given.
func (s *SoftSPI) Read(n int, fill byte) ([]byte, error) {
	if s.miso == nil {
		return nil, ErrNoMISO
	}
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		var v byte
		for bit := 7; bit >= 0; bit-- {
			if err := s.mosi.Set((fill>>uint(bit))&1 == 1); err != nil {
				return nil, err
			}
			if err := s.sck.Set(true); err != nil {
				return nil, err
			}
			high, err := s.miso.IsHigh()
			if err != nil {
				return nil, err
			}
			v <<= 1
			if high {
				v |= 1
			}
			if err := s.sck.Set(false); err != nil {
				return nil, err
			}
		}
		out[i] = v
	}
	return out, nil
}
