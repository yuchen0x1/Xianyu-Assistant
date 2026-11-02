<script setup lang="ts">
declare const __APP_VERSION__: string

defineOptions({ name: 'AboutPanel' })

const appVersion = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '3.4.10'
// 动态绑定资源路径，避免测试环境（Windows jsdom）对静态 src 的 file URL 解析问题
const brandMark = '/brand-mark.svg'
</script>

<template>
  <div class="settings__panel">
    <div class="settings__panel-title">关于</div>

    <section class="about-hero" aria-labelledby="about-title">
      <img class="about-hero__mark" :src="brandMark" alt="XianYu Seller Console" />
      <div class="about-hero__content">
        <p class="about-hero__eyebrow">PERSONAL SELLER CONSOLE</p>
        <h2 id="about-title" class="about-hero__title">XianYu Seller Console</h2>
        <p class="about-hero__desc">A focused workspace for seller accounts, products, messages, and automation workflows.</p>
        <div class="about-hero__meta">
          <span class="about-version">当前版本 v{{ appVersion }}</span>
          <span class="about-status"><span class="about-status__dot" aria-hidden="true"></span>本地服务运行中</span>
        </div>
      </div>
    </section>

    <div class="about-grid">
      <section class="about-card" aria-labelledby="about-capabilities-title">
        <div class="about-card__kicker">WORKFLOWS</div>
        <h3 id="about-capabilities-title" class="about-card__title">已覆盖的工作流</h3>
        <p class="about-card__desc">从账号连接到售后跟进，常用操作都可以在左侧菜单完成。</p>
        <ul class="about-list">
          <li><strong>账号与连接</strong><span>扫码登录、Cookie/Token 管理、WebSocket 连接状态</span></li>
          <li><strong>商品管理</strong><span>同步商品资料、编辑商品信息、维护在售状态</span></li>
          <li><strong>消息与自动化</strong><span>消息查看、关键词回复、AI 客服、自动发货</span></li>
          <li><strong>数据与运维</strong><span>数据面板、操作日志、备份恢复、邮箱告警</span></li>
        </ul>
      </section>

      <section class="about-card" aria-labelledby="about-start-title">
        <div class="about-card__kicker">GET STARTED</div>
        <h3 id="about-start-title" class="about-card__title">三步开始使用</h3>
        <p class="about-card__desc">首次部署或新增账号时，按这个顺序操作最稳妥。</p>
        <ol class="about-steps">
          <li><span class="about-step__number">1</span><span><strong>添加账号</strong><small>在“闲鱼账号”中扫码登录，并确认账号状态正常。</small></span></li>
          <li><span class="about-step__number">2</span><span><strong>确认连接</strong><small>系统会在后台自动保持 WebSocket 连接，可在“连接管理”中确认状态。</small></span></li>
          <li><span class="about-step__number">3</span><span><strong>配置自动化</strong><small>同步商品后，再按需开启自动回复或自动发货。</small></span></li>
        </ol>
      </section>
    </div>

    <section class="about-note" aria-labelledby="about-safety-title">
      <div class="about-note__icon" aria-hidden="true">i</div>
      <div>
        <h3 id="about-safety-title" class="about-note__title">账号与数据使用提醒</h3>
        <ul class="about-note__list">
          <li>请只管理您本人有权使用的账号，并遵守闲鱼平台规则。</li>
          <li>Cookie、Token 和 AI 密钥属于敏感信息，不要截图、转发或提交到公共仓库。</li>
          <li>遇到滑块验证、掉线或状态异常时，请先在闲鱼 App 完成验证，再回到“连接管理”重试。</li>
          <li>重要配置请定期在“备份与恢复”中导出，升级前先确认备份可用。</li>
        </ul>
      </div>
    </section>

    <section class="about-footer" aria-label="应用信息">
      <span>XianYu Seller Console · Self-hosted Edition</span>
      <span>数据保存在当前服务实例中</span>
    </section>
  </div>
</template>
