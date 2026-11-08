// nxtailnet 是 Infinity Deck 内置的 Tailscale 节点（tsnet）。它作为 App 的子进程运行，不占用系统 VPN：
// 经 stdin/stdout JSON 行协议接受启停、登录、状态与节点列表命令，并在 127.0.0.1 随机端口提供
// 需要凭据的 SOCKS5 / HTTP 分流代理，让 App 内的 HTTP、SSH 与子进程访问 tailnet。
// stdin 结束（父进程退出或主动关闭）时下线并退出。
package main

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	"tailscale.com/envknob"
)

func main() {
	// 不向 Tailscale 上传运行日志；tsnet 的日志全部丢弃，stdout 只承载协议。
	envknob.SetNoLogsNoSupport()
	log.SetOutput(io.Discard)
	s := NewServer(NewWriter(os.Stdout), func(opts *StartOptions) Backend { return NewNode(opts) }, directDialer())
	os.Exit(s.Run(os.Stdin))
}

func discardLogger() *log.Logger { return log.New(io.Discard, "", 0) }

// Backend 是 tsnet 节点的抽象；测试使用假实现。
type Backend interface {
	Directory
	Start(ctx context.Context, emit func(StatusSummary)) error
	Summary(ctx context.Context) (StatusSummary, error)
	PeerList(ctx context.Context) []PeerInfo
	Ping(ctx context.Context, ip netip.Addr) PingReply
	Login(ctx context.Context) error
	Logout(ctx context.Context) error
	Dial(ctx context.Context, network, address string) (net.Conn, error)
	Close() error
}

// Server 处理协议命令。
type Server struct {
	out        *Writer
	newBackend func(*StartOptions) Backend
	direct     DialFunc

	ctx    context.Context
	cancel context.CancelFunc

	mu      sync.Mutex
	backend Backend
	proxy   *Proxy
	done    chan struct{}
	once    sync.Once
}

func NewServer(out *Writer, newBackend func(*StartOptions) Backend, direct DialFunc) *Server {
	ctx, cancel := context.WithCancel(context.Background())
	return &Server{out: out, newBackend: newBackend, direct: direct, ctx: ctx, cancel: cancel, done: make(chan struct{})}
}

// Run 读取命令直到 stdin 结束或收到 shutdown，然后关闭节点并返回退出码。
func (s *Server) Run(in io.Reader) int {
	reader := NewRequestReader(in)
	lines := make(chan *Request)
	readErr := make(chan error, 1)
	go func() {
		for {
			req, err := reader.Next()
			if err != nil {
				if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, os.ErrClosed) {
					readErr <- err
					return
				}
				// 无法解析的行：没有可信的 id，按 0 回复后继续。
				s.out.Fail(0, ErrBadRequest, "无法解析的请求")
				continue
			}
			lines <- req
		}
	}()
	for {
		select {
		case req := <-lines:
			s.dispatch(req)
		case <-readErr:
			s.shutdown()
			return 0
		case <-s.done:
			s.shutdown()
			return 0
		}
	}
}

func (s *Server) dispatch(req *Request) {
	if req.Op == OpStart {
		s.start(req)
		return
	}
	if req.Op == OpShutdown {
		s.out.Reply(req.ID, struct{}{})
		s.once.Do(func() { close(s.done) })
		return
	}
	s.mu.Lock()
	backend := s.backend
	s.mu.Unlock()
	if backend == nil {
		if req.Op == OpPing {
			s.out.Fail(req.ID, ErrNotRunning, "Tailscale 未运行")
			return
		}
		s.out.Fail(req.ID, ErrNotStarted, "尚未启动")
		return
	}
	go s.handle(req, backend)
}

