import type { NormalizedUsage } from '@/api/generated'

export const UNKNOWN_TEXT = '未知'
export type UsageTrendResolution = 'HOUR' | 'DAY'
export type UsageDisplayedResolution = UsageTrendResolution | 'MILLISECOND'

export function normalizeUsageTrendResolution(value: unknown): UsageTrendResolution {
  return value === 'HOUR' ? 'HOUR' : 'DAY'
}

export const USAGE_STATUS_OPTIONS = [
  { label: '成功', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
  { label: '超时', value: 'TIMED_OUT' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '已废弃', value: 'ABANDONED' },
  { label: '进行中', value: 'IN_PROGRESS' },
] as const

export const USAGE_QUALITY_OPTIONS = [
  { label: '供应商分项报告', value: 'REPORTED_COMPONENTS' },
  { label: '供应商报告总量', value: 'REPORTED_TOTAL' },
  { label: '部分用量', value: 'PARTIAL' },
  { label: '估算用量', value: 'ESTIMATED' },
  { label: '用量缺失', value: 'MISSING' },
] as const

// 用量统计的 modelType 过滤值与后端 ModelType.name() 存储值一致，
// 与 AiModel.spec.modelType 的 kebab 形式不同。
export const USAGE_MODEL_TYPE_OPTIONS = [
  { label: '语言模型', value: 'LANGUAGE' },
  { label: 'Embedding', value: 'EMBEDDING' },
  { label: 'Rerank', value: 'RERANK' },
  { label: '图像生成', value: 'IMAGE_GENERATION' },
] as const

export const USAGE_OPERATION_OPTIONS = [
  { label: '文本生成', value: 'language.generateText' },
  { label: '流式文本生成', value: 'language.streamText' },
  { label: '文本嵌入', value: 'embedding.embed' },
  { label: '查询嵌入', value: 'embedding.embedQuery' },
  { label: '重排序', value: 'rerank.rerank' },
  { label: '图像生成', value: 'image.generateImage' },
] as const

const STATUS_LABELS = toLabelMap(USAGE_STATUS_OPTIONS)
const QUALITY_LABELS = toLabelMap(USAGE_QUALITY_OPTIONS)
const MODEL_TYPE_LABELS = toLabelMap(USAGE_MODEL_TYPE_OPTIONS)
const OPERATION_LABELS = toLabelMap(USAGE_OPERATION_OPTIONS)

const UNIT_KIND_LABELS: Record<string, string> = {
  GENERATION_STEP: '生成步骤',
  EMBEDDING_BATCH: '嵌入批次',
  RERANK: '重排序',
  IMAGE_BATCH: '图像批次',
}

const STATUS_BADGE_CLASSES: Record<string, string> = {
  SUCCEEDED: 'bg-emerald-50 text-emerald-700',
  FAILED: 'bg-red-50 text-red-700',
  TIMED_OUT: 'bg-amber-50 text-amber-700',
  CANCELLED: 'bg-gray-100 text-gray-600',
  ABANDONED: 'bg-gray-100 text-gray-500',
  IN_PROGRESS: 'bg-blue-50 text-blue-700',
}

const QUALITY_BADGE_CLASSES: Record<string, string> = {
  REPORTED_COMPONENTS: 'bg-emerald-50 text-emerald-700',
  REPORTED_TOTAL: 'bg-emerald-50 text-emerald-700',
  PARTIAL: 'bg-amber-50 text-amber-700',
  ESTIMATED: 'bg-blue-50 text-blue-700',
  MISSING: 'bg-red-50 text-red-700',
}

function toLabelMap(options: readonly { label: string; value: string }[]) {
  return Object.fromEntries(options.map((item) => [item.value, item.label]))
}

function labelOf(labels: Record<string, string>, value?: string | null) {
  if (!value) {
    return UNKNOWN_TEXT
  }
  return labels[value] || value
}

export function usageStatusLabel(value?: string | null) {
  return labelOf(STATUS_LABELS, value)
}

export function usageQualityLabel(value?: string | null) {
  return labelOf(QUALITY_LABELS, value)
}

export function usageModelTypeLabel(value?: string | null) {
  return labelOf(MODEL_TYPE_LABELS, value)
}

export function usageOperationLabel(value?: string | null) {
  return labelOf(OPERATION_LABELS, value)
}

export function usageUnitKindLabel(value?: string | null) {
  return labelOf(UNIT_KIND_LABELS, value)
}

export function usageStatusBadgeClass(value?: string | null) {
  return STATUS_BADGE_CLASSES[value || ''] || 'bg-gray-100 text-gray-600'
}

export function usageQualityBadgeClass(value?: string | null) {
  return QUALITY_BADGE_CLASSES[value || ''] || 'bg-gray-100 text-gray-600'
}

/** Token 数值展示：null/undefined 一律显示「未知」，绝不显示 0。 */
export function formatTokens(value?: number | null) {
  if (value === null || value === undefined) {
    return UNKNOWN_TEXT
  }
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 用量覆盖率（0~1）展示为百分比，缺失时显示「未知」。 */
export function formatCoverage(value?: number | null) {
  if (value === null || value === undefined) {
    return UNKNOWN_TEXT
  }
  const percent = value * 100
  const rounded = percent >= 99.95 && percent < 100 ? 99.9 : Math.round(percent * 10) / 10
  return `${rounded}%`
}

export function usageResolutionLabel(value?: UsageDisplayedResolution | null) {
  if (value === 'DAY') {
    return '按天（UTC）'
  }
  if (value === 'HOUR') {
    return '按小时（UTC）'
  }
  if (value === 'MILLISECOND') {
    return '精确'
  }
  return value || UNKNOWN_TEXT
}

export function formatDuration(millis?: number | null) {
  if (millis === null || millis === undefined) {
    return UNKNOWN_TEXT
  }
  if (millis < 1000) {
    return `${millis} ms`
  }
  const seconds = millis / 1000
  return `${seconds >= 100 ? Math.round(seconds) : Math.round(seconds * 10) / 10} s`
}

/** ISO instant 转本地时间字符串，缺失时显示「未知」。 */
export function formatDateTime(iso?: string | null) {
  if (!iso) {
    return UNKNOWN_TEXT
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return UNKNOWN_TEXT
  }
  const pad = (value: number) => String(value).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** UTC 桶起始时间按分辨率展示；DAY 分辨率只展示日期，避免暗示小时精度。 */
export function formatBucketStart(
  iso: string | undefined,
  resolution?: UsageDisplayedResolution | null,
) {
  if (!iso) {
    return UNKNOWN_TEXT
  }
  if (resolution === 'DAY') {
    return iso.slice(0, 10)
  }
  return formatDateTime(iso)
}

export function usageQualityOf(usage?: NormalizedUsage | null) {
  return usage?.quality || 'MISSING'
}
