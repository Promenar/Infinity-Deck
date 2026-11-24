package main

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"sync"
	"time"
)

const defaultProbeURL = "https://www.gstatic.com/generate_204"

type latencyParams struct {
	Nodes     []string `json:"nodes"`
	URL       string   `json:"url"`
	TimeoutMs int      `json:"timeoutMs"`
	YAML      string   `json:"yaml"`
}

type latencyNode struct {
	Name    string `json:"name"`
	OK      bool   `json:"ok"`
	DelayMs int64  `json:"delayMs"`
	Error   string `json:"error"`
}

type latencyResult struct {
	Nodes []latencyNode `json:"nodes"`
}

type latencyBatch struct {
	g       *generation
	names   []string
	url     string
	target  destination
	timeout time.Duration
}

func (s *proxyService) prepareLatency(p latencyParams) (latencyBatch, error) {
	if p.URL == "" {
		p.URL = defaultProbeURL
	}
	u, err := url.Parse(p.URL)
	if err != nil || len(p.URL) > 8192 {
		return latencyBatch{}, errors.New("测速网址无效")
	}
	target, ok := urlTarget(u)
	if !ok {
		return latencyBatch{}, errors.New("测速网址无效")
	}
	if p.TimeoutMs == 0 {
		p.TimeoutMs = 5000
	}
	if p.TimeoutMs < 1 || p.TimeoutMs > 60000 || len(p.Nodes) > maxNodes {
		return latencyBatch{}, errors.New("测速超时或节点数量超出范围")
	}
	g := s.acquireGeneration()
	if g == nil {
		if p.YAML == "" {
			return latencyBatch{}, errors.New("代理未运行，请提供测速节点配置")
		}
		g, err = newGeneration(configParams{YAML: p.YAML})
		if err != nil {
			return latencyBatch{}, err
		}
	}
	if p.Nodes == nil {
		for _, node := range g.nodes {
			p.Nodes = append(p.Nodes, node.Name)
		}
	}
	return latencyBatch{g, p.Nodes, u.String(), target, time.Duration(p.TimeoutMs) * time.Millisecond}, nil
}

func (s *proxyService) runLatency(batch latencyBatch) latencyResult {
	defer batch.g.release()
	result := latencyResult{Nodes: make([]latencyNode, len(batch.names))}
	var workers sync.WaitGroup
	jobs := make(chan int)
	for range min(len(batch.names), cap(s.probeSlots)) {
		workers.Go(func() {
			for i := range jobs {
				result.Nodes[i] = s.probe(batch, batch.names[i])
			}
		})
	}
	for i := range batch.names {
		jobs <- i
	}
	close(jobs)
	workers.Wait()
	return result
}

func (s *proxyService) probe(batch latencyBatch, name string) latencyNode {
	result := latencyNode{Name: name}
	index, exists := batch.g.indices[name]
	if !exists {
		result.Error = "未找到测速节点"
		return result
	}
	select {
	case s.probeSlots <- struct{}{}:
		defer func() { <-s.probeSlots }()
	case <-s.ctx.Done():
		result.Error = "测速已取消"
		return result
	}
	ctx, cancel := context.WithTimeout(s.ctx, batch.timeout)
	defer cancel()
	if _, public := classifyTarget(ctx, batch.target.host); !public {
		result.Error = "测速目标须为公网网址"
		return result
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, batch.url, nil)
	if err != nil {
		result.Error = "测速网址无效"
		return result
	}
	begin := time.Now()
	upstream, err := dialNode(ctx, batch.g.nodes[index], batch.target)
	var resp *http.Response
	if err == nil {
		defer upstream.Close()
		transport := proxyTransport(upstream)
		defer transport.CloseIdleConnections()
		resp, err = transport.RoundTrip(req)
	}
	switch {
	case s.ctx.Err() != nil:
		result.Error = "测速已取消"
	case errors.Is(ctx.Err(), context.DeadlineExceeded):
		result.Error = "测速超时"
	case err != nil:
		result.Error = "无法连接测速目标"
	case resp.StatusCode < 200 || resp.StatusCode >= 300 || batch.url == defaultProbeURL && resp.StatusCode != http.StatusNoContent:
		result.Error = "测速目标响应异常"
	default:
		result.OK = true
		result.DelayMs = max(1, time.Since(begin).Milliseconds())
	}
	if resp != nil {
		resp.Body.Close()
	}
	// 测速目标返回错误不表示出站节点失效，避免触发业务流量冷却。
	if result.OK && s.ctx.Err() == nil {
		s.recordNode(batch.g, index, true, false, result.DelayMs)
	}
	return result
}
