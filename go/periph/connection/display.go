// DisplayConnection: SPI + D/C# + optional RES# + optional BUSY for display
// controllers (OLED: SH1106; e-paper: UC8276C, SSD1683). No build tag — the
// platform-specific constructors live in display_linux.go and
// display_tinygo.go. See specs/transport_display.md.
package connection

import (
	"errors"
	"time"
)

// WireMode selects how the controller's data pin is wired to the host.
type WireMode int

const (
	// WireModeSimplex: MOSI -> DIN, write only; ReadData returns an error.
	WireModeSimplex WireMode = iota
	// WireModeHalfDuplexHW: DIN/DOUT shared with MOSI, read through the SPI
	// peripheral's hardware 3-wire mode. Not available on Go targets.
	WireModeHalfDuplexHW
	// WireModeHalfDuplexPin: MOSI -- resistor -- MISO joined at DIN/DOUT,
	// read through a full-duplex (hardware or software) SPI master.
	WireModeHalfDuplexPin
)

var (
	// ErrNoMISO is returned when a read is requested from a SoftSPI that has no MISO pin.
	ErrNoMISO = errors.New("display: software SPI has no MISO pin")
	// ErrReadUnavailable is returned by ReadData in WireModeSimplex.
	ErrReadUnavailable = errors.New("display: ReadData is not available in simplex wire mode")
	// ErrHalfDuplexHWUnsupported is returned when WireModeHalfDuplexHW is
	// requested on a Go target (neither spidev nor machine.SPI exposes 3-wire mode).
	ErrHalfDuplexHWUnsupported = errors.New("display: hardware half-duplex is not supported on this platform; use WireModeHalfDuplexPin")
)

// DisplayConn is the interface chip drivers accept: the real
// DisplayConnection and DisplayConnectionMock both implement it. Chip
// drivers never touch D/C, RES, BUSY or CS themselves.
type DisplayConn interface {
	// Reset pulses RES# low then waits for the controller; it does not wait for BUSY.
	Reset() error
	// BusyLevel returns the raw BUSY level (false when no BUSY pin is wired).
	BusyLevel() (bool, error)
	// WaitBusy polls BUSY until it is no longer active. busyActiveHigh is the
	// controller's polarity (chip-level knowledge). A timeout <= 0 waits
	// forever. It returns true when the controller became ready (or no BUSY
	// pin exists) and false on timeout.
	WaitBusy(busyActiveHigh bool, timeout time.Duration) (bool, error)
	// SendCommand sends cmd with D/C# low, then data (if any) with D/C# high,
	// as separate SPI transfers.
	SendCommand(cmd byte, data []byte) error
	// SendData sends data with D/C# high as one SPI transfer.
	SendData(data []byte) error
	// ReadData reads n bytes (D/C# high), one CS window per byte; an error in
	// WireModeSimplex.
	ReadData(n int) ([]byte, error)
	Enable()
	Disable()
	IsEnabled() bool
	Close() error
}

// displaySPI is the transfer primitive underneath: *SPIConnection (hardware
// SPI, CS handled by the kernel / SPIConnection) and *softDisplaySPI
// (software SPI + a CS pin) both satisfy it. Each call is one CS window.
type displaySPI interface {
	Write(data []byte) error
	Read(n int) ([]byte, error)
}

// softDisplaySPI frames SoftSPI transfers with an active-low CS pin.
type softDisplaySPI struct {
	spi *SoftSPI
	cs  OutputPin
}

func (s *softDisplaySPI) Write(data []byte) error {
	if err := s.cs.Set(false); err != nil {
		return err
	}
	werr := s.spi.Write(data)
	if err := s.cs.Set(true); err != nil && werr == nil {
		return err
	}
	return werr
}

func (s *softDisplaySPI) Read(n int) ([]byte, error) {
	if err := s.cs.Set(false); err != nil {
		return nil, err
	}
	out, rerr := s.spi.Read(n, 0x00)
	if err := s.cs.Set(true); err != nil && rerr == nil {
		return nil, err
	}
	return out, rerr
}

// DisplayConnection implements DisplayConn.
//
// CS is hardware CS only: every SendCommand/SendData transfer and every byte
// of ReadData is its own CS window. RES# and BUSY are optional (nil). The
// BUSY polarity is chip-level: WaitBusy takes it as an argument.
type DisplayConnection struct {
	connectionBase
	spi         displaySPI
	dc          OutputPin
	res         OutputPin // nil if not wired
	busy        InputPin  // nil if not wired
	wireMode    WireMode
	resetPulse  time.Duration
	resetSettle time.Duration
	closeFn     func() error
}

