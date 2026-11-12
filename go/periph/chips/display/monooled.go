package display

import (
	"errors"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Shared building blocks of the monochrome page-organised OLED controller drivers (SSD130x style): a page-major
// 1 bpp framebuffer with drawing primitives and the two transports (control-byte framed I2C, D/C-pin framed
// 4-wire SPI).

// Transfer limits of the monochrome OLED transports.
const (
	// MonoOLEDI2CChunk is the largest I2C write in bytes including the control byte (the 32-byte limit of some I2C layers).
	MonoOLEDI2CChunk = 32
	// MonoOLEDSPIChunk is the largest data write in bytes on the SPI transport (one page).
	MonoOLEDSPIChunk = 128
)

// ErrMonoOLEDArgument is returned for a size, position or buffer argument that is out of range.
var ErrMonoOLEDArgument = errors.New("monooled: argument out of range")

// monoOLEDBus is the transport of the monochrome OLED drivers.
type monoOLEDBus interface {
	writeCommands(b []byte) error
	writeData(b []byte) error
	hardwareReset() error
}

// monoOLEDI2C is the I2C transport: every transaction starts with a control byte (0x00 commands, 0x40 RAM data).
type monoOLEDI2C struct{ conn connection.Connection }

func (t *monoOLEDI2C) writeFramed(control byte, b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > MonoOLEDI2CChunk-1 {
			n = MonoOLEDI2CChunk - 1
		}
		if err := t.conn.Write(append([]byte{control}, b[:n]...)); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *monoOLEDI2C) writeCommands(b []byte) error { return t.writeFramed(0x00, b) }
func (t *monoOLEDI2C) writeData(b []byte) error     { return t.writeFramed(0x40, b) }
func (t *monoOLEDI2C) hardwareReset() error         { return nil }

// monoOLEDSPI is the 4-wire SPI transport: D/C low for every command byte, high for RAM data.
type monoOLEDSPI struct{ conn connection.DisplayConn }

func (t *monoOLEDSPI) writeCommands(b []byte) error {
	for _, c := range b {
		if err := t.conn.SendCommand(c, nil); err != nil {
			return err
		}
	}
	return nil
}

func (t *monoOLEDSPI) writeData(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > MonoOLEDSPIChunk {
			n = MonoOLEDSPIChunk
		}
		if err := t.conn.SendData(b[:n]); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *monoOLEDSPI) hardwareReset() error { return t.conn.Reset() }

// monoFramebuffer is a page-major monochrome framebuffer (bit 0 of a byte = top pixel of its page) with
// drawing primitives.
type monoFramebuffer struct {
	width, height, pages int
	data                 []byte
}

func newMonoFramebuffer(width, height int) *monoFramebuffer {
	return &monoFramebuffer{width: width, height: height, pages: height / 8, data: make([]byte, width*(height/8))}
}

func (f *monoFramebuffer) page(p int) []byte { return f.data[p*f.width : (p+1)*f.width] }

// transposed returns the framebuffer column-major (for vertical addressing).
func (f *monoFramebuffer) transposed() []byte {
	out := make([]byte, 0, len(f.data))
	for x := 0; x < f.width; x++ {
		for p := 0; p < f.pages; p++ {
			out = append(out, f.data[p*f.width+x])
		}
	}
	return out
}

func (f *monoFramebuffer) fill(on bool) {
	var v byte
	if on {
		v = 0xFF
	}
	for i := range f.data {
		f.data[i] = v
	}
}

func (f *monoFramebuffer) setPixel(x, y int, on bool) {
	if x < 0 || y < 0 || x >= f.width || y >= f.height {
		return
	}
	i := (y>>3)*f.width + x
	if on {
		f.data[i] |= 1 << uint(y&7)
	} else {
		f.data[i] &^= 1 << uint(y&7)
	}
}

func (f *monoFramebuffer) getPixel(x, y int) bool {
	if x < 0 || y < 0 || x >= f.width || y >= f.height {
		return false
	}
	return f.data[(y>>3)*f.width+x]&(1<<uint(y&7)) != 0
}

func monoAbs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func (f *monoFramebuffer) drawLine(x0, y0, x1, y1 int, on bool) {
	dx := monoAbs(x1 - x0)
	dy := -monoAbs(y1 - y0)
	sx, sy := -1, -1
	if x0 < x1 {
		sx = 1
	}
	if y0 < y1 {
		sy = 1
	}
	err := dx + dy
	for {
		f.setPixel(x0, y0, on)
		if x0 == x1 && y0 == y1 {
			break
		}
		e2 := 2 * err
		if e2 >= dy {
			err += dy
			x0 += sx
		}
		if e2 <= dx {
			err += dx
			y0 += sy
		}
	}
}

func (f *monoFramebuffer) drawRect(x, y, w, h int, on, fill bool) {
	if w <= 0 || h <= 0 {
		return
	}
	if fill {
		for yy := y; yy < y+h; yy++ {
			for xx := x; xx < x+w; xx++ {
				f.setPixel(xx, yy, on)
			}
		}
		return
	}
	f.drawLine(x, y, x+w-1, y, on)
	f.drawLine(x, y+h-1, x+w-1, y+h-1, on)
	f.drawLine(x, y, x, y+h-1, on)
	f.drawLine(x+w-1, y, x+w-1, y+h-1, on)
}

// blit copies a column-major bitmap into the framebuffer (clipped); y must be a multiple of 8.
func (f *monoFramebuffer) blit(x, y int, columns []byte, heightPages int) error {
	if y%8 != 0 || heightPages <= 0 {
		return ErrMonoOLEDArgument
	}
	n := len(columns) / heightPages
	for p := 0; p < heightPages; p++ {
		page := y/8 + p
		if page < 0 || page >= f.pages {
			continue
		}
		for c := 0; c < n; c++ {
			xx := x + c
			if xx >= 0 && xx < f.width {
				f.data[page*f.width+xx] = columns[p*n+c]
			}
		}
	}
	return nil
}
