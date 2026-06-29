package tgengine

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gotd/td/telegram"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
)

const (
	chunkSize   = 1024 * 1024 // 1 MiB download chunk (aligned to GetFile boundaries)
	maxPoolSize = 16          // upper bound of connections per DC pool

	// buildTag is stamped on every stream request so the log unambiguously
	// identifies the running engine build. Bump it on every engine change.
	buildTag = "media-6"
)

// proxyServer is the localhost HTTP server ExoPlayer streams from.
type proxyServer struct {
	e    *Engine
	ln   net.Listener
	srv  *http.Server
	port int
}

// StartProxy starts the local HTTP proxy and returns the port it listens on.
func (e *Engine) StartProxy() (int, error) {
	if e.proxy != nil {
		return e.proxy.port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	mux := http.NewServeMux()
	p := &proxyServer{e: e, ln: ln, port: ln.Addr().(*net.TCPAddr).Port}
	mux.HandleFunc("/stream", p.handleStream)
	p.srv = &http.Server{Handler: mux}
	go func() { _ = p.srv.Serve(ln) }()
	e.proxy = p
	return p.port, nil
}

func (p *proxyServer) stop() {
	if p.srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = p.srv.Shutdown(ctx)
	}
}

// partInfo describes one Telegram message (part) of the logical file.
type partInfo struct {
	msgID       int
	doc         *tg.Document
	size        int64
	globalStart int64 // byte offset of this part within the logical file
}

