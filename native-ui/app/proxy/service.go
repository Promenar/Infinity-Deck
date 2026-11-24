package main

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	mnet "github.com/metacubex/mihomo/common/net"
	mihomo "github.com/metacubex/mihomo/constant"
)

const (
	nodeTimeout  = 5 * time.Second
	nodeCooldown = 30 * time.Second
)

var errUnavailable = errors.New("当前没有可用的出站节点")

type nodeHealth struct {
	failedUntil time.Time
	delayMs     int64
}

type generation struct {
	refs    atomic.Int64
	nodes   []outboundNode
	indices map[string]int
	enabled []bool
	order   []int
	mode    string
	health  []nodeHealth
	active  int
	reason  string
}

func newGeneration(p configParams) (*generation, error) {
	mode := strings.ToLower(strings.TrimSpace(p.Mode))
	fallback := false
	switch mode {
	case "":
		mode = "single"
	case "single":
	case "fallback":
		fallback = true
	default:
		return nil, errors.New("出站模式无效")
	}
	nodes, err := compileNodes(p.YAML)
	if err != nil {
		return nil, err
	}
	g := &generation{
		nodes: nodes, indices: make(map[string]int, len(nodes)), enabled: make([]bool, len(nodes)),
		mode: mode, health: make([]nodeHealth, len(nodes)), active: -1,
	}
	g.refs.Store(1)
	for i, node := range nodes {
		g.indices[node.Name] = i
		g.enabled[i] = p.EnabledNodes == nil
	}
	for _, name := range p.EnabledNodes {
		i, ok := g.indices[name]
		if !ok {
			g.release()
			return nil, errors.New("已启用节点列表包含未知节点")
		}
		g.enabled[i] = true
	}
	seen := make([]bool, len(nodes))
	add := func(i int) {
		if !seen[i] && g.enabled[i] {
			seen[i] = true
			g.order = append(g.order, i)
		}
	}
	if fallback && p.FallbackOrder != nil {
		for _, name := range p.FallbackOrder {
			i, ok := g.indices[name]
			if !ok {
				g.release()
				return nil, errors.New("降级顺序包含未知节点")
			}
			add(i)
		}
	}
	if !fallback && p.ActiveNode != "" {
		i, ok := g.indices[p.ActiveNode]
		if !ok || !fallback && !g.enabled[i] {
			g.release()
			return nil, errors.New("选中的出站节点不可用")
		}
		add(i)
	}
	if !fallback || p.FallbackOrder == nil {
		for i := range nodes {
			add(i)
		}
	}
	if len(g.order) > 0 {
		g.active = g.order[0]
		if !fallback {
			g.order = g.order[:1]
		}
	}
	return g, nil
}

func (g *generation) retain() *generation {
	g.refs.Add(1)
	return g
}

func (g *generation) release() {
	if g.refs.Add(-1) == 0 {
		closeNodes(g.nodes)
	}
}

type nodeState struct {
	nodeInfo
	Enabled bool  `json:"enabled"`
	Healthy bool  `json:"healthy"`
	DelayMs int64 `json:"delayMs,omitempty"`
}

type proxyState struct {
	Running          bool        `json:"running"`
	Mode             string      `json:"mode"`
	ActiveNode       string      `json:"activeNode"`
	HealthyNodeCount int         `json:"healthyNodeCount"`
	FallbackReason   string      `json:"fallbackReason"`
	Nodes            []nodeState `json:"nodes"`
}

type configResult struct {
	ProxyPort int `json:"proxyPort"`
	NodeCount int `json:"nodeCount"`
	proxyState
}

type proxyService struct {
	ctx        context.Context
	cancel     context.CancelFunc
	dns        dnsPolicy
	emit       func(proxyState)
	probeSlots chan struct{}

	mu       sync.Mutex
	listener net.Listener
	clients  map[net.Conn]struct{}
	leases   map[*leasedConn]struct{}
	current  *generation
	workers  sync.WaitGroup
}

func newProxyService(emit func(proxyState)) *proxyService {
	s := &proxyService{
		emit: emit, clients: make(map[net.Conn]struct{}), leases: make(map[*leasedConn]struct{}),
		probeSlots: make(chan struct{}, 4),
	}
	s.ctx, s.cancel = context.WithCancel(context.WithValue(context.Background(), dnsContextKey{}, &s.dns))
	return s
}

