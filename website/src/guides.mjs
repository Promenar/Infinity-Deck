// 双语专题共用路由，首页、文档页与 sitemap 使用同一清单。
export const guides = {
  'android-ai-coding': {
    zh: {
      title: '在 Android 手机上用 AI 修改 Git 仓库',
      intro: 'Infinity Deck 是运行在 Android 12 及以上设备上的开源 AI 智能体。侧载版内置 Alpine Linux，可以在手机上用 Git 拉取仓库、运行命令和检查代码改动。',
      steps: [
        '下载侧载版，配置模型服务和自己的 API Key，再为会话选择 Linux 执行环境。',
        '克隆 HTTPS 仓库并绑定到会话工作区。私有仓库在钥匙串的 Git 类别里添加令牌或用 GitHub 浏览器登录。',
        '交代修改目标和验收命令。需要并行处理时，为分支会话选择独立的 Git 工作树。',
        '在 Git 检视器查看差异和分支，核对测试结果后再提交或推送。',
      ],
      note: 'Linux 命令在手机上运行。拉取远端仓库与调用云端模型仍需连接相应服务。启用命令审批时，逐条确认命令。',
      alt: 'Infinity Deck 会话中的 Git 分支方案与工作树',
    },
    en: {
      title: 'Edit Git repositories with an AI coding agent on Android',
      intro: 'Infinity Deck is an open-source AI agent for Android 12 and later. The sideloaded app includes Alpine Linux for cloning Git repositories, running commands, and reviewing changes on the phone.',
      steps: [
        'Install the sideloaded APK, configure your model provider and API key, and select the Linux execution environment for the session.',
        'Clone an HTTPS repository and bind it to the session workspace. For a private repository, add a token or sign in to GitHub in the browser under Git in the keychain.',
        'Describe the change and how to test it. For parallel work, use a separate Git worktree for the branch session.',
        'Review diffs, branches, and test results in the Git inspector before committing or pushing.',
      ],
      note: 'Linux commands run on your phone. Remote repositories and cloud models still require a connection to their respective services. In command approval mode, review each command before it runs.',
      alt: 'A Git branch plan and worktrees in an Infinity Deck session',
    },
    image: 'inner-chat.state.branch-plan.webp',
    source: 'workspaces.md',
  },
  'android-ssh-ops': {
    zh: {
      title: '用 Android AI 智能体通过 SSH 排查服务器故障',
      intro: 'Infinity Deck 可以从 Android 手机连接 SSH 主机。在命令审批模式下，你可以先查看 Agent 拟执行的命令，再逐条授权。服务器位于 tailnet 时，可使用应用内置的 Tailscale 连接。',
      steps: [
        '在应用里添加 SSH 主机，核对主机指纹，选择登录密码或钥匙串里的 SSH 密钥。若主机在 tailnet 内，先连接 Tailscale。',
        '告诉 Agent 受影响的站点、错误码和时间范围，并指定先做只读检查。',
        '以首页的 502 演示为例，逐条审核反向代理日志和上游服务状态的检查命令，依据输出判断故障位置。',
        '确认影响范围后再批准修复命令，复查站点响应，并让 Agent 汇总命令结果与待跟进事项。',
      ],
      note: 'SSH 命令在目标服务器执行，连接需要有效凭据和网络可达性。演示中的 502 处理步骤应根据实际服务配置调整。',
      alt: 'Infinity Deck 外屏显示的 SSH 排障报告',
    },
    en: {
      title: 'Troubleshoot servers over SSH with an Android AI agent',
      intro: 'Infinity Deck connects to SSH hosts from Android. In command approval mode, you can inspect each proposed command before authorizing it. For a host on your tailnet, you can connect through the app’s built-in Tailscale integration.',
      steps: [
        'Add the SSH host, verify its fingerprint, and choose a password or an SSH key from the keychain. Connect to Tailscale first if the host is on your tailnet.',
        'Give the agent the affected site, error code, and time window. Ask it to start with read-only checks.',
        'Following the homepage’s 502 demo, review checks for reverse-proxy logs and upstream service status. Use the output to locate the failure.',
        'Confirm the scope before approving a repair, verify the site response, and ask the agent to summarize the results and follow-up items.',
      ],
      note: 'SSH commands run on the target server and require valid credentials and network access. Adapt the steps in the 502 demo to your service configuration.',
      alt: 'An SSH incident report on the Infinity Deck cover screen',
    },
    image: 'outer-chat.state.ops-report.webp',
    source: 'execution-environments.md',
  },
};

export const guidePaths = Object.keys(guides).map(slug => `/guides/${slug}/`);