func newDisplayConnection(spi displaySPI, dc, res OutputPin, busy InputPin, mode WireMode, closeFn func() error) (*DisplayConnection, error) {
	if dc == nil {
		return nil, errors.New("display: dc pin is required")
	}
	if mode == WireModeHalfDuplexHW {
		return nil, ErrHalfDuplexHWUnsupported
	}
	if mode != WireModeSimplex && mode != WireModeHalfDuplexPin {
		return nil, errors.New("display: unknown wire mode")
	}
	if err := dc.Set(false); err != nil {
		return nil, err
	}
	if res != nil {
		if err := res.Set(true); err != nil {
			return nil, err
		}
	}
	return &DisplayConnection{
		spi:         spi,
		dc:          dc,
		res:         res,
		busy:        busy,
		wireMode:    mode,
		resetPulse:  200 * time.Microsecond,
		resetSettle: 10 * time.Millisecond,
		closeFn:     closeFn,
	}, nil
}

// NewDisplayConnectionSoftSPI builds a connection that bit-bangs SCK/MOSI
// (and MISO for WireModeHalfDuplexPin) and drives cs (active low) itself.
// res and busy may be nil; miso may be nil in WireModeSimplex. Works on
// every Go target.
func NewDisplayConnectionSoftSPI(sck, mosi OutputPin, miso InputPin, cs, dc, res OutputPin, busy InputPin, mode WireMode) (*DisplayConnection, error) {
	if cs == nil {
		return nil, errors.New("display: software SPI needs a cs pin")
	}
	if mode == WireModeHalfDuplexPin && miso == nil {
		return nil, errors.New("display: WireModeHalfDuplexPin needs a miso pin")
	}
	spi, err := NewSoftSPI(sck, mosi, miso)
	if err != nil {
		return nil, err
	}
	if err := cs.Set(true); err != nil {
		return nil, err
	}
	return newDisplayConnection(&softDisplaySPI{spi: spi, cs: cs}, dc, res, busy, mode, nil)
}

// SetResetTiming overrides the RES# low pulse width and the settle time
// after release (defaults 200 µs and 10 ms).
func (c *DisplayConnection) SetResetTiming(pulse, settle time.Duration) {
	c.resetPulse = pulse
	c.resetSettle = settle
}

// Reset pulses RES# low then high and waits for the settle time. It does
// not wait for BUSY; the chip driver calls WaitBusy afterwards. No-op when
// disabled or RES# is not wired.
func (c *DisplayConnection) Reset() error {
	if !c.IsEnabled() || c.res == nil {
		return nil
	}
	if err := c.res.Set(false); err != nil {
		return err
	}
	time.Sleep(c.resetPulse)
	if err := c.res.Set(true); err != nil {
		return err
	}
	time.Sleep(c.resetSettle)
	return nil
}

// BusyLevel returns the raw BUSY level; false when no BUSY pin is wired.
func (c *DisplayConnection) BusyLevel() (bool, error) {
	if c.busy == nil {
		return false, nil
	}
	return c.busy.IsHigh()
}

// WaitBusy polls BUSY every millisecond until it is no longer active. See
// DisplayConn.WaitBusy.
func (c *DisplayConnection) WaitBusy(busyActiveHigh bool, timeout time.Duration) (bool, error) {
	if c.busy == nil {
		return true, nil
	}
	start := time.Now()
	for {
		high, err := c.busy.IsHigh()
		if err != nil {
			return false, err
		}
		if high != busyActiveHigh {
			return true, nil
		}
		if timeout > 0 && time.Since(start) >= timeout {
			return false, nil
		}
		time.Sleep(time.Millisecond)
	}
}

// SendCommand sends the command byte (D/C# low) and then the parameter
// bytes (D/C# high) as separate transfers. No-op when disabled.
func (c *DisplayConnection) SendCommand(cmd byte, data []byte) error {
	if !c.IsEnabled() {
		return nil
	}
	if err := c.dc.Set(false); err != nil {
		return err
	}
	if err := c.spi.Write([]byte{cmd}); err != nil {
		return err
	}
	if len(data) == 0 {
		return nil
	}
	if err := c.dc.Set(true); err != nil {
		return err
	}
	return c.spi.Write(data)
}

// SendData sends data with D/C# high as one transfer. No-op when disabled.
func (c *DisplayConnection) SendData(data []byte) error {
	if !c.IsEnabled() || len(data) == 0 {
		return nil
	}
	if err := c.dc.Set(true); err != nil {
		return err
	}
	return c.spi.Write(data)
}

// ReadData reads n bytes with D/C# high, one CS window per byte. It returns
// ErrReadUnavailable in WireModeSimplex and zeros when disabled.
func (c *DisplayConnection) ReadData(n int) ([]byte, error) {
	if c.wireMode == WireModeSimplex {
		return nil, ErrReadUnavailable
	}
	if !c.IsEnabled() {
		return make([]byte, n), nil
	}
	if err := c.dc.Set(true); err != nil {
		return nil, err
	}
	out := make([]byte, 0, n)
	for i := 0; i < n; i++ {
		b, err := c.spi.Read(1)
		if err != nil {
			return nil, err
		}
		out = append(out, b[0])
	}
	return out, nil
}

// Close releases the hardware SPI device if this connection opened one.
func (c *DisplayConnection) Close() error {
	if c.closeFn != nil {
		return c.closeFn()
	}
	return nil
}