func (s *proxyService) stateLocked() proxyState {
	state := proxyState{Running: s.listener != nil, Nodes: []nodeState{}}
	g := s.current
	if g == nil {
		return state
	}
	state.Mode, state.FallbackReason = g.mode, g.reason
	if g.active >= 0 {
		state.ActiveNode = g.nodes[g.active].Name
	}
	now := time.Now()
	state.Nodes = make([]nodeState, len(g.nodes))
	for i, node := range g.nodes {
		healthy := g.enabled[i] && !now.Before(g.health[i].failedUntil)
		state.Nodes[i] = nodeState{nodeInfo: node.nodeInfo, Enabled: g.enabled[i], Healthy: healthy, DelayMs: g.health[i].delayMs}
		if healthy {
			state.HealthyNodeCount++
		}
	}
	return state
}

func (s *proxyService) status() proxyState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stateLocked()
}

func (s *proxyService) resultLocked() configResult {
	state := s.stateLocked()
	return configResult{ProxyPort: s.listener.Addr().(*net.TCPAddr).Port, NodeCount: len(state.Nodes), proxyState: state}
}

func (s *proxyService) start(p configParams) (configResult, error) {
	if s.status().Running {
		return configResult{}, errors.New("代理已启动")
	}
	auth, err := newCredentials(p.ProxyUser, p.ProxyPassword)
	if err != nil {
		return configResult{}, err
	}
	settings, err := resolveSettings(p.DNSServers)
	if err != nil {
		return configResult{}, err
	}
	g, err := newGeneration(p)
	if err != nil {
		return configResult{}, err
	}
	if len(g.order) == 0 {
		g.release()
		return configResult{}, errors.New("至少需要一个启用的出站节点")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		g.release()
		return configResult{}, errors.New("无法启动本地代理端口")
	}
	s.mu.Lock()
	s.listener, s.current = listener, g
	s.dns.settings.Store(settings)
	result := s.resultLocked()
	s.emit(result.proxyState)
	s.workers.Go(func() { s.accept(listener, auth) })
	s.mu.Unlock()
	return result, nil
}

func (s *proxyService) reload(p configParams) (configResult, error) {
	if !s.status().Running {
		return configResult{}, errors.New("代理尚未启动")
	}
	settings, err := resolveSettings(p.DNSServers)
	if err != nil {
		return configResult{}, err
	}
	g, err := newGeneration(p)
	if err != nil {
		return configResult{}, err
	}
	if len(g.order) == 0 {
		g.release()
		return configResult{}, errors.New("至少需要一个启用的出站节点")
	}
	s.mu.Lock()
	old, oldLeases := s.current, s.leases
	s.current = g
	s.leases = make(map[*leasedConn]struct{})
	if p.DNSServers != nil {
		s.dns.settings.Store(settings)
	}
	result := s.resultLocked()
	s.mu.Unlock()
	// 已替换的出站连接不能被 HTTPS keep-alive 用于新请求。
	for lease := range oldLeases {
		lease.Close()
	}
	old.release()
	s.emit(result.proxyState)
	return result, nil
}

func (s *proxyService) setDNS(servers []string) error {
	settings, err := resolveSettings(servers)
	if err == nil {
		s.dns.settings.Store(settings)
	}
	return err
}

func (s *proxyService) acquireGeneration() *generation {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return nil
	}
	return s.current.retain()
}

func (s *proxyService) accept(listener net.Listener, auth credentials) {
	for {
		conn, err := listener.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) || !retryAccept(s.ctx) {
				return
			}
			continue
		}
		s.mu.Lock()
		if s.ctx.Err() != nil || len(s.clients) >= 256 {
			s.mu.Unlock()
			conn.Close()
			continue
		}
		s.clients[conn] = struct{}{}
		s.workers.Go(func() {
			defer func() {
				conn.Close()
				s.mu.Lock()
				delete(s.clients, conn)
				s.mu.Unlock()
			}()
			s.handleClient(conn, auth)
		})
		s.mu.Unlock()
	}
}

