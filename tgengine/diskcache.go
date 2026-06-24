package tgengine

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/gotd/td/tg"
)

// Disk cache (optional, flag-gated) — a small CIRCULAR write-ahead buffer on
// disk that sits between Telegram and the player.
//
// Why: on an ~80 Mb/s stream the per-account media throughput (~10 MB/s) barely
// matches the bitrate, so there is no headroom to build a look-ahead buffer in
// the player and any network jitter stalls playback. A continuously-running
// downloader that fills a disk ring AHEAD of the player's read position keeps
// the Telegram pipe hot and absorbs jitter, while staying OFF the Java heap so
// it works on cheap low-RAM boxes (Fire Stick etc.).
//
// Design: a single producer goroutine reuses the proven streamRange pipeline
// (conns-parallel, read-ahead, flood-wait/CDN handling) to fill a fixed-size
// circular file. The HTTP handler serves the player from that file. The
// producer never overwrites bytes the consumer has not yet read (readPos
// backpressure), so it naturally stays at most `cap` bytes ahead. A seek that
// lands outside the resident window repositions the producer to the new offset.
//
// The file is capped (50–250 MB, sized by bitrate), reused for one stream at a
// time, and deleted on stop / new stream / app start (orphan sweep) so it never
// saturates the device.

const (
	cacheSubdir = "tgstream-cache" // under the app cache dir; wiped on SetCacheDir
	cacheFile   = "active.cache"   // single backing file (one stream at a time)

	cacheTargetSeconds = 22.0           // look-ahead the ring aims to hold
	cacheMinBytes      = 50 << 20       // floor, regardless of bitrate
	cacheMaxBytes      = 250 << 20      // hard ceiling (device-friendly)
)

// errSeekAbort unwinds the producer's streamRange run when a seek or close is
// requested; it is never surfaced to the player.
var errSeekAbort = errors.New("diskcache: run aborted")

// errRingBusy is returned by serve when another consumer already owns the ring.
// The ring has a single producer/read position, so it can back exactly one
// continuous reader. Players issue concurrent range requests (e.g. an MKV tail
// probe for the Cues index while the head streams), and letting a second reader
// in would make the two fight over the single producer position (seek thrash).
// The caller serves the losing request directly from Telegram instead.
var errRingBusy = errors.New("diskcache: ring busy")

// durationOf returns the video duration (seconds) declared on a document, or 0.
func durationOf(doc *tg.Document) float64 {
	for _, a := range doc.Attributes {
		if v, ok := a.(*tg.DocumentAttributeVideo); ok {
			return v.Duration
		}
	}
	return 0
}

// cacheCapFor sizes the ring from the stream's average bitrate: enough bytes to
// hold ~cacheTargetSeconds of video, clamped to [cacheMinBytes, cacheMaxBytes]
// and rounded up to a whole chunk. Unknown duration → max (safest coverage).
func cacheCapFor(total int64, durationSec float64) int64 {
	if total <= 0 || durationSec <= 0 {
		return cacheMaxBytes
	}
	bytesPerSec := float64(total) / durationSec
	capBytes := int64(bytesPerSec * cacheTargetSeconds)
	if capBytes < cacheMinBytes {
		capBytes = cacheMinBytes
	}
	if capBytes > cacheMaxBytes {
		capBytes = cacheMaxBytes
	}
	capBytes = roundUp(capBytes, chunkSize)
	if capBytes > total {
		capBytes = roundUp(total, chunkSize)
	}
	return capBytes
}

// diskRing is a circular on-disk write-ahead buffer for one stream.
//
// Invariant: bytes [base, head) are present in the file (head-base <= cap), and
// the producer never advances head past readPos+cap (so [readPos, head) — the
// region the consumer may still read — is never overwritten).
type diskRing struct {
	e         *Engine
	path      string
	file      *os.File
	cap       int64
	total     int64
	conns     int
	channelID int64
	input     tg.InputChannelClass
	parts     []partInfo

	mu        sync.Mutex
	cond      *sync.Cond
	head      int64              // next byte to download; [base,head) is resident & contiguous
	base      int64              // oldest resident offset (= max(0, head-cap))
	readPos   int64              // consumer position; producer won't overwrite >= readPos
	seekReq   int64              // requested reposition offset, or -1
	curCancel context.CancelFunc // cancels the in-flight streamRange run
	active    bool               // a consumer currently owns serve() (single reader)
	closed    bool
	err       error
	done      chan struct{} // closed when run() returns
}