// handleStream serves GET/HEAD /stream?channel=<id>&messages=<m1-m2-...>&conns=<n>
// with HTTP range support, streaming bytes from Telegram.
func (p *proxyServer) handleStream(w http.ResponseWriter, r *http.Request) {
	e := p.e
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !e.IsReady() {
		logW("stream req rejected: engine not ready")
		http.Error(w, "engine not ready", http.StatusServiceUnavailable)
		return
	}
	q := r.URL.Query()
	channelID, err := strconv.ParseInt(q.Get("channel"), 10, 64)
	if err != nil {
		http.Error(w, "bad channel", http.StatusBadRequest)
		return
	}
	msgIDs, err := parseMessageIDs(q.Get("messages"))
	if err != nil || len(msgIDs) == 0 {
		http.Error(w, "bad messages", http.StatusBadRequest)
		return
	}
	// The conns query param is the user's MAXIMUM. The actual number of
	// connections is picked from the file size below (pickConns), so a small file
	// never opens more than it needs and only a big one scales up to this cap.
	maxConns := atoiDefault(q.Get("conns"), 1)
	if maxConns < 1 {
		maxConns = 1
	}
	if maxConns > maxPoolSize {
		maxConns = maxPoolSize
	}

	logI("stream req [build=%s]: channel=%d msgs=%v maxConns=%d range=%q method=%s",
		buildTag, channelID, msgIDs, maxConns, r.Header.Get("Range"), r.Method)

	ctx := r.Context()
	input, err := e.resolveChannel(ctx, channelID)
	if err != nil {
		logE("resolveChannel %d failed: %v", channelID, err)
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}

	// Resolve all parts and compute the total logical size.
	parts := make([]partInfo, 0, len(msgIDs))
	var total int64
	for _, mid := range msgIDs {
		doc, err := e.fetchDocument(ctx, channelID, input, mid)
		if err != nil {
			logE("fetchDocument msg=%d failed: %v", mid, err)
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		logI("part msg=%d size=%d dc=%d mime=%s", mid, doc.Size, doc.DCID, doc.MimeType)
		parts = append(parts, partInfo{msgID: mid, doc: doc, size: doc.Size, globalStart: total})
		total += doc.Size
	}
	logI("resolved %d part(s), total=%d bytes", len(parts), total)

	// Scale connections to the file size (the only metadata we reliably have):
	// ~1 connection per connSizeStep, capped at the user's max. Small files stream
	// fine on a single connection; only large ones ramp up.
	conns := pickConns(total, maxConns)
	logI("conns: size=%.1f GiB -> %d (max=%d)", float64(total)/float64(1<<30), conns, maxConns)

	contentType := parts[0].doc.MimeType
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	start, end, isRange := parseRange(r.Header.Get("Range"), total)
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", contentType)
	if isRange {
		if start > end || start >= total {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", total))
			http.Error(w, "range not satisfiable", http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, total))
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.WriteHeader(http.StatusPartialContent)
	} else {
		start, end = 0, total-1
		w.Header().Set("Content-Length", strconv.FormatInt(total, 10))
		w.WriteHeader(http.StatusOK)
	}
	if r.Method == http.MethodHead {
		return
	}

	logI("serving bytes %d-%d (%d bytes) range=%v", start, end, end-start+1, isRange)

	if err := e.streamRange(ctx, w, channelID, input, parts, start, end, conns); err != nil {
		// Client disconnects are normal; just stop.
		logW("stream ended: %v", err)
	}
}

// chunkJob is a single GetFile request plus the slice of its result to emit.
type chunkJob struct {
	part       *partInfo
	offset     int64 // aligned offset within the part
	limit      int   // bytes to request
	sliceStart int   // first byte of result to emit
	sliceEnd   int   // last byte of result to emit (inclusive)
}

// streamRange writes the logical byte range [start,end] to w, fetching 1 MiB
// chunks with up to conns parallel GetFile calls and emitting them in order.
func (e *Engine) streamRange(ctx context.Context, w interface{ Write([]byte) (int, error) },
	channelID int64, input tg.InputChannelClass, parts []partInfo, start, end int64, conns int) error {

	// Build the ordered list of chunk jobs spanning all involved parts.
	var jobs []chunkJob
	for i := range parts {
		pt := &parts[i]
		pStart := pt.globalStart
		pEnd := pt.globalStart + pt.size - 1
		if pEnd < start || pStart > end {
			continue
		}
		localStart := maxI64(start, pStart) - pStart
		localEnd := minI64(end, pEnd) - pStart
		off := localStart - (localStart % chunkSize)
		for off <= localEnd {
			limit := int64(chunkSize)
			if off+limit > pt.size {
				limit = roundUp(pt.size-off, 4096)
			}
			emitFrom := maxI64(localStart, off) - off
			emitTo := minI64(localEnd, off+limit-1) - off
			jobs = append(jobs, chunkJob{
				part:       pt,
				offset:     off,
				limit:      int(limit),
				sliceStart: int(emitFrom),
				sliceEnd:   int(emitTo),
			})
			off += chunkSize
		}
	}

	// Pipeline with read-ahead: keep up to `conns` getChunk calls IN FLIGHT (sem),
	// but let the producer fetch a large WARM PREFETCH window ahead of the in-order
	// emit position. This is what absorbs bursty clients: ExoPlayer fills its
	// (heap-bounded) buffer, stops reading → TCP backpressure blocks our w.Write,
	// then drains and resumes. With only a tiny window the producer idles during
	// the pause and the Telegram fetch pipeline cold-restarts on resume (~2-3s gap
	// → stall). A big window (~64 MiB ≈ a few seconds of high-bitrate video) keeps
	// chunks ready and workers warm, so resume is instant and throughput uses the
	// full link instead of collapsing to ~bitrate. Concurrency stays bounded by
	// `conns`; only the buffered-ahead depth grows (native RAM, cheap).
	streamCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	// chunkSize is 1 MiB, so readAhead is also the warm-prefetch size in MiB.
	readAhead := 8 * conns
	if readAhead < 64 {
		readAhead = 64 // floor: cover the resume ramp even at low conns
	}
	if readAhead > 128 {
		readAhead = 128 // ceiling: bound native RAM per stream
	}
	resultChans := make(chan chan chunkResult, readAhead)

	// Producer: spawn workers bounded by `conns` in flight (sem), enqueueing
	// result channels in job order up to `readAhead` ahead of emission.
	go func() {
		defer close(resultChans)
		sem := make(chan struct{}, conns)
		for i := range jobs {
			select {
			case <-streamCtx.Done():
				return
			case sem <- struct{}{}:
			}
			rc := make(chan chunkResult, 1)
			select {
			case <-streamCtx.Done():
				<-sem
				return
			case resultChans <- rc:
			}
			go func(j chunkJob, rc chan chunkResult) {
				defer func() { <-sem }()
				data, err := e.getChunk(channelID, input, j.part, j.offset, j.limit, conns)
				if err != nil {
					rc <- chunkResult{err: err}
					return
				}
				lo, hi := j.sliceStart, j.sliceEnd
				if hi >= len(data) {
					hi = len(data) - 1
				}
				if lo > hi {
					rc <- chunkResult{}
					return
				}
				rc <- chunkResult{data: data[lo : hi+1]}
			}(jobs[i], rc)
		}
	}()

	// Consumer: emit in order. Returning cancels streamCtx (defer), unblocking
	// the producer and any in-flight getChunk calls.
	t0 := time.Now()
	var emitted int64
	nextLog := int64(32 << 20) // log throughput every 32 MiB
	for rc := range resultChans {
		res := <-rc
		if res.err != nil {
			return res.err
		}
		if len(res.data) == 0 {
			continue
		}
		if _, err := w.Write(res.data); err != nil {
			return err // client disconnected
		}
		emitted += int64(len(res.data))
		if emitted >= nextLog {
			if el := time.Since(t0).Seconds(); el > 0 {
				logI("download: %d MiB in %.1fs = %.1f MB/s (%.0f Mbps), conns=%d",
					emitted>>20, el, float64(emitted)/1e6/el, float64(emitted)*8/1e6/el, conns)
			}
			nextLog += 32 << 20
		}
	}
	return nil
}

// chunkResult carries one pipelined chunk's trimmed bytes or its error.
type chunkResult struct {
	data []byte
	err  error
}

// getChunk fetches one chunk from the document's DC using up to `conns`
// connections, handling flood-wait, expired file references and CDN redirects.
//
// The RPCs run on a context DETACHED from the HTTP request: ExoPlayer routinely
// closes/reopens connections (seek, buffer management), and if those
// cancellations reached the pooled gotd connections they'd tear the whole pool
// down and trigger an endless reconnect storm. The pipeline stops *issuing* new
// chunks when the request ends; the few already in flight finish on their own
// timeout instead of killing connections.
func (e *Engine) getChunk(channelID int64, input tg.InputChannelClass,
	part *partInfo, offset int64, limit, conns int) ([]byte, error) {

	// Engine-lived context (NOT the per-request one): cancelling a request on
	// seek/abandon must not cancel in-flight getFile RPCs and tear down pooled
	// media connections — that caused the conn-id churn spiral. The pipeline
	// already stops issuing new chunks when the request ends; the few in-flight
	// ones finish and are discarded.
	ctx := e.dlCtx

	media, err := e.mediaClient(ctx, part.doc.DCID, conns)
	if err != nil {
		return nil, err
	}
	loc := &tg.InputDocumentFileLocation{
		ID:            part.doc.ID,
		AccessHash:    part.doc.AccessHash,
		FileReference: part.doc.FileReference,
	}

	const maxAttempts = 6
	for attempt := 0; attempt < maxAttempts; attempt++ {
		res, err := media.UploadGetFile(ctx, &tg.UploadGetFileRequest{
			Precise:  true,
			Location: loc,
			Offset:   offset,
			Limit:    limit,
		})
		if err != nil {
			if d, ok := tgerr.AsFloodWait(err); ok {
				logW("flood wait %v on dc=%d offset=%d (attempt %d)", d, part.doc.DCID, offset, attempt)
				select {
				case <-time.After(d):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			if rpc, ok := tgerr.As(err); ok && strings.Contains(rpc.Type, "FILE_REFERENCE_EXPIRED") {
				logW("file reference expired msg=%d, refreshing", part.msgID)
				fresh, rerr := e.refreshDocument(ctx, channelID, input, part.msgID)
				if rerr != nil {
					return nil, rerr
				}
				part.doc = fresh
				loc.FileReference = fresh.FileReference
				loc.AccessHash = fresh.AccessHash
				continue
			}
			// Transient connection blip (pool reconnect, dead conn): brief
			// backoff and retry on another pooled connection.
			if attempt < maxAttempts-1 {
				logW("getFile dc=%d offset=%d transient: %v (retry %d)", part.doc.DCID, offset, err, attempt)
				select {
				case <-time.After(300 * time.Millisecond):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			logE("getFile dc=%d offset=%d limit=%d failed: %v", part.doc.DCID, offset, limit, err)
			return nil, err
		}

		switch f := res.(type) {
		case *tg.UploadFile:
			return f.Bytes, nil
		case *tg.UploadFileCDNRedirect:
			logI("CDN redirect to dc=%d offset=%d", f.DCID, offset)
			return e.getCDNChunk(ctx, f, offset, limit, conns)
		default:
			return nil, fmt.Errorf("unexpected getFile result %T", res)
		}
	}
	return nil, fmt.Errorf("getChunk: too many retries")
}

// getCDNChunk downloads and decrypts a chunk from a CDN DC.
// Ported from gotd's downloader CDN schema. Untested until a redirect occurs.
func (e *Engine) getCDNChunk(ctx context.Context, redirect *tg.UploadFileCDNRedirect,
	offset int64, limit, conns int) ([]byte, error) {

	cdn, err := e.mediaClient(ctx, redirect.DCID, conns)
	if err != nil {
		return nil, fmt.Errorf("cdn dc %d: %w", redirect.DCID, err)
	}
	for attempt := 0; attempt < 6; attempt++ {
		r, err := cdn.UploadGetCDNFile(ctx, &tg.UploadGetCDNFileRequest{
			FileToken: redirect.FileToken,
			Offset:    offset,
			Limit:     limit,
		})
		if err != nil {
			if d, ok := tgerr.AsFloodWait(err); ok {
				select {
				case <-time.After(d):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			return nil, err
		}
		switch f := r.(type) {
		case *tg.UploadCDNFile:
			return cdnDecrypt(redirect, f.Bytes, offset)
		case *tg.UploadCDNFileReuploadNeeded:
			// Ask the master DC to re-upload to CDN, then retry.
			if _, err := e.api.UploadReuploadCDNFile(ctx, &tg.UploadReuploadCDNFileRequest{
				FileToken:    redirect.FileToken,
				RequestToken: f.RequestToken,
			}); err != nil {
				return nil, err
			}
			continue
		default:
			return nil, fmt.Errorf("unexpected getCDNFile result %T", r)
		}
	}
	return nil, fmt.Errorf("getCDNChunk: too many retries")
}

// cdnDecrypt decrypts a CDN chunk (AES-CTR, IV tail = offset/16). From gotd.
func cdnDecrypt(redirect *tg.UploadFileCDNRedirect, src []byte, offset int64) ([]byte, error) {
	block, err := aes.NewCipher(redirect.EncryptionKey)
	if err != nil {
		return nil, err
	}
	if block.BlockSize() != len(redirect.EncryptionIv) {
		return nil, fmt.Errorf("bad CDN IV length")
	}
	iv := make([]byte, len(redirect.EncryptionIv))
	copy(iv, redirect.EncryptionIv)
	binary.BigEndian.PutUint32(iv[len(iv)-4:], uint32(offset/16))
	dst := make([]byte, len(src))
	cipher.NewCTR(block, iv).XORKeyStream(dst, src)
	return dst, nil
}

// warmPool eagerly opens `conns` connections by firing that many `open` calls
// concurrently and waiting for them. gotd's pool creates a connection per
// in-flight request (up to its max), so concurrent calls force the pool to open
// up to `conns` distinct connections, which then sit in its free list ready for
// the real getFile calls. A single connection needs no warming. `open` errors
// are the caller's concern; warmPool only cares that the connection was opened.
func warmPool(ctx context.Context, conns int, open func(context.Context) error) {
	if conns <= 1 {
		return
	}
	var wg sync.WaitGroup
	for i := 0; i < conns; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = open(ctx)
		}()
	}
	wg.Wait()
}

// connSizeStep is the file size that warrants one download connection. A single
// connection comfortably sustains far more than the bitrate of a 20 GiB film, so
// scaling one connection per 20 GiB keeps small files on a single connection and
// only ramps big ones up. Tuned approximately, erring toward fewer connections.
const connSizeStep int64 = 20 << 30 // 20 GiB

// pickConns chooses the number of download connections for a file of `total`
// bytes: ceil(total / connSizeStep), clamped to [1, max]. Size is the only
// metadata we reliably have (duration is usually absent), so this is a coarse
// but predictable proxy: small file → 1 connection, big file → up to the cap.
func pickConns(total int64, max int) int {
	if max < 1 {
		max = 1
	}
	n := int((total + connSizeStep - 1) / connSizeStep) // ceil
	if n < 1 {
		n = 1
	}
	if n > max {
		n = max
	}
	return n
}

// poolNeedsResize reports whether a cached media pool created with existingMax
// connections must be recreated to honor a request for `requested` connections.
// We only grow pools (requested > existingMax): shrinking would needlessly tear
// down healthy in-use connections, and the larger pool already serves smaller
// requests fine (the per-stream `conns` still bounds in-flight chunks).
func poolNeedsResize(existingMax, requested int64) bool {
	return requested > existingMax
}

// mediaClient returns (and caches) a tg.Client bound to a pool of up to `conns`
// connections on the given DC.
func (e *Engine) mediaClient(ctx context.Context, dcID, conns int) (*tg.Client, error) {
	max := int64(conns)
	if max < 1 {
		max = 1
	}
	if max > maxPoolSize {
		max = maxPoolSize
	}

	e.mediaMu.Lock()
	defer e.mediaMu.Unlock()
	if e.mediaAPI == nil {
		e.mediaAPI = make(map[int]*tg.Client)
		e.mediaInv = make(map[int]telegram.CloseInvoker)
		e.mediaMax = make(map[int]int64)
	}
	if c, ok := e.mediaAPI[dcID]; ok {
		if !poolNeedsResize(e.mediaMax[dcID], max) {
			return c, nil
		}
		// The connection setting was raised since this DC's pool was first built,
		// so the cached pool capped concurrency below the new value. Tear it down
		// and recreate at the larger size. In-flight chunks on the old pool finish
		// against the closing invoker; new chunks use the resized pool.
		logI("resizing media pool dc=%d from %d to %d conns", dcID, e.mediaMax[dcID], max)
		_ = e.mediaInv[dcID].Close()
		delete(e.mediaAPI, dcID)
		delete(e.mediaInv, dcID)
		delete(e.mediaMax, dcID)
	}
	// Download on the DC's MEDIA endpoint with N connections. Telegram throttles
	// sustained getFile on the regular endpoint (the flood waits we saw); the
	// media endpoint is not throttled — a single account reaches ~10 MB/s there.
	// gotd does not export auth in the pool path (export lives only in
	// transfer.go), so the home-DC media pool reuses the existing home auth key.
	inv, err := e.client.MediaOnly(ctx, dcID, max)
	if err != nil {
		logW("MediaOnly dc %d failed (%v); falling back to regular pool (throttled)", dcID, err)
		inv, err = e.client.Pool(max)
		if err != nil {
			logE("connect dc %d failed: %v", dcID, err)
			return nil, fmt.Errorf("connect dc %d: %w", dcID, err)
		}
		logI("media client ready dc=%d conns=%d (REGULAR endpoint — throttled)", dcID, max)
	} else {
		logI("media client ready dc=%d conns=%d (media endpoint)", dcID, max)
	}
	c := tg.NewClient(inv)
	e.mediaAPI[dcID] = c
	e.mediaInv[dcID] = inv
	e.mediaMax[dcID] = max
	// Pre-open the pool to `max` connections. The streaming pipeline already
	// opens N connections for any range spanning ≥N chunks, but a small initial
	// range (ExoPlayer probing the file head, or a sub-N-MiB file) would leave us
	// under-parallelized; warming guarantees all N are ready. Fire-and-forget on
	// the engine-lived ctx — holding mediaMu through N handshakes would serialize
	// every concurrent getChunk; warmPool errors are irrelevant (goal is just to
	// open the connections, which then land in the pool's free list).
	go warmPool(ctx, int(max), func(ctx context.Context) error {
		_, err := c.HelpGetConfig(ctx)
		return err
	})
	return c, nil
}

func (e *Engine) closeMedia() {
	e.mediaMu.Lock()
	defer e.mediaMu.Unlock()
	for _, inv := range e.mediaInv {
		_ = inv.Close()
	}
	e.mediaAPI = nil
	e.mediaInv = nil
	e.mediaMax = nil
}

// --- small helpers ---

func parseMessageIDs(s string) ([]int, error) {
	if s == "" {
		return nil, fmt.Errorf("empty")
	}
	// Accept both dash- and comma-separated lists.
	sep := "-"
	if strings.Contains(s, ",") {
		sep = ","
	}
	fields := strings.Split(s, sep)
	out := make([]int, 0, len(fields))
	for _, f := range fields {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		v, err := strconv.Atoi(f)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

func parseRange(header string, size int64) (start, end int64, ok bool) {
	if header == "" || !strings.HasPrefix(header, "bytes=") {
		return 0, size - 1, false
	}
	spec := strings.TrimPrefix(header, "bytes=")
	if i := strings.IndexByte(spec, ','); i >= 0 {
		spec = spec[:i] // only the first range
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, size - 1, false
	}
	startStr, endStr := spec[:dash], spec[dash+1:]
	if startStr == "" {
		// suffix: last N bytes
		n, err := strconv.ParseInt(endStr, 10, 64)
		if err != nil || n <= 0 {
			return 0, size - 1, false
		}
		if n > size {
			n = size
		}
		return size - n, size - 1, true
	}
	s, err := strconv.ParseInt(startStr, 10, 64)
	if err != nil {
		return 0, size - 1, false
	}
	if endStr == "" {
		return s, size - 1, true
	}
	en, err := strconv.ParseInt(endStr, 10, 64)
	if err != nil {
		return 0, size - 1, false
	}
	if en > size-1 {
		en = size - 1
	}
	return s, en, true
}

func atoiDefault(s string, def int) int {
	if v, err := strconv.Atoi(s); err == nil {
		return v
	}
	return def
}

func roundUp(v, mult int64) int64 {
	if v <= 0 {
		return mult
	}
	return (v + mult - 1) / mult * mult
}

func maxI64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func minI64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}
