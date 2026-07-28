//go:build linux && !tinygo

// NeoPixelConnection is the Linux implementation of the NeoPixel
// write-only connection. It wraps the Go Linux SPI connection opened at
// 2.4 MHz, mode 0, and encodes each NeoPixel bit as three SPI bits to
// produce the timing that WS2812B / SK6812 LED strips expect.
package connection

// NeoPixelConnection is the Linux /dev/spidevB.D-backed implementation
// of the NeoPixel write-only connection. It holds an SPIConnection opened
// at 2.4 MHz, mode 0, MSB-first, and encodes each NeoPixel bit as
// three SPI bits: `100` for a 0 bit, `110` for a 1 bit. The
// resulting buffer is sent in a single SPI transfer followed by 16
// zero bytes for the reset.
type NeoPixelConnection struct {
	connectionBase
	spi *SPIConnection
}

// NewNeoPixelConnection opens /dev/spidevBUS.DEVICE at 2.4 MHz, mode 0,
// for NeoPixel bit-encoding. No CS pin is required for WS2812B/SK6812
// strips (CS is a dummy in the kernel's view but still toggled, which
// the strips ignore). enPin may be nil if the strip's EN line is not
// wired; there is no INT line for a write-only strip.
func NewNeoPixelConnection(busNum, deviceNum int, enPin OutputPin) (*NeoPixelConnection, error) {
	spi, err := NewSPIConnection(busNum, deviceNum, 2_400_000, nil, nil)
	if err != nil {
		return nil, err
	}
	return &NeoPixelConnection{connectionBase: connectionBase{enPin: enPin}, spi: spi}, nil
}

// Close releases the underlying SPI device.
func (n *NeoPixelConnection) Close() error {
	return n.spi.Close()
}

// Write encodes the buffer and transmits it to the LED strip in a
// single SPI transfer. Each NeoPixel byte is encoded as three SPI
// bytes; 16 trailing zero bytes produce the reset pulse. Writes are
// dropped when disabled.
func (n *NeoPixelConnection) Write(data []byte) error {
	if !n.IsEnabled() {
		return nil
	}
	encoded := encodeNeoPixel(data)
	return n.spi.Write(encoded)
}

// encodeNeoPixel converts a buffer of NeoPixel bytes into 3-bits-per-bit
// SPI bytes, with 16 trailing zero bytes for the reset. Each NeoPixel
// bit maps to 3 SPI bits at 2.4 MHz: `100` for a 0, `110` for a 1.
func encodeNeoPixel(data []byte) []byte {
	out := make([]byte, len(data)*3+16)
	for i, b := range data {
		var bits uint32
		for bit := 7; bit >= 0; bit-- {
			pattern := uint32(0b100)
			if (b>>bit)&1 == 1 {
				pattern = 0b110
			}
			bits = (bits << 3) | pattern
		}
		out[i*3+0] = byte(bits >> 16)
		out[i*3+1] = byte(bits >> 8)
		out[i*3+2] = byte(bits)
	}
	return out
}

// Read is a no-op for NeoPixel: strips are write-only. Provided to
// satisfy the Connection interface.
func (n *NeoPixelConnection) Read(count int) ([]byte, error) {
	return nil, nil
}

// WriteRead is a no-op for NeoPixel: strips are write-only. Provided
// to satisfy the Connection interface.
func (n *NeoPixelConnection) WriteRead(data []byte, count int) ([]byte, error) {
	return nil, nil
}