// newDiskRing creates the backing file, sized to cap, and starts the producer.
func newDiskRing(e *Engine, dir string, channelID int64, input tg.InputChannelClass,
	parts []partInfo, total, capBytes int64, conns int) (*diskRing, error) {

	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	path := filepath.Join(dir, cacheFile)
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, err
	}
	// Pre-size so circular WriteAt/ReadAt always land inside the file.
	if err := f.Truncate(capBytes); err != nil {
		f.Close()
		os.Remove(path)
		return nil, err
	}
	r := &diskRing{
		e:         e,
		path:      path,
		file:      f,
		cap:       capBytes,
		total:     total,
		conns:     conns,
		channelID: channelID,
		input:     input,
		parts:     parts,
		seekReq:   -1,
		done:      make(chan struct{}),
	}
	r.cond = sync.NewCond(&r.mu)
	logI("diskcache: ring %d MiB for %d-byte stream (conns=%d)", capBytes>>20, total, conns)
	go r.run()
	return r, nil
}

// close stops the producer, closes the file and deletes it.
func (r *diskRing) close() {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.closed = true
	if r.curCancel != nil {
		r.curCancel()
	}
	r.cond.Broadcast()
	r.mu.Unlock()

	<-r.done // wait for the producer to stop touching the file
	r.file.Close()
	os.Remove(r.path)
	logI("diskcache: ring closed and removed")
}

// run is the single producer goroutine. It (re)runs the streamRange pipeline
// from the current head to EOF, writing emitted bytes into the ring via
// ringWriter. A seek aborts the current run and restarts from the new offset.
func (r *diskRing) run() {
	defer close(r.done)
	for {
		r.mu.Lock()
		for !r.closed && r.seekReq < 0 && r.head >= r.total {
			r.cond.Wait() // fully downloaded: idle until a seek or close
		}
		if r.closed {
			r.mu.Unlock()
			return
		}
		if r.seekReq >= 0 {
			r.repositionLocked(r.seekReq)
			r.seekReq = -1
		}
		start := r.head
		runCtx, cancel := context.WithCancel(r.e.dlCtx)
		r.curCancel = cancel
		r.mu.Unlock()

		w := &ringWriter{r: r, off: start}
		err := r.e.streamRange(runCtx, w, r.channelID, r.input, r.parts, start, r.total-1, r.conns)
		cancel()

		r.mu.Lock()
		r.curCancel = nil
		aborted := r.closed || r.seekReq >= 0 || runCtx.Err() != nil || errors.Is(err, errSeekAbort)
		if err != nil && !aborted {
			r.err = err
			r.cond.Broadcast()
			r.mu.Unlock()
			logW("diskcache: producer error: %v", err)
			return
		}
		r.cond.Broadcast()
		r.mu.Unlock()
	}
}

// repositionLocked empties the resident window and points the producer at a.
func (r *diskRing) repositionLocked(a int64) {
	if a < 0 {
		a = 0
	}
	r.head = a
	r.base = a
	r.readPos = a
}

// ringWriter is the streamRange sink: it appends sequentially into the ring.
type ringWriter struct {
	r   *diskRing
	off int64
}

func (w *ringWriter) Write(p []byte) (int, error) {
	if err := w.r.put(w.off, p); err != nil {
		return 0, err
	}
	w.off += int64(len(p))
	return len(p), nil
}