func retryAccept(ctx context.Context) bool {
	timer := time.NewTimer(50 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func (s *proxyService) stop() {
	s.cancel()
	s.mu.Lock()
	listener, g := s.listener, s.current
	s.listener, s.current = nil, nil
	if listener != nil {
		listener.Close()
		for conn := range s.clients {
			conn.Close()
		}
		s.emit(s.stateLocked())
	}
	s.mu.Unlock()
	s.workers.Wait()
	if g != nil {
		g.release()
	}
}

func (s *proxyService) recordNode(g *generation, i int, success, selected bool, delay int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current != g {
		return
	}
	h := &g.health[i]
	oldHealth, active, reason := !time.Now().Before(h.failedUntil), g.active, g.reason
	oldDelay := h.delayMs
	if success {
		h.failedUntil = time.Time{}
		if delay > 0 {
			h.delayMs = delay
		}
		if selected {
			g.active = i
			g.reason = ""
			if i != g.order[0] {
				g.reason = "优先节点暂不可用，已切换出站节点"
			}
		}
	} else {
		h.failedUntil = time.Now().Add(nodeCooldown)
		h.delayMs = 0
		if g.active == i {
			g.active = -1
			g.reason = "出站节点连接失败"
			for _, candidate := range g.order {
				if !time.Now().Before(g.health[candidate].failedUntil) {
					g.active = candidate
					g.reason = "优先节点暂不可用，已切换出站节点"
					break
				}
			}
		}
	}
	if oldHealth != success || active != g.active || reason != g.reason || oldDelay != h.delayMs {
		s.emit(s.stateLocked())
	}
}

func (s *proxyService) dial(ctx context.Context, target destination) (net.Conn, error) {
	local, public := classifyTarget(ctx, target.host)
	if len(local) > 0 && !target.publicOnly {
		return dialBypass(ctx, local, target.port)
	}
	if !public {
		return nil, errUnavailable
	}
	g := s.acquireGeneration()
	if g == nil {
		return nil, errUnavailable
	}
	leased := false
	defer func() {
		if !leased {
			g.release()
		}
	}()
	for _, idx := range g.order {
		s.mu.Lock()
		cooling := time.Now().Before(g.health[idx].failedUntil)
		s.mu.Unlock()
		if cooling || ctx.Err() != nil {
			continue
		}
		attempt, cancel := context.WithTimeout(ctx, nodeTimeout)
		conn, err := dialNode(attempt, g.nodes[idx], target)
		cancel()
		if ctx.Err() == nil {
			s.recordNode(g, idx, err == nil, err == nil, 0)
		}
		if err == nil {
			lease := &leasedConn{Conn: conn}
			lease.release = func() {
				s.mu.Lock()
				delete(s.leases, lease)
				s.mu.Unlock()
				g.release()
			}
			s.mu.Lock()
			if s.current != g {
				s.mu.Unlock()
				conn.Close()
				return nil, errUnavailable
			}
			s.leases[lease] = struct{}{}
			s.mu.Unlock()
			leased = true
			return lease, nil
		}
	}
	return nil, errUnavailable
}

type leasedConn struct {
	net.Conn
	once    sync.Once
	release func()
}

func (c *leasedConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.release)
	return err
}

func (c *leasedConn) Upstream() any { return c.Conn }

type destination struct {
	host       string
	port       uint16
	publicOnly bool
}

func parseDestination(host, port string) (destination, bool) {
	host = normalizedHost(host)
	p, err := strconv.ParseUint(port, 10, 16)
	return destination{host: host, port: uint16(p)}, host != "" && p != 0 && err == nil
}

func (d destination) address() string {
	return net.JoinHostPort(d.host, strconv.Itoa(int(d.port)))
}

func dialNode(ctx context.Context, node outboundNode, target destination) (conn net.Conn, err error) {
	defer func() {
		if recover() != nil {
			if conn != nil {
				conn.Close()
			}
			conn, err = nil, errUnavailable
		}
	}()
	metadata := &mihomo.Metadata{NetWork: mihomo.TCP, DstPort: target.port}
	if ip, err := netip.ParseAddr(target.host); err == nil {
		metadata.DstIP = ip.WithZone("")
	} else {
		metadata.Host = target.host
	}
	conn, err = node.adapter.DialContext(ctx, metadata)
	if err != nil {
		return nil, errUnavailable
	}
	if mnet.NeedHandshake(conn) {
		conn.SetWriteDeadline(time.Now().Add(nodeTimeout))
		if deadline, ok := ctx.Deadline(); ok {
			conn.SetWriteDeadline(deadline)
		}
		socket := conn
		stop := context.AfterFunc(ctx, func() { socket.Close() })
		_, err = socket.Write(nil)
		stopped := stop()
		if err != nil || !stopped {
			socket.Close()
			return nil, errUnavailable
		}
		conn.SetWriteDeadline(time.Time{})
	}
	return conn, nil
}