func (s *Server) handle(req *Request, backend Backend) {
	switch req.Op {
	case OpPing:
		ctx, cancel := context.WithTimeout(s.ctx, pingTimeout)
		defer cancel()
		if !backend.Running() {
			s.out.Fail(req.ID, ErrNotRunning, "Tailscale 未运行")
			return
		}
		if req.Ping == nil {
			s.out.Fail(req.ID, ErrBadRequest, "缺少 ping 参数")
			return
		}
		ip, err := netip.ParseAddr(req.Ping.Target)
		if err != nil {
			s.out.Fail(req.ID, ErrBadRequest, "ping 目标必须是在线节点的 tailnet IP")
			return
		}
		peer := findPeerByAddr(backend.Peers(ctx, true), ip)
		if !backend.Running() {
			s.out.Fail(req.ID, ErrNotRunning, "Tailscale 未运行")
			return
		}
		if peer == nil || !peer.Online {
			s.out.Fail(req.ID, ErrBadRequest, "ping 目标必须是在线节点的 tailnet IP")
			return
		}
		s.out.Reply(req.ID, backend.Ping(ctx, ip))
	case OpStatus:
		ctx, cancel := context.WithTimeout(s.ctx, 10*time.Second)
		defer cancel()
		summary, err := backend.Summary(ctx)
		if err != nil {
			s.out.Fail(req.ID, ErrInternal, err.Error())
			return
		}
		s.out.Reply(req.ID, summary)
	case OpPeers:
		ctx, cancel := context.WithTimeout(s.ctx, 10*time.Second)
		defer cancel()
		s.out.Reply(req.ID, PeersResult{Peers: backend.PeerList(ctx)})
	case OpLogin:
		ctx, cancel := context.WithTimeout(s.ctx, 30*time.Second)
		defer cancel()
		if err := backend.Login(ctx); err != nil {
			s.out.Fail(req.ID, ErrInternal, err.Error())
			return
		}
		s.out.Reply(req.ID, struct{}{})
	case OpLogout:
		ctx, cancel := context.WithTimeout(s.ctx, 30*time.Second)
		defer cancel()
		if err := backend.Logout(ctx); err != nil {
			s.out.Fail(req.ID, ErrInternal, err.Error())
			return
		}
		s.out.Reply(req.ID, struct{}{})
	default:
		s.out.Fail(req.ID, ErrUnsupported, "不支持的命令")
	}
}

var hostnamePattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)

// minSecretLength 是代理用户名与口令的最小长度；父进程每次启动随机生成。
const minSecretLength = 16

func validateStart(opts *StartOptions) string {
	switch {
	case opts == nil:
		return "缺少 start 参数"
	case !filepath.IsAbs(opts.StateDir):
		return "stateDir 必须是绝对路径"
	case !hostnamePattern.MatchString(opts.Hostname):
		return "hostname 不合法"
	case len(opts.ProxyUser) < minSecretLength || len(opts.ProxyPassword) < minSecretLength:
		return "代理凭据过短"
	}
	if opts.ControlURL != "" {
		u, err := url.Parse(opts.ControlURL)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.User != nil {
			return "controlUrl 必须是不含凭据的 http(s) URL"
		}
	}
	return ""
}

func (s *Server) start(req *Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.backend != nil {
		s.out.Fail(req.ID, ErrStarted, "已启动")
		return
	}
	if problem := validateStart(req.Start); problem != "" {
		s.out.Fail(req.ID, ErrBadRequest, problem)
		return
	}
	opts := *req.Start
	if err := os.MkdirAll(opts.StateDir, 0o700); err != nil {
		s.out.Fail(req.ID, ErrStartFailed, "无法创建状态目录")
		return
	}
	backend := s.newBackend(&opts)
	router := &Router{Directory: backend, Tailnet: backend.Dial, Direct: s.direct}
	proxy := &Proxy{Credentials: Credentials{User: opts.ProxyUser, Password: opts.ProxyPassword}, Dial: router.Dial}
	port, err := proxy.Listen()
	if err != nil {
		s.out.Fail(req.ID, ErrStartFailed, "无法监听本机代理端口")
		return
	}
	s.backend, s.proxy = backend, proxy
	s.out.Reply(req.ID, StartResult{ProxyPort: port})
	go func() {
		if err := backend.Start(s.ctx, s.out.State); err != nil {
			s.out.State(StatusSummary{BackendState: "NoState", Error: ErrStartFailed + ": " + err.Error()})
		}
	}()
}

func (s *Server) shutdown() {
	s.cancel()
	s.mu.Lock()
	backend, proxy := s.backend, s.proxy
	s.mu.Unlock()
	if proxy != nil {
		proxy.Close()
	}
	if backend != nil {
		closed := make(chan struct{})
		go func() { _ = backend.Close(); close(closed) }()
		select {
		case <-closed:
		case <-time.After(5 * time.Second):
		}
	}
}
