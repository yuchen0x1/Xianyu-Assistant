<script setup lang="ts">
import { ref, watch, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { getGoodsDetail, deleteItem } from '@/api/goods';
import { getPriceTrend, type PriceTrendPoint } from '@/api/price-history';
import { showSuccess, showError, showConfirm } from '@/utils';
import type { GoodsItemWithConfig } from '@/api/goods';
import EmptyState from '@/components/EmptyState.vue';

interface Props {
  modelValue: boolean;
  goodsId: string;
  accountId: number | null;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'refresh'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const router = useRouter();

const loading = ref(false);
const goodsDetail = ref<GoodsItemWithConfig | null>(null);
const currentImageIndex = ref(0);
const images = ref<string[]>([]);

// 响应式检测
const isMobile = ref(false);
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768;
};

// 加载商品详情
const loadDetail = async () => {
  if (!props.goodsId) return;

  loading.value = true;
  try {
    const response = await getGoodsDetail(props.goodsId);
    if (response.code === 0 || response.code === 200) {
      goodsDetail.value = response.data?.itemWithConfig || null;
      
      // 解析图片列表
      if (goodsDetail.value?.item.infoPic) {
        try {
          const infoPicArray = JSON.parse(goodsDetail.value.item.infoPic);
          images.value = infoPicArray.map((pic: { url?: string }) => pic.url);
        } catch (e) {
          console.error('解析图片列表失败:', e);
          images.value = [];
        }
      }
      
      // 如果没有图片，使用封面图
      if (images.value.length === 0 && goodsDetail.value?.item.coverPic) {
        images.value = [goodsDetail.value.item.coverPic];
      }
      
      currentImageIndex.value = 0;

      // 并行加载价格趋势（不阻塞详情渲染）
      loadPriceTrend();
    } else {
      throw new Error(response.msg || '获取商品详情失败');
    }
  } catch (error) {
    console.error('加载商品详情失败:', error);
  } finally {
    loading.value = false;
  }
};

// // 切换自动发货
// const handleToggleAutoDelivery = async (value: boolean) => {
//   if (!props.accountId || !goodsDetail.value) return;
//
//   try {
//     const response = await updateAutoDeliveryStatus({
//       xianyuAccountId: props.accountId,
//       xyGoodsId: goodsDetail.value.item.xyGoodId,
//       xianyuAutoDeliveryOn: value ? 1 : 0
//     });
//
//     if (response.code === 0 || response.code === 200) {
//       showSuccess(`自动发货${value ? '开启' : '关闭'}成功`);
//       goodsDetail.value.xianyuAutoDeliveryOn = value ? 1 : 0;
//       emit('refresh');
//     } else {
//       throw new Error(response.msg || '操作失败');
//     }
//   } catch (error) {
//     showError('操作失败: ' + (error as Error).message);
//     // 恢复开关状态
//     if (goodsDetail.value) {
//       goodsDetail.value.xianyuAutoDeliveryOn = value ? 0 : 1;
//     }
//   }
// };
//
// // 切换自动回复
// const handleToggleAutoReply = async (value: boolean) => {
//   if (!props.accountId || !goodsDetail.value) return;
//
//   try {
//     const response = await updateAutoReplyStatus({
//       xianyuAccountId: props.accountId,
//       xyGoodsId: goodsDetail.value.item.xyGoodId,
//       xianyuAutoReplyOn: value ? 1 : 0
//     });
//
//     if (response.code === 0 || response.code === 200) {
//       showSuccess(`自动回复${value ? '开启' : '关闭'}成功`);
//       goodsDetail.value.xianyuAutoReplyOn = value ? 1 : 0;
//       emit('refresh');
//     } else {
//       throw new Error(response.msg || '操作失败');
//     }
//   } catch (error) {
//     showError('操作失败: ' + (error as Error).message);
//     // 恢复开关状态
//     if (goodsDetail.value) {
//       goodsDetail.value.xianyuAutoReplyOn = value ? 0 : 1;
//     }
//   }
// };

// 配置自动发货
const handleConfigAutoDelivery = () => {
  if (!goodsDetail.value) return;

  router.push({
    path: '/auto-delivery',
    query: {
      accountId: props.accountId?.toString(),
      goodsId: goodsDetail.value.item.xyGoodId
    }
  });
  handleClose();
};

// 删除商品
const handleDelete = async () => {
  if (!props.accountId || !goodsDetail.value) return;

  try {
    await showConfirm(
      `确定要删除商品 "${goodsDetail.value.item.title}" 吗？此操作不可恢复。`,
      '删除确认'
    );

    const response = await deleteItem({
      xianyuAccountId: props.accountId,
      xyGoodsId: goodsDetail.value.item.xyGoodId
    });

    if (response.code === 0 || response.code === 200) {
      showSuccess('商品删除成功');
      handleClose();
      emit('refresh');
    } else {
      throw new Error(response.msg || '删除失败');
    }
  } catch (error) {
    if (error === 'cancel') {
      return;
    }
    showError('删除失败: ' + (error as Error).message);
  }
};

// 获取状态标签类型
const getStatusType = (status: number) => {
  const statusMap: Record<number, string> = {
    0: 'success',
    1: 'info',
    2: 'warning'
  };
  return statusMap[status] || 'info';
};

// 获取状态文本
const getStatusText = (status: number) => {
  const statusMap: Record<number, string> = {
    0: '在售',
    1: '已下架',
    2: '已售出',
    '-1': '已删除'
  };
  return statusMap[status] || '未知';
};

// 格式化价格
const formatPrice = (price: string) => {
  return price ? `¥${price}` : '-';
};

// ===== 价格趋势 =====
const priceTrend = ref<PriceTrendPoint[]>([]);
const priceTrendLoading = ref(false);
const priceTrendLoaded = ref(false);

const loadPriceTrend = async () => {
  if (!props.goodsId) return;
  priceTrendLoading.value = true;
  try {
    const response = await getPriceTrend(props.goodsId, 30);
    if (response.code === 0 || response.code === 200) {
      priceTrend.value = response.data ?? [];
    } else {
      priceTrend.value = [];
    }
  } catch (error) {
    console.error('加载价格趋势失败:', error);
    priceTrend.value = [];
  } finally {
    priceTrendLoading.value = false;
    priceTrendLoaded.value = true;
  }
};

// SVG 折线图尺寸
const TREND_W = 460;
const TREND_H = 132;
const TREND_PAD_L = 10;
const TREND_PAD_R = 10;
const TREND_PAD_T = 14;
const TREND_PAD_B = 22;

// 趋势图表数据（数据不足 2 个点时返回 null）
const trendChart = computed(() => {
  const pts = priceTrend.value;
  if (pts.length < 2) return null;
  const prices = pts.map((p) => p.price);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const range = max - min || 1;
  const innerW = TREND_W - TREND_PAD_L - TREND_PAD_R;
  const innerH = TREND_H - TREND_PAD_T - TREND_PAD_B;
  const posAt = (i: number) => ({
    x: TREND_PAD_L + (i / (pts.length - 1)) * innerW,
    y: TREND_PAD_T + (1 - (pts[i].price - min) / range) * innerH
  });
  const path = pts
    .map((_, i) => {
      const p = posAt(i);
      return `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`;
    })
    .join(' ');
  const first = posAt(0);
  const last = posAt(pts.length - 1);
  return {
    stats: {
      max,
      min,
      current: pts[pts.length - 1].price
    },
    path,
    first: { ...first, point: pts[0] },
    last: { ...last, point: pts[pts.length - 1] }
  };
});

const formatTrendPrice = (v: number) => `¥${v.toFixed(2)}`;
const formatTrendDate = (d: string) => d.slice(5); // "2026-07-01" -> "07-01"

// 选择图片
const selectImage = (index: number) => {
  currentImageIndex.value = index;
};

// 关闭对话框
const handleClose = () => {
  emit('update:modelValue', false);
  goodsDetail.value = null;
  images.value = [];
  priceTrend.value = [];
  priceTrendLoaded.value = false;
};

// 监听对话框打开
watch(() => props.modelValue, (val) => {
  if (val) {
    loadDetail();
  }
});

onMounted(() => {
  checkScreenSize();
  window.addEventListener('resize', checkScreenSize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', checkScreenSize);
});
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container" :class="{ 'is-mobile': isMobile }">
          <!-- Header -->
          <div class="modal-header">
            <h2 class="modal-title">商品详情</h2>
            <button class="modal-close" @click="handleClose">×</button>
          </div>

          <!-- Body -->
          <div class="modal-body">
            <div v-if="goodsDetail" class="detail-content">
              <div class="detail-left">
                <div class="main-image">
                  <img
                    v-if="images.length > 0"
                    :src="images[currentImageIndex]"
                    class="native-image"
                  />
                  <EmptyState v-else title="暂无图片" class="main-image__empty" />
                </div>
                <div v-if="images.length > 1" class="thumbnails">
                  <div
                    v-for="(img, index) in images"
                    :key="index"
                    class="thumbnail"
                    :class="{ active: currentImageIndex === index }"
                    @click="selectImage(index)"
                  >
                    <img :src="img" class="native-image" />
                  </div>
                </div>
              </div>

              <div class="detail-right">
                <div class="title-section">
                  <h3 class="goods-title">{{ goodsDetail.item.title }}</h3>
                  <div class="goods-id">ID: {{ goodsDetail.item.xyGoodId }}</div>
                </div>

                <div class="price-section">
                  <span class="price">{{ formatPrice(goodsDetail.item.soldPrice) }}</span>
                  <span class="tag" :class="getStatusType(goodsDetail.item.status) === 'success' ? 'tag--success' : getStatusType(goodsDetail.item.status) === 'warning' ? 'tag--warning' : 'tag--info'">
                    {{ getStatusText(goodsDetail.item.status) }}
                  </span>
                </div>

                <div v-if="goodsDetail.item.detailInfo" class="description">
                  <div class="description-title">商品描述</div>
                  <div class="description-content">{{ goodsDetail.item.detailInfo }}</div>
                </div>

                <div class="config-section">
                  <div class="config-item">
                    <span class="config-label">自动发货</span>
                    <div class="config-value">
                      <span v-if="goodsDetail.xianyuAutoDeliveryOn === 1" class="detail-mode-tag detail-mode-tag--delivery">{{ (goodsDetail.autoDeliveryType ?? 1) === 2 ? '卡密发货' : '文本发货' }}</span>
                      <span v-else class="detail-mode-tag detail-mode-tag--off">未开启</span>
                    </div>
                  </div>
                  <div class="config-item">
                    <span class="config-label">自动回复</span>
                    <div class="config-value">
                      <span v-if="goodsDetail.xianyuAutoReplyOn === 1" class="detail-mode-tag detail-mode-tag--reply">已开启</span>
                      <span v-else class="detail-mode-tag detail-mode-tag--off">未开启</span>
                    </div>
                  </div>
                </div>

                <div class="action-buttons">
                  <button class="btn-glass btn-glass--success" @click="handleConfigAutoDelivery">配置自动发货</button>
                  <button class="btn-glass btn-glass--danger" @click="handleDelete">删除商品</button>
                </div>
              </div>
            </div>

            <!-- 价格趋势 -->
            <div class="trend-section">
              <div class="trend-section__header">
                <span class="trend-section__title">价格趋势</span>
                <span class="trend-section__range">近 30 天</span>
              </div>

              <div v-if="priceTrendLoading" class="trend-loading" aria-hidden="true">
                <div class="trend-loading__bar"></div>
                <div class="trend-loading__bar trend-loading__bar--short"></div>
              </div>

              <EmptyState
                v-else-if="priceTrendLoaded && !trendChart"
                title="价格数据积累中"
                desc="刷新商品后生成价格趋势"
              />

              <template v-else-if="trendChart">
                <div class="trend-chart">
                  <svg :viewBox="`0 0 ${TREND_W} ${TREND_H}`" class="trend-chart__svg" role="img" aria-label="近30天价格走势">
                    <path
                      :d="trendChart.path"
                      fill="none"
                      stroke="var(--brand-600)"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                    <circle :cx="trendChart.first.x" :cy="trendChart.first.y" r="3.5" fill="var(--brand-600)" />
                    <circle :cx="trendChart.last.x" :cy="trendChart.last.y" r="3.5" fill="var(--brand-600)" />
                    <text :x="trendChart.first.x + 7" :y="trendChart.first.y - 7" class="trend-chart__label">
                      {{ formatTrendPrice(trendChart.first.point.price) }} · {{ formatTrendDate(trendChart.first.point.date) }}
                    </text>
                    <text :x="trendChart.last.x - 7" :y="trendChart.last.y - 7" text-anchor="end" class="trend-chart__label">
                      {{ formatTrendPrice(trendChart.last.point.price) }} · {{ formatTrendDate(trendChart.last.point.date) }}
                    </text>
                  </svg>
                </div>

                <div class="trend-stats">
                  <div class="trend-stat">
                    <span class="trend-stat__label">最高价</span>
                    <span class="trend-stat__value trend-stat__value--max">{{ formatTrendPrice(trendChart.stats.max) }}</span>
                  </div>
                  <div class="trend-stat">
                    <span class="trend-stat__label">最低价</span>
                    <span class="trend-stat__value trend-stat__value--min">{{ formatTrendPrice(trendChart.stats.min) }}</span>
                  </div>
                  <div class="trend-stat">
                    <span class="trend-stat__label">当前价</span>
                    <span class="trend-stat__value trend-stat__value--current">{{ formatTrendPrice(trendChart.stats.current) }}</span>
                  </div>
                </div>
              </template>
            </div>
          </div>

          <!-- Footer -->
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="handleClose">关闭</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  width: 100%;
  max-width: 720px;
  max-height: 88vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-container.is-mobile {
  max-width: 94vw;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: var(--radius-xs);
  border: none;
  background: transparent;
  color: var(--text-2);
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}

.modal-body {
  flex: 1;
  padding: 0 20px 20px;
  overflow-y: auto;
  min-height: 0;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 18px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: var(--glass-bg-deep);
  border: 1px solid var(--glass-border);
  color: var(--text-1);
  }

.btn-secondary:hover {
  background: var(--glass-bg);
}

.detail-content {
  display: flex;
  gap: 20px;
}

.detail-left {
  flex: 0 0 300px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.main-image {
  width: 100%;
  height: 300px;
  background: var(--bg-muted);
  border-radius: var(--radius-sm);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.main-image .native-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.thumbnails {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.thumbnail {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.2s;
  flex-shrink: 0;
}

.thumbnail.active {
  border-color: var(--brand-600);
}

.thumbnail .native-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.title-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.goods-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
  line-height: 1.4;
}

.goods-id {
  font-size: 12px;
  color: var(--text-2);
  font-family: 'SF Mono', Menlo, monospace;
}

.price-section {
  display: flex;
  align-items: center;
  gap: 12px;
}

.price {
  font-size: 20px;
  font-weight: 700;
  color: var(--danger-500);
}

.description {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.description-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.description-content {
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.6;
  background: var(--bg-muted);
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--border-1);
}

.config-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.config-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.config-label {
  font-size: 13px;
  color: var(--text-2);
  min-width: 70px;
}

.config-value {
  display: flex;
  align-items: center;
}

.detail-mode-tag {
  padding: 4px 10px;
  border-radius: var(--radius-xs);
  font-size: 12px;
  font-weight: 500;
}

.detail-mode-tag--delivery {
  background: var(--success-50);
  color: var(--success-500);
}

.detail-mode-tag--reply {
  background: rgba(191,90,242,.15);
  color: var(--purple-500);
}

.detail-mode-tag--off {
  background: rgba(120,120,128,.12);
  color: var(--text-2);
}

.action-buttons {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}

/* Mobile */
@media (max-width: 768px) {
  .modal-container {
    max-width: 94vw;
  }

  .detail-content {
    flex-direction: column;
  }

  .detail-left {
    flex: none;
    width: 100%;
  }

  .main-image {
    height: 250px;
  }
}

.btn-glass { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 8px 16px; border-radius: var(--radius-pill); font-size: 13px; font-weight: 590; cursor: pointer; transition: opacity .15s, transform .12s; border: none; font-family: inherit; user-select: none; white-space: nowrap; }
.btn-glass:active { opacity: .80; transform: scale(.96); }
.btn-glass--primary { background: var(--brand-600); color: #fff; border: 1px solid var(--border-1); box-shadow: 0 4px 16px var(--brand-500), 0 8px 32px rgba(0,0,0,0.08); }
.btn-glass--default { background: var(--glass-bg); color: var(--brand-600); border: 1px solid var(--glass-border); box-shadow: 0 8px 32px rgba(0,0,0,0.08); }
.btn-glass--success { background: var(--success-500); color: #fff; border: 1px solid var(--border-1); }
.btn-glass--warning { background: var(--warning-500); color: #fff; border: 1px solid var(--border-1); }
.btn-glass--danger { color: var(--danger-500); background: var(--danger-50); border: 1px solid var(--border-1); }
.tag { display: inline-flex; align-items: center; padding: 2px 10px; border-radius: var(--radius-pill); font-size: 12px; font-weight: 500; }
.tag--success { background: var(--success-50); color: var(--success-500); }
.tag--warning { background: var(--warning-50); color: var(--warning-500); }
.tag--info { background: var(--bg-muted); color: var(--text-2); }
.tag--danger { background: var(--danger-50); color: var(--danger-500); }
.native-image { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; }

/* ============================================================
   Price Trend Section
   ============================================================ */
.trend-section {
  margin-top: 16px;
  padding: 14px 16px;
  background: var(--bg-muted);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
}

.trend-section__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.trend-section__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.trend-section__range {
  font-size: 11px;
  color: var(--text-3);
}

.trend-chart {
  width: 100%;
}

.trend-chart__svg {
  width: 100%;
  height: auto;
  display: block;
}

.trend-chart__label {
  fill: var(--text-2);
  font-size: 10px;
  font-family: var(--font-mono);
  user-select: none;
}

.trend-stats {
  display: flex;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.trend-stat {
  flex: 1;
  min-width: 90px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-radius: 8px;
}

.trend-stat__label {
  font-size: 11px;
  color: var(--text-3);
}

.trend-stat__value {
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.trend-stat__value--max {
  color: var(--danger-500);
}

.trend-stat__value--min {
  color: var(--success-600);
}

.trend-stat__value--current {
  color: var(--brand-600);
}

/* 价格趋势加载骨架 */
.trend-loading {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 4px;
}

.trend-loading__bar {
  height: 12px;
  border-radius: var(--radius-xs);
  background: linear-gradient(90deg, var(--bg-hover) 25%, var(--border-1) 50%, var(--bg-hover) 75%);
  background-size: 200% 100%;
  animation: trend-loading-shimmer 1.2s ease-in-out infinite;
}

.trend-loading__bar--short {
  width: 60%;
}

@keyframes trend-loading-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* 主图区内的紧凑空态 */
.main-image__empty :deep(.empty-state) {
  min-height: 0;
  padding: var(--space-3);
  gap: var(--space-1);
  background: transparent;
}

.main-image__empty :deep(.empty-state__icon) {
  width: 40px;
  height: 40px;
  margin-bottom: 0;
}

/* Mobile */
@media (max-width: 768px) {
  .trend-stat {
    min-width: calc(33.33% - 8px);
  }
}
</style>
