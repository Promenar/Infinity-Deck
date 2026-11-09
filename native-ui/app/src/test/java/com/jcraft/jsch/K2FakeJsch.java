package com.jcraft.jsch;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;

/** 在库的包内构造假 Session，仅测试资源生命周期与固定命令，不建立网络连接。 */
public class K2FakeJsch extends JSch {
    public static final byte[] HOST_KEY = ByteBuffer.allocate(4 + 11 + 32).putInt(11)
        .put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII)).put(new byte[32]).array();
    public final List<FakeSession> sessions = new java.util.concurrent.CopyOnWriteArrayList<>();
    public boolean failConnect;
    public boolean keepChannelOpen;
    public int exitCode;
    public boolean pauseAfterPassword;
    public final java.util.concurrent.CountDownLatch passwordSet = new java.util.concurrent.CountDownLatch(1);
    public final java.util.concurrent.CountDownLatch releasePassword = new java.util.concurrent.CountDownLatch(1);
    public final java.util.concurrent.CountDownLatch connectFinished = new java.util.concurrent.CountDownLatch(1);
    @Override public Session getSession(String username, String host, int port) throws JSchException {
        FakeSession session = new FakeSession(this, username, host, port);
        sessions.add(session);
        return session;
    }
    public class FakeSession extends Session {
        public volatile boolean connected;
        public volatile boolean disconnected;
        public volatile FakeExec exec;
        FakeSession(JSch client, String username, String host, int port) throws JSchException { super(client, username, host, port); }
        @Override public void setPassword(byte[] bytes) {
            super.setPassword(bytes);
            if (pauseAfterPassword && bytes != null && bytes.length > 0) {
                passwordSet.countDown();
                try { if (!releasePassword.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("密码门闩超时"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }
        @Override public void connect(int timeout) throws JSchException {
            try {
                if (failConnect) throw new JSchException("synthetic connection failure");
                int accepted = getHostKeyRepository().check(host, HOST_KEY);
                if (accepted != HostKeyRepository.OK) throw new JSchException("synthetic host key rejection");
                connected = true;
            } finally {
                if (password != null) java.util.Arrays.fill(password, (byte) 0);
                password = null;
                connectFinished.countDown();
            }
        }
        @Override public Channel openChannel(String type) { exec = new FakeExec(); return exec; }
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnect() { if (!connected) return; disconnected = true; connected = false; if (exec != null) exec.disconnect(); }
        public boolean passwordCleared() { return password == null || password.length == 0; }
    }
    public class FakeExec extends ChannelExec {
        public String text;
        public volatile boolean disconnected;
        @Override public void setCommand(String command) { text = command; }
        @Override public void connect(int timeout) {}
        @Override public boolean isClosed() { return !keepChannelOpen || disconnected; }
        @Override public int getExitStatus() { return exitCode; }
        @Override public void disconnect() { disconnected = true; }
    }
}
