<script setup lang="ts">
import type { AiModel, AiProvider, CallerPluginInfo } from '@/api/generated'
import { USAGE_RANGE_OPTIONS, type UsageFilterState } from '@/composables/use-usage-filters'
import {
  USAGE_MODEL_TYPE_OPTIONS,
  USAGE_OPERATION_OPTIONS,
  USAGE_QUALITY_OPTIONS,
  USAGE_STATUS_OPTIONS,
} from '@/utils/usage'
import { IconRefreshLine, VSpace } from '@halo-dev/components'
import { computed } from 'vue'

const props = defineProps<{
  state: UsageFilterState
  callers?: CallerPluginInfo[]
  providers?: AiProvider[]
  models?: AiModel[]
  featureInvalid?: boolean
  hasDimensionFilters?: boolean
  fetching?: boolean
}>()

const emit = defineEmits<{
  (event: 'change', patch: Partial<UsageFilterState>): void
  (event: 'clear'): void
  (event: 'refresh'): void
  (event: 'reset'): void
}>()

const callerItems = computed(() => [
  { label: '全部', value: undefined },
  ...(props.callers || [])
    .filter((caller) => caller.pluginName)
    .map((caller) => ({
      label: caller.displayName
        ? `${caller.displayName}（${caller.pluginName}）`
        : (caller.pluginName as string),
      value: caller.pluginName as string,
    })),
])

const providerItems = computed(() => [
  { label: '全部', value: undefined },
  ...(props.providers || []).map((provider) => ({
    label: provider.spec.displayName
      ? `${provider.spec.displayName}（${provider.metadata.name}）`
      : provider.metadata.name,
    value: provider.metadata.name,
  })),
])

const modelItems = computed(() => [
  { label: '全部', value: undefined },
  ...(props.models || []).map((model) => ({
    label: model.spec.displayName
      ? `${model.spec.displayName}（${model.metadata.name}）`
      : model.metadata.name,
    value: model.metadata.name,
  })),
])

const ALL_OPTION = { label: '全部', value: undefined }

function update(patch: Partial<UsageFilterState>) {
  emit('change', patch)
}
</script>

<template>
  <div class=":uno: flex flex-col gap-2">
    <div class=":uno: flex flex-col flex-wrap items-start gap-2 lg:flex-row lg:items-center">
      <VSpace class=":uno: flex-wrap">
        <FilterDropdown
          :model-value="state.range"
          label="时间范围"
          :items="[...USAGE_RANGE_OPTIONS]"
          @update:model-value="update({ range: $event || '30d' })"
        />
        <template v-if="state.range === 'custom'">
          <input
            type="date"
            aria-label="开始日期"
            class=":uno: h-9 border border-gray-200 rounded-md bg-white px-2 text-sm text-gray-700"
            :value="state.fromDate"
            @input="update({ fromDate: ($event.target as HTMLInputElement).value || undefined })"
          />
          <span class=":uno: text-xs text-gray-400">至</span>
          <input
            type="date"
            aria-label="结束日期"
            class=":uno: h-9 border border-gray-200 rounded-md bg-white px-2 text-sm text-gray-700"
            :value="state.toDate"
            @input="update({ toDate: ($event.target as HTMLInputElement).value || undefined })"
          />
        </template>
      </VSpace>

      <VSpace class=":uno: flex-wrap sm:ml-auto">
        <FilterCleanButton v-if="hasDimensionFilters" @click="emit('clear')" />
        <button
          type="button"
          class=":uno: group size-9 inline-flex cursor-pointer items-center justify-center border border-gray-200 rounded-md bg-white hover:bg-gray-50"
          v-tooltip="`刷新`"
          @click="emit('refresh')"
        >
          <IconRefreshLine
            :class="{ ':uno: animate-spin text-gray-900': fetching }"
            class=":uno: size-4 text-gray-600 group-hover:text-gray-900"
          />
        </button>
        <button
          type="button"
          class=":uno: h-9 inline-flex cursor-pointer items-center border border-red-200 rounded-md bg-white px-3 text-xs text-red-600 hover:bg-red-50"
          @click="emit('reset')"
        >
          重置统计
        </button>
      </VSpace>
    </div>

    <div v-if="state.range === 'custom'" class=":uno: text-xs text-gray-400">
      自定义范围按本地日期选择，查询时转换为 UTC 半开区间。
    </div>

    <div class=":uno: flex flex-wrap items-center gap-3">
      <FilterDropdown
        :model-value="state.callerPlugin"
        label="调用插件"
        :items="callerItems"
        @update:model-value="update({ callerPlugin: $event || undefined })"
      />
      <FilterDropdown
        :model-value="state.providerName"
        label="供应商"
        :items="providerItems"
        @update:model-value="update({ providerName: $event || undefined })"
      />
      <FilterDropdown
        :model-value="state.modelName"
        label="模型"
        :items="modelItems"
        @update:model-value="update({ modelName: $event || undefined })"
      />
      <FilterDropdown
        :model-value="state.modelType"
        label="模型类型"
        :items="[ALL_OPTION, ...USAGE_MODEL_TYPE_OPTIONS]"
        @update:model-value="update({ modelType: $event || undefined })"
      />
      <FilterDropdown
        :model-value="state.operation"
        label="操作"
        :items="[ALL_OPTION, ...USAGE_OPERATION_OPTIONS]"
        @update:model-value="update({ operation: $event || undefined })"
      />
      <FilterDropdown
        :model-value="state.status"
        label="状态"
        :items="[ALL_OPTION, ...USAGE_STATUS_OPTIONS]"
        @update:model-value="update({ status: $event || undefined })"
      />
      <FilterDropdown
        :model-value="state.usageQuality"
        label="用量质量"
        :items="[ALL_OPTION, ...USAGE_QUALITY_OPTIONS]"
        @update:model-value="update({ usageQuality: $event || undefined })"
      />
      <div class=":uno: flex items-center gap-1">
        <input
          type="text"
          aria-label="功能标识"
          placeholder="feature"
          class=":uno: h-9 w-40 border rounded-md bg-white px-2 text-sm text-gray-700"
          :class="featureInvalid ? ':uno: border-red-300' : ':uno: border-gray-200'"
          :value="state.feature"
          @input="update({ feature: ($event.target as HTMLInputElement).value || undefined })"
        />
      </div>
    </div>
    <div v-if="featureInvalid" class=":uno: text-xs text-red-600">
      功能标识需匹配 [a-z0-9._-]，长度 1-64；当前值不会作为过滤条件。
    </div>
  </div>
</template>
