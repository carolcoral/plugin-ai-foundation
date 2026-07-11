<script setup lang="ts">
import { IconArrowRight } from '@halo-dev/components'
import { CollapsibleContent, CollapsibleRoot, CollapsibleTrigger } from 'reka-ui'
import { onMounted, shallowRef } from 'vue'

defineProps<{
  sourceLabel: string
}>()

// Reka 2.10.1 stores the generated content ID in a non-reactive context.
// Refresh the trigger once after its sibling content has registered that ID.
const contentReady = shallowRef(false)
onMounted(() => {
  contentReady.value = true
})
</script>

<template>
  <CollapsibleRoot class=":uno: mt-4" :unmount-on-hide="false">
    <CollapsibleTrigger
      :data-content-ready="contentReady || undefined"
      class=":uno: group min-h-10 w-full flex items-center justify-between gap-3 rounded-md px-3 py-2 text-left text-sm text-gray-700 transition-colors hover:bg-gray-100"
    >
      <span class=":uno: flex items-center gap-2 font-medium">
        <IconArrowRight
          class=":uno: h-4 w-4 text-gray-500 transition-transform group-data-[state=open]:rotate-90"
        />
        高级设置
      </span>
      <span class=":uno: text-xs text-gray-500">来源：{{ sourceLabel }}</span>
    </CollapsibleTrigger>
    <CollapsibleContent class=":uno: mt-4 border-l border-gray-100 pl-3 space-y-4">
      <slot />
    </CollapsibleContent>
  </CollapsibleRoot>
</template>