// put writes data at absolute offset off (== current head), blocking until the
// ring has room (i.e. the consumer has read far enough). Returns errSeekAbort if
// a seek/close is requested while waiting so the streamRange run unwinds.
func (r *diskRing) put(off int64, data []byte) error {
	r.mu.Lock()
	for {
		if r.closed || r.seekReq >= 0 {
			r.mu.Unlock()
			return errSeekAbort
		}
		// Keep [readPos, off+len) within cap so we never clobber unread bytes.
		if off+int64(len(data))-r.readPos <= r.cap {
			break
		}
		r.cond.Wait()
	}
	r.mu.Unlock()

	// Write outside the lock: [off, off+len) is >= head, so the consumer (which
	// reads strictly below head) never touches it concurrently.
	if err := r.writeAt(off, data); err != nil {
		return err
	}

	r.mu.Lock()
	r.head = off + int64(len(data))
	if r.head-r.cap > r.base {
		r.base = r.head - r.cap
	}
	r.cond.Broadcast()
	r.mu.Unlock()
	return nil
}

// serve streams logical bytes [a,b] to w from the ring, repositioning the
// producer on an out-of-window seek and waiting for the producer to fill ahead.
//
// The ring backs a single continuous reader: it owns one producer position. If a
// reader is already active, serve returns errRingBusy so the caller streams this
// (concurrent) request directly from Telegram instead of letting it hijack the
// shared producer — otherwise the two readers seek-thrash and playback stalls.
func (r *diskRing) serve(ctx context.Context, w io.Writer, a, b int64) error {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil
	}
	if r.active {
		r.mu.Unlock()
		return errRingBusy
	}
	r.active = true
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		r.active = false
		r.cond.Broadcast()
		r.mu.Unlock()
	}()

	r.mu.Lock()
	if a < r.base || a > r.head {
		r.requestSeekLocked(a) // out of resident window → reposition producer
	}
	r.readPos = a
	r.cond.Broadcast()
	r.mu.Unlock()

	buf := make([]byte, chunkSize)
	off := a
	for off <= b {
		r.mu.Lock()
		for r.head <= off && r.err == nil && !r.closed {
			r.cond.Wait()
		}
		if r.err != nil {
			err := r.err
			r.mu.Unlock()
			return err
		}
		if r.closed {
			r.mu.Unlock()
			return nil
		}
		if off < r.base {
			// Evicted under us (should not happen given backpressure): reposition.
			r.requestSeekLocked(off)
			r.readPos = off
			r.cond.Broadcast()
			r.mu.Unlock()
			continue
		}
		avail := minI64(r.head-1, b)
		r.mu.Unlock()

		n := avail - off + 1
		if n > int64(len(buf)) {
			n = int64(len(buf))
		}
		if err := r.readInto(off, buf[:n]); err != nil {
			return err
		}
		if _, err := w.Write(buf[:n]); err != nil {
			return err // client disconnected (normal on seek/stop)
		}
		off += n

		r.mu.Lock()
		r.readPos = off
		r.cond.Broadcast()
		r.mu.Unlock()

		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
	}
	return nil
}

// requestSeekLocked asks the producer to reposition to a and waits (releasing
// r.mu via cond.Wait) until a falls inside the resident window again. Caller
// holds r.mu.
func (r *diskRing) requestSeekLocked(a int64) {
	r.seekReq = a
	if r.curCancel != nil {
		r.curCancel() // abort the in-flight run so the producer restarts at a
	}
	r.cond.Broadcast()
	for !r.closed && r.err == nil && !(r.base <= a && a <= r.head) {
		r.cond.Wait()
	}
}

// writeAt writes data into the circular file at the slot for absolute offset
// off, splitting across the wrap boundary when needed.
func (r *diskRing) writeAt(off int64, data []byte) error {
	pos := off % r.cap
	if pos+int64(len(data)) <= r.cap {
		_, err := r.file.WriteAt(data, pos)
		return err
	}
	first := r.cap - pos
	if _, err := r.file.WriteAt(data[:first], pos); err != nil {
		return err
	}
	_, err := r.file.WriteAt(data[first:], 0)
	return err
}

// readInto reads len(buf) bytes for absolute offset off from the circular file,
// splitting across the wrap boundary when needed.
func (r *diskRing) readInto(off int64, buf []byte) error {
	pos := off % r.cap
	if pos+int64(len(buf)) <= r.cap {
		_, err := r.file.ReadAt(buf, pos)
		return err
	}
	first := r.cap - pos
	if _, err := r.file.ReadAt(buf[:first], pos); err != nil {
		return err
	}
	_, err := r.file.ReadAt(buf[first:], 0)
	return err
}
