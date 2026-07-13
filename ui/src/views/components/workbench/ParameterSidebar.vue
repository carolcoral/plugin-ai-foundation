<script lang="ts" setup>
import type { OutputMode, ReasoningEffort, ReasoningMode } from '@/utils/model-test-workbench'
import RiSettings3Line from '~icons/ri/settings-3-line'
import ChatParameterPanel from './ChatParameterPanel.vue'
import EmbeddingParameterPanel from './EmbeddingParameterPanel.vue'
import ImageParameterPanel from './ImageParameterPanel.vue'

type ImageResponseFormat = 'DEFAULT' | 'URL' | 'BASE64'

defineProps<{
  mode: 'chat' | 'embedding' | 'rerank' | 'image'
  systemPrompt?: string
  temperature?: number
  topP?: number
  maxTokens?: number
  seed?: number
  maxRetries?: number
  reasoningMode?: ReasoningMode
  reasoningEffort?: ReasoningEffort
  testToolEnabled?: boolean
  testToolApprovalEnabled?: boolean
  externalTestToolEnabled?: boolean
  agentTestToolsEnabled?: boolean
  toolCallRepairEnabled?: boolean
  outputMode?: OutputMode
  outputSchemaText?: string
  outputChoicesText?: string
  providerOptionsText?: string
  chatHeadersText?: string
  chatHeadersError?: string
  providerOptionsError?: string
  outputError?: string
  embeddingDimensions?: number
  embeddingMaxBatchSize?: number
  embeddingMaxParallelCalls?: number
  embeddingMaxRetries?: number
  embeddingProviderOptionsText?: string
  embeddingProviderOptionsError?: string
  imageN?: number
  imageWidth?: number
  imageHeight?: number
  imageAspectRatio?: string
  imageSeed?: number
  imageResponseFormat?: ImageResponseFormat
  imageMaxRetries?: number
  imageMaxParallelCalls?: number
  imageProviderOptionsText?: string
  imageProviderOptionsError?: string
  imageHeadersText?: string
  imageHeadersError?: string
}>()

const emit = defineEmits<{
  'update:systemPrompt': [value: string]
  'update:temperature': [value: number]
  'update:topP': [value: number]
  'update:maxTokens': [value: number]
  'update:seed': [value: number | undefined]
  'update:maxRetries': [value: number | undefined]
  'update:reasoningMode': [value: ReasoningMode]
  'update:reasoningEffort': [value: ReasoningEffort]
  'update:testToolEnabled': [value: boolean]
  'update:testToolApprovalEnabled': [value: boolean]
  'update:externalTestToolEnabled': [value: boolean]
  'update:agentTestToolsEnabled': [value: boolean]
  'update:toolCallRepairEnabled': [value: boolean]
  'update:outputMode': [value: OutputMode]
  'update:outputSchemaText': [value: string]
  'update:outputChoicesText': [value: string]
  'update:providerOptionsText': [value: string]
  'update:chatHeadersText': [value: string]
  'update:embeddingDimensions': [value: number | undefined]
  'update:embeddingMaxBatchSize': [value: number | undefined]
  'update:embeddingMaxParallelCalls': [value: number | undefined]
  'update:embeddingMaxRetries': [value: number | undefined]
  'update:embeddingProviderOptionsText': [value: string]
  'update:imageN': [value: number | undefined]
  'update:imageWidth': [value: number | undefined]
  'update:imageHeight': [value: number | undefined]
  'update:imageAspectRatio': [value: string]
  'update:imageSeed': [value: number | undefined]
  'update:imageResponseFormat': [value: ImageResponseFormat]
  'update:imageMaxRetries': [value: number | undefined]
  'update:imageMaxParallelCalls': [value: number | undefined]
  'update:imageProviderOptionsText': [value: string]
  'update:imageHeadersText': [value: string]
}>()
</script>

<template>
  <div
    class=":uno: h-full flex flex-col overflow-hidden border-t border-slate-200 bg-white lg:border-l lg:border-t-0"
  >
    <div class=":uno: border-b border-slate-200 bg-slate-50/70 px-4 py-3">
      <div class=":uno: flex items-center gap-2">
        <span class=":uno: h-7 w-7 flex items-center justify-center rounded-lg text-slate-600">
          <RiSettings3Line class=":uno: size-4" />
        </span>
        <span class=":uno: text-sm text-slate-950 font-semibold">参数设置</span>
      </div>
      <div class=":uno: mt-1 text-xs text-slate-500">调整模型行为、输出格式和请求扩展</div>
    </div>

    <div class=":uno: flex-1 overflow-y-auto px-4 py-3">
      <ChatParameterPanel
        v-if="mode === 'chat'"
        :system-prompt="systemPrompt"
        :temperature="temperature"
        :top-p="topP"
        :max-tokens="maxTokens"
        :seed="seed"
        :max-retries="maxRetries"
        :reasoning-mode="reasoningMode"
        :reasoning-effort="reasoningEffort"
        :test-tool-enabled="testToolEnabled"
        :test-tool-approval-enabled="testToolApprovalEnabled"
        :external-test-tool-enabled="externalTestToolEnabled"
        :agent-test-tools-enabled="agentTestToolsEnabled"
        :tool-call-repair-enabled="toolCallRepairEnabled"
        :output-mode="outputMode"
        :output-schema-text="outputSchemaText"
        :output-choices-text="outputChoicesText"
        :provider-options-text="providerOptionsText"
        :provider-options-error="providerOptionsError"
        :chat-headers-text="chatHeadersText"
        :chat-headers-error="chatHeadersError"
        :output-error="outputError"
        @update:system-prompt="emit('update:systemPrompt', $event)"
        @update:temperature="emit('update:temperature', $event)"
        @update:top-p="emit('update:topP', $event)"
        @update:max-tokens="emit('update:maxTokens', $event)"
        @update:seed="emit('update:seed', $event)"
        @update:max-retries="emit('update:maxRetries', $event)"
        @update:reasoning-mode="emit('update:reasoningMode', $event)"
        @update:reasoning-effort="emit('update:reasoningEffort', $event)"
        @update:test-tool-enabled="emit('update:testToolEnabled', $event)"
        @update:test-tool-approval-enabled="emit('update:testToolApprovalEnabled', $event)"
        @update:external-test-tool-enabled="emit('update:externalTestToolEnabled', $event)"
        @update:agent-test-tools-enabled="emit('update:agentTestToolsEnabled', $event)"
        @update:tool-call-repair-enabled="emit('update:toolCallRepairEnabled', $event)"
        @update:output-mode="emit('update:outputMode', $event)"
        @update:output-schema-text="emit('update:outputSchemaText', $event)"
        @update:output-choices-text="emit('update:outputChoicesText', $event)"
        @update:provider-options-text="emit('update:providerOptionsText', $event)"
        @update:chat-headers-text="emit('update:chatHeadersText', $event)"
      />

      <EmbeddingParameterPanel
        v-else-if="mode === 'embedding'"
        :embedding-dimensions="embeddingDimensions"
        :embedding-max-batch-size="embeddingMaxBatchSize"
        :embedding-max-parallel-calls="embeddingMaxParallelCalls"
        :embedding-max-retries="embeddingMaxRetries"
        :embedding-provider-options-text="embeddingProviderOptionsText"
        :embedding-provider-options-error="embeddingProviderOptionsError"
        @update:embedding-dimensions="emit('update:embeddingDimensions', $event)"
        @update:embedding-max-batch-size="emit('update:embeddingMaxBatchSize', $event)"
        @update:embedding-max-parallel-calls="emit('update:embeddingMaxParallelCalls', $event)"
        @update:embedding-max-retries="emit('update:embeddingMaxRetries', $event)"
        @update:embedding-provider-options-text="
          emit('update:embeddingProviderOptionsText', $event)
        "
      />

      <ImageParameterPanel
        v-else-if="mode === 'image'"
        :image-n="imageN"
        :image-width="imageWidth"
        :image-height="imageHeight"
        :image-aspect-ratio="imageAspectRatio"
        :image-seed="imageSeed"
        :image-response-format="imageResponseFormat"
        :image-max-retries="imageMaxRetries"
        :image-max-parallel-calls="imageMaxParallelCalls"
        :image-provider-options-text="imageProviderOptionsText"
        :image-provider-options-error="imageProviderOptionsError"
        :image-headers-text="imageHeadersText"
        :image-headers-error="imageHeadersError"
        @update:image-n="emit('update:imageN', $event)"
        @update:image-width="emit('update:imageWidth', $event)"
        @update:image-height="emit('update:imageHeight', $event)"
        @update:image-aspect-ratio="emit('update:imageAspectRatio', $event)"
        @update:image-seed="emit('update:imageSeed', $event)"
        @update:image-response-format="emit('update:imageResponseFormat', $event)"
        @update:image-max-retries="emit('update:imageMaxRetries', $event)"
        @update:image-max-parallel-calls="emit('update:imageMaxParallelCalls', $event)"
        @update:image-provider-options-text="emit('update:imageProviderOptionsText', $event)"
        @update:image-headers-text="emit('update:imageHeadersText', $event)"
      />
    </div>
  </div>
</template>
